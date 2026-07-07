package com.adpilot.modules.automation.service;

import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.support.AdMetrics;
import com.adpilot.modules.automation.entity.AutomationExecutionEntity;
import com.adpilot.modules.automation.entity.AutomationRuleTemplateEntity;
import com.adpilot.modules.automation.entity.AutomationRuleTemplateLinkEntity;
import com.adpilot.modules.automation.mapper.AutomationExecutionMapper;
import com.adpilot.modules.automation.mapper.AutomationRuleTemplateLinkMapper;
import com.adpilot.modules.automation.mapper.AutomationRuleTemplateMapper;
import com.adpilot.modules.automation.support.RuleAction;
import com.adpilot.modules.automation.support.RuleCondition;
import com.adpilot.modules.automation.support.RuleConditionCodec;
import com.adpilot.modules.automation.support.RuleConditionEvaluator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Scheduled evaluator that drives automation rule templates (Req 25.3): on each
 * tick it loads every enabled {@link AutomationRuleTemplateEntity}, parses its
 * stored condition / action into the pure value types consumed by
 * {@link RuleConditionEvaluator}, and for every linked object (campaign /
 * target / keyword) computes the object's recent metrics from
 * {@code performance_daily} and applies the template's action <strong>exactly
 * when</strong> the condition holds — never when it is false.
 *
 * <p>The actual decision is delegated to the pure
 * {@link RuleConditionEvaluator#resolveAction} so the soundness contract
 * exercised by Property 5 (task 11.6) governs production behavior: the action
 * is applied iff the returned optional is present. Each applied action writes an
 * auditable {@code automation_executions} row (status {@code applied}, source
 * {@code rule_template}) so it can be reviewed and rolled back.
 *
 * <p>Per-template and per-object failures are isolated: an exception while
 * evaluating one template or one linked object is logged and skipped so it
 * never aborts the whole tick (mirrors {@code ApprovalExpirationSweeper} and
 * {@code AiHostingOptimizer}). Scheduling is enabled application-wide via
 * {@code SchedulerConfig} ({@code @EnableScheduling}); the cadence is
 * configurable through {@code adpilot.automation.rule-eval-ms} (default 5 min).
 *
 * <p>Live Amazon Ads actuation is out of scope — applied actions are recorded in
 * the project's own {@code automation_executions} table (the stubbed
 * {@code PlatformConnector} seam).
 */
@Slf4j
@Component
public class RuleTemplateEvaluator {

    private static final String EXECUTION_SOURCE = "rule_template";
    private static final String EXECUTION_STATUS_APPLIED = "applied";
    private static final String EXECUTION_RISK_LEVEL = "low";

    private final AutomationRuleTemplateMapper templateMapper;
    private final AutomationRuleTemplateLinkMapper linkMapper;
    private final PerformanceDailyMapper performanceDailyMapper;
    private final AutomationExecutionMapper automationExecutionMapper;
    private final ObjectMapper objectMapper;

    /** Trailing window (days) used to compute a linked object's recent metrics. */
    @Value("${adpilot.automation.rule-eval-lookback-days:14}")
    private int lookbackDays;

