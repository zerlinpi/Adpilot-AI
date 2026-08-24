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

/**
 * Scheduled evaluator for persisted automation rule templates.
 *
 * <p>Conditions are evaluated against recent performance for each linked object.
 * Metric computation is intentionally delegated to {@link AdMetrics} so the
 * automation engine and reporting surfaces use the same ACoS/ROAS/CTR/CVR/CPC
 * definitions and the same zero-denominator behavior.
 *
 * <p>Per-template and per-object failures are isolated. A bad rule or object is
 * logged and skipped without aborting the rest of the evaluation tick.
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

    /** Evaluate every enabled template once and return an aggregate summary. */
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

    /** Evaluate a single template against every object linked to it. */
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
     * Aggregate recent metrics for a linked object.
     *
     * <p>The metric vocabulary deliberately contains both canonical names and
     * compatibility aliases used by the existing frontend rule builder. In
     * particular, {@code conversion_rate} aliases {@code cvr}; this prevents
     * previously-created conversion-rate rules from silently evaluating false.
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

        BigDecimal acos = AdMetrics.acos(spend, sales);
        BigDecimal roas = AdMetrics.roas(sales, spend);
        BigDecimal ctr = AdMetrics.ctr(clicks, impressions);
        BigDecimal cvr = AdMetrics.cvr(orders, clicks);
        BigDecimal cpc = AdMetrics.cpc(spend, clicks);

        Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        metrics.put("impressions", impressions);
        metrics.put("clicks", clicks);
        metrics.put("spend", spend);
        metrics.put("sales", sales);
        metrics.put("orders", orders);
        metrics.put("acos", acos);
        metrics.put("roas", roas);
        metrics.put("ctr", ctr);
        metrics.put("cvr", cvr);
        metrics.put("conversion_rate", cvr);
        metrics.put("avg_cpc", cpc);
        metrics.put("cpc", cpc);
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