    public RuleTemplateEvaluator(AutomationRuleTemplateMapper templateMapper,
                                 AutomationRuleTemplateLinkMapper linkMapper,
                                 PerformanceDailyMapper performanceDailyMapper,
                                 AutomationExecutionMapper automationExecutionMapper,
                                 ObjectMapper objectMapper) {
        this.templateMapper = templateMapper;
        this.linkMapper = linkMapper;
        this.performanceDailyMapper = performanceDailyMapper;
        this.automationExecutionMapper = automationExecutionMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Scheduled entry point (Req 25.3). Fires on the configured fixed delay and
     * delegates to {@link #runOnce()}; the run is fully self-contained and never
     * propagates an exception so the scheduler keeps ticking.
     */
    @Scheduled(fixedDelayString = "${adpilot.automation.rule-eval-ms:300000}")
    public void evaluate() {
        try {
            EvaluationSummary summary = runOnce();
            if (summary.getTemplatesProcessed() > 0) {
                log.info("Rule-template evaluation tick: {} templates, {} objects, {} actions applied, {} failed",
                        summary.getTemplatesProcessed(), summary.getObjectsEvaluated(),
                        summary.getActionsApplied(), summary.getTemplatesFailed());
            }
        } catch (Exception ex) {
            log.warn("Rule-template evaluation tick failed", ex);
        }
    }

    /**
     * Evaluate every enabled template once and return an aggregate summary.
     * Per-template failures are isolated (logged and skipped) so one template's
     * error never aborts the run.
     */
    public EvaluationSummary runOnce() {
        List<AutomationRuleTemplateEntity> templates = templateMapper.selectList(
                new LambdaQueryWrapper<AutomationRuleTemplateEntity>()
                        .eq(AutomationRuleTemplateEntity::getStatus,
                                AutomationRuleTemplateEntity.STATUS_ENABLED));

        EvaluationSummary summary = new EvaluationSummary();
        for (AutomationRuleTemplateEntity template : templates) {
            summary.templatesProcessed++;
            try {
                evaluateTemplate(template, summary);
            } catch (Exception ex) {
                summary.templatesFailed++;
                log.warn("Rule-template evaluation failed for template {}: {}",
                        template.getId(), rootMessage(ex));
            }
        }
        return summary;
    }

    /**
     * Evaluate a single template against every object linked to it. The
     * condition / action are parsed once; each linked object is evaluated in
     * isolation so a single bad object never aborts the template.
     */
    public void evaluateTemplate(AutomationRuleTemplateEntity template, EvaluationSummary summary) {
        if (template == null) {
            return;
        }
        RuleCondition condition = RuleConditionCodec.parseCondition(template.getConditionJson());
        RuleAction action = RuleConditionCodec.parseAction(template.getActionJson());

        List<AutomationRuleTemplateLinkEntity> links = linkMapper.selectList(
                new LambdaQueryWrapper<AutomationRuleTemplateLinkEntity>()
                        .eq(AutomationRuleTemplateLinkEntity::getTemplateId, template.getId()));

        for (AutomationRuleTemplateLinkEntity link : links) {
            summary.objectsEvaluated++;
            try {
                Map<String, BigDecimal> metrics = computeMetrics(link);
                // The single decision point: action applies iff condition is true.
                Optional<RuleAction> resolved = RuleConditionEvaluator.resolveAction(condition, action, metrics);
                if (resolved.isPresent()) {
                    writeExecution(template, link, resolved.get(), metrics);
                    summary.actionsApplied++;
                }
            } catch (Exception ex) {
                summary.objectsFailed++;
                log.warn("Rule-template evaluation failed for template {} object {}/{}: {}",
                        template.getId(), link.getObjectType(), link.getObjectId(), rootMessage(ex));
            }
        }
    }

    /**
     * Aggregate a linked object's recent metrics from {@code performance_daily}
     * over the lookback window, keyed by lower-case metric name so the condition
     * grammar (e.g. {@code acos}, {@code spend}, {@code ctr}) can reference them.
     * Returns an empty map when the object has no performance signal — an absent
     * metric makes any comparison {@code false}, so a template never fires on a
     * complete absence of data.
     */
    private Map<String, BigDecimal> computeMetrics(AutomationRuleTemplateLinkEntity link) {
        LocalDate since = LocalDate.now().minusDays(Math.max(0, lookbackDays));
        LambdaQueryWrapper<PerformanceDailyEntity> wrapper =
                new LambdaQueryWrapper<PerformanceDailyEntity>()
                        .ge(PerformanceDailyEntity::getDate, since);

        String objectType = link.getObjectType() == null ? "" : link.getObjectType().toLowerCase();
        switch (objectType) {
            case AutomationRuleTemplateLinkEntity.OBJECT_CAMPAIGN:
                wrapper.eq(PerformanceDailyEntity::getCampaignId, link.getObjectId());
                break;
            case AutomationRuleTemplateLinkEntity.OBJECT_KEYWORD:
                wrapper.eq(PerformanceDailyEntity::getKeywordId, link.getObjectId());
                break;
            case AutomationRuleTemplateLinkEntity.OBJECT_TARGET:
                wrapper.eq(PerformanceDailyEntity::getEntityType,
                                AutomationRuleTemplateLinkEntity.OBJECT_TARGET)
                        .eq(PerformanceDailyEntity::getEntityId, link.getObjectId());
                break;
            default:
                // Unknown object type: no metrics, so nothing fires.
                return Map.of();
        }

        List<PerformanceDailyEntity> rows = performanceDailyMapper.selectList(wrapper);
        if (rows.isEmpty()) {
            return Map.of();
        }

        BigDecimal impressions = BigDecimal.ZERO;
        BigDecimal clicks = BigDecimal.ZERO;
        BigDecimal spend = BigDecimal.ZERO;
        BigDecimal sales = BigDecimal.ZERO;
        BigDecimal orders = BigDecimal.ZERO;
        for (PerformanceDailyEntity row : rows) {
            impressions = impressions.add(nvl(row.getImpressions()));
            clicks = clicks.add(nvl(row.getClicks()));
            spend = spend.add(nvl(row.getSpend()));
            sales = sales.add(nvl(row.getSales()));
            orders = orders.add(nvl(row.getOrders()));
        }

        Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        metrics.put("impressions", impressions);
        metrics.put("clicks", clicks);
        metrics.put("spend", spend);
        metrics.put("sales", sales);
        metrics.put("orders", orders);
        // Derived metrics computed with the shared, total (no-throw) helpers.
        metrics.put("acos", AdMetrics.acos(spend, sales));
        metrics.put("roas", sales.signum() == 0 ? BigDecimal.ZERO
                : sales.divide(spend.signum() == 0 ? BigDecimal.ONE : spend, 4, java.math.RoundingMode.HALF_UP));
        metrics.put("ctr", ratioPercent(clicks, impressions));
        metrics.put("cvr", ratioPercent(orders, clicks));
        metrics.put("avg_cpc", clicks.signum() == 0 ? BigDecimal.ZERO
                : spend.divide(clicks, 4, java.math.RoundingMode.HALF_UP));
        return metrics;
    }

    private void writeExecution(AutomationRuleTemplateEntity template,
                                AutomationRuleTemplateLinkEntity link,
                                RuleAction action,
                                Map<String, BigDecimal> metrics) {
        automationExecutionMapper.insert(AutomationExecutionEntity.builder()
                .storeId(template.getStoreId())
                .source(EXECUTION_SOURCE)
                .entityType(link.getObjectType())
                .entityId(link.getObjectId())
                .actionType(action.type())
                .beforeSnapshot(toJson(metrics))
                .afterSnapshot(toJson(actionSnapshot(template, action)))
                .riskLevel(EXECUTION_RISK_LEVEL)
                .status(EXECUTION_STATUS_APPLIED)
                .build());
    }

    private Map<String, Object> actionSnapshot(AutomationRuleTemplateEntity template, RuleAction action) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("templateId", template.getId() != null ? template.getId().toString() : null);
        snapshot.put("templateName", template.getName());
        snapshot.put("actionType", action.type());
        snapshot.put("params", action.params());
        return snapshot;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static BigDecimal ratioPercent(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, 8, java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal nvl(Number value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        return BigDecimal.valueOf(value.doubleValue());
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m != null ? m : cur.getClass().getSimpleName();
    }

    /** Aggregate counters for one evaluation run. */
    @Getter
    public static final class EvaluationSummary {
        private int templatesProcessed;
        private int templatesFailed;
        private int objectsEvaluated;
        private int objectsFailed;
        private int actionsApplied;
    }
}
