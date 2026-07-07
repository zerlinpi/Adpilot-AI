package com.adpilot.modules.automation.service.impl;

import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.entity.NegativeKeywordEntity;
import com.adpilot.modules.advertising.entity.SearchTermEntity;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.NegativeKeywordMapper;
import com.adpilot.modules.advertising.mapper.SearchTermMapper;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformWriteResult;
import com.adpilot.modules.approval.annotation.RequiresApproval;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.automation.entity.AutomationRuleEntity;
import com.adpilot.modules.automation.mapper.AutomationRuleMapper;
import com.adpilot.modules.automation.service.AutomationRunner;
import com.adpilot.modules.automation.vo.AutomationRunSummaryVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link AutomationRunner} (Req 13.2).
 *
 * <h2>Evaluation (Req 13.2.1)</h2>
 * <p>{@link #runEnabledRules()} loads every enabled {@code automation_rules} row
 * and dispatches each to {@link #executeRule(AutomationRuleEntity)} <em>through
 * the Spring proxy</em> ({@link #self}) so the {@code @RequiresApproval} gate can
 * apply (Req 13.2.6). The scheduler wiring is task 26.2.
 *
 * <h2>Bid adjustment (Req 13.2.2, 13.2.5)</h2>
 * <p>For a {@code bid_adjustment} rule, each enabled keyword for the store is
 * evaluated against the rule's condition. When matched, the adjusted bid is
 * computed from the configured {@code adjustmentPct} and clamped within
 * {@code [min_bid, max_bid]} via {@link AutomationRunner#clampBid}. The clamped
 * value is submitted to the live platform; the internal keyword bid is updated
 * only after the platform accepts.
 *
 * <h2>Negative keyword (Req 13.2.3)</h2>
 * <p>For a {@code negative_keyword} rule, qualifying search terms (by min clicks
 * / max orders) are submitted as negative keywords; the internal
 * {@code negative_keywords} row is created only after the platform accepts.
 *
 * <h2>Audit &amp; rejection (Req 13.2.4, 13.2.7)</h2>
 * <p>Every executed change is written to {@code audit_logs} with source
 * {@code "automation"}. On platform rejection (or when no write connector is
 * registered for the platform), the failure reason is recorded and the internal
 * record is left unchanged.
 */
@Slf4j
@Service
public class AutomationRunnerImpl implements AutomationRunner {

    /** Approval governance coordinates for automated execution (Req 13.2.6). */
    static final String APPROVAL_MODULE = "automation";
    static final String APPROVAL_ACTION = "execute";

    /** Audit actions / entity type recorded for automation (Req 13.2.4 / 13.2.7). */
    static final String AUDIT_ENTITY_TYPE = "AUTOMATION_RULE";
    static final String ACTION_APPLIED = "AUTOMATION_APPLIED";
    static final String ACTION_REJECTED = "AUTOMATION_REJECTED";
    static final String SOURCE_AUTOMATION = "automation";

    private static final String CHANGE_BID = "bid_change";
    private static final String CHANGE_NEGATIVE_KEYWORD = "negative_keyword";

    private final AutomationRuleMapper automationRuleMapper;
    private final KeywordMapper keywordMapper;
    private final SearchTermMapper searchTermMapper;
    private final NegativeKeywordMapper negativeKeywordMapper;
    private final PlatformConnectionMapper platformConnectionMapper;
    private final AuditLogService auditLogService;
    private final CryptoUtil cryptoUtil;
    private final ObjectMapper objectMapper;

    /** Self-proxy so {@code @RequiresApproval} advice applies to executeRule (Req 13.2.6). */
    private final AutomationRunner self;

    /** platform key -> write connector, built from all injected connector beans. */
    private final Map<String, PlatformWriteConnector> writeConnectors = new ConcurrentHashMap<>();

    public AutomationRunnerImpl(AutomationRuleMapper automationRuleMapper,
                                KeywordMapper keywordMapper,
                                SearchTermMapper searchTermMapper,
                                NegativeKeywordMapper negativeKeywordMapper,
                                PlatformConnectionMapper platformConnectionMapper,
                                AuditLogService auditLogService,
                                CryptoUtil cryptoUtil,
                                ObjectMapper objectMapper,
                                @Lazy AutomationRunner self,
                                List<PlatformWriteConnector> writeConnectorBeans) {
        this.automationRuleMapper = automationRuleMapper;
        this.keywordMapper = keywordMapper;
        this.searchTermMapper = searchTermMapper;
        this.negativeKeywordMapper = negativeKeywordMapper;
        this.platformConnectionMapper = platformConnectionMapper;
        this.auditLogService = auditLogService;
        this.cryptoUtil = cryptoUtil;
        this.objectMapper = objectMapper;
        this.self = self;
        for (PlatformWriteConnector connector : writeConnectorBeans) {
            this.writeConnectors.put(connector.platform(), connector);
        }
    }

    @Override
    public AutomationRunSummaryVo runEnabledRules() {
        return runForRules(enabledRules(null));
    }

    @Override
    public AutomationRunSummaryVo runEnabledRules(UUID storeId) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId is required");
        }
        return runForRules(enabledRules(storeId));
    }

    private List<AutomationRuleEntity> enabledRules(UUID storeId) {
        LambdaQueryWrapper<AutomationRuleEntity> query = new LambdaQueryWrapper<AutomationRuleEntity>()
                .eq(AutomationRuleEntity::getEnabled, true);
        if (storeId != null) {
            query.eq(AutomationRuleEntity::getStoreId, storeId);
        }
        return automationRuleMapper.selectList(query);
    }

    private AutomationRunSummaryVo runForRules(List<AutomationRuleEntity> rules) {
        AutomationRunSummaryVo total = AutomationRunSummaryVo.builder().build();
        for (AutomationRuleEntity rule : rules) {
            total.setRulesEvaluated(total.getRulesEvaluated() + 1);
            // Invoke through the proxy so @RequiresApproval can gate (Req 13.2.6).
            AutomationRunSummaryVo perRule;
            try {
                perRule = self.executeRule(rule);
            } catch (Exception e) {
                log.warn("Automation rule {} failed to execute: {}", rule.getId(), rootMessage(e));
                continue;
            }
            if (perRule == null) {
                // Gated into a pending-approval state (Req 13.2.6): nothing submitted.
                total.setGatedForApproval(total.getGatedForApproval() + 1);
                continue;
            }
            total.setChangesSubmitted(total.getChangesSubmitted() + perRule.getChangesSubmitted());
            total.setChangesAccepted(total.getChangesAccepted() + perRule.getChangesAccepted());
            total.setChangesRejected(total.getChangesRejected() + perRule.getChangesRejected());
            total.setChangesClamped(total.getChangesClamped() + perRule.getChangesClamped());
        }
        return total;
    }

    @Override
    @Transactional
    @RequiresApproval(module = APPROVAL_MODULE, action = APPROVAL_ACTION)
    public AutomationRunSummaryVo executeRule(AutomationRuleEntity rule) {
        AutomationRunSummaryVo summary = AutomationRunSummaryVo.builder().rulesEvaluated(1).build();
        if (rule == null || rule.getStoreId() == null) {
            return summary;
        }
        Map<String, Object> condition = parseCondition(rule.getConditionJson());

        // Resolve the write path once per rule (Req 13.2.2/13.2.3 submit to live platform).
        PlatformConnectionEntity connection = resolveConnection(rule.getStoreId());
        if (connection == null) {
            log.info("Automation rule {} skipped: store {} has no platform connection",
                    rule.getId(), rule.getStoreId());
            return summary;
        }
        PlatformWriteConnector connector = writeConnectors.get(connection.getPlatform());
        ConnectionContext ctx = connector == null ? null : new ConnectionContext(
                connection.getId(), rule.getStoreId(), connection.getPlatform(),
                decryptConfig(connection.getConfigEncrypted()));

        if (AutomationRuleEntity.TYPE_BID_ADJUSTMENT.equalsIgnoreCase(rule.getRuleType())) {
            evaluateBidAdjustment(rule, condition, connection, connector, ctx, summary);
        } else if (AutomationRuleEntity.TYPE_NEGATIVE_KEYWORD.equalsIgnoreCase(rule.getRuleType())) {
            evaluateNegativeKeyword(rule, condition, connection, connector, ctx, summary);
        } else {
            log.warn("Automation rule {} has unknown rule_type '{}'; skipping",
                    rule.getId(), rule.getRuleType());
        }
        return summary;
    }

    // ── bid adjustment (Req 13.2.2, 13.2.5) ────────────────────────────────────

    private void evaluateBidAdjustment(AutomationRuleEntity rule, Map<String, Object> condition,
                                       PlatformConnectionEntity connection,
                                       PlatformWriteConnector connector, ConnectionContext ctx,
                                       AutomationRunSummaryVo summary) {
        String metric = stringValue(condition, "metric", "acos");
        String operator = stringValue(condition, "operator", "gt");
        BigDecimal threshold = decimalValue(condition, "threshold");
        BigDecimal adjustmentPct = decimalValue(condition, "adjustmentPct");
        if (adjustmentPct == null) {
            adjustmentPct = BigDecimal.ZERO;
        }

        List<KeywordEntity> keywords = keywordMapper.selectList(
                new LambdaQueryWrapper<KeywordEntity>()
                        .eq(KeywordEntity::getStoreId, rule.getStoreId())
                        .eq(KeywordEntity::getStatus, "enabled")
                        .isNotNull(KeywordEntity::getBid));

        for (KeywordEntity keyword : keywords) {
            if (!conditionMet(metricValue(keyword, metric), operator, threshold)) {
                continue;
            }
            BigDecimal current = keyword.getBid();
            BigDecimal proposed = current
                    .multiply(BigDecimal.ONE.add(adjustmentPct.movePointLeft(2)))
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal clamped = AutomationRunner.clampBid(proposed, rule.getMinBid(), rule.getMaxBid());
            if (clamped == null) {
                continue;
            }
            if (clamped.compareTo(proposed) != 0) {
                summary.setChangesClamped(summary.getChangesClamped() + 1);
            }
            if (clamped.compareTo(current) == 0) {
                continue; // no effective change
            }

            PlatformChange change = new PlatformChange(
                    connection.getPlatform(), rule.getStoreId(), CHANGE_BID,
                    "keyword", keyword.getId().toString(),
                    current.toPlainString(), clamped.toPlainString(),
                    SOURCE_AUTOMATION, rule.getId().toString());

            summary.setChangesSubmitted(summary.getChangesSubmitted() + 1);
            PlatformWriteResult result = submit(connector, ctx, change);
            if (result.accepted()) {
                // Req 13.2.2: record the internal change only after the platform accepts.
                keyword.setBid(clamped);
                keywordMapper.updateById(keyword);
                recordApplied(rule, change, result);
                summary.setChangesAccepted(summary.getChangesAccepted() + 1);
            } else {
                // Req 13.2.7: rejected -> record reason, leave the keyword unchanged.
                recordRejection(rule, change, result);
                summary.setChangesRejected(summary.getChangesRejected() + 1);
            }
        }
    }

    // ── negative keyword (Req 13.2.3) ──────────────────────────────────────────

    private void evaluateNegativeKeyword(AutomationRuleEntity rule, Map<String, Object> condition,
                                         PlatformConnectionEntity connection,
                                         PlatformWriteConnector connector, ConnectionContext ctx,
                                         AutomationRunSummaryVo summary) {
        BigDecimal minClicks = decimalValue(condition, "minClicks");
        BigDecimal maxOrders = decimalValue(condition, "maxOrders");
        if (maxOrders == null) {
            maxOrders = BigDecimal.ZERO;
        }
        String matchType = stringValue(condition, "matchType", "negativeExact");

        LambdaQueryWrapper<SearchTermEntity> query = new LambdaQueryWrapper<SearchTermEntity>()
                .eq(SearchTermEntity::getStoreId, rule.getStoreId())
                .eq(SearchTermEntity::getHarvested, false);
        if (minClicks != null) {
            query.ge(SearchTermEntity::getClicks, minClicks.intValue());
        }
        query.le(SearchTermEntity::getOrders, maxOrders.intValue());

        List<SearchTermEntity> terms = searchTermMapper.selectList(query);
        for (SearchTermEntity term : terms) {
            PlatformChange change = new PlatformChange(
                    connection.getPlatform(), rule.getStoreId(), CHANGE_NEGATIVE_KEYWORD,
                    "search_term", term.getId().toString(),
                    null, term.getSearchTerm(),
                    SOURCE_AUTOMATION, rule.getId().toString());

            summary.setChangesSubmitted(summary.getChangesSubmitted() + 1);
            PlatformWriteResult result = submit(connector, ctx, change);
            if (result.accepted()) {
                // Req 13.2.3: record the internal negative keyword only after acceptance.
                NegativeKeywordEntity negative = NegativeKeywordEntity.builder()
                        .campaignId(term.getCampaignId())
                        .adGroupId(term.getAdGroupId())
                        .storeId(rule.getStoreId())
                        .keywordText(term.getSearchTerm())
                        .matchType(matchType)
                        .level(term.getAdGroupId() != null ? "adGroup" : "campaign")
                        .source(SOURCE_AUTOMATION)
                        .status("enabled")
                        .build();
                negativeKeywordMapper.insert(negative);
                term.setHarvested(true);
                searchTermMapper.updateById(term);
                recordApplied(rule, change, result);
                summary.setChangesAccepted(summary.getChangesAccepted() + 1);
            } else {
                // Req 13.2.7: rejected -> record reason, leave internal records unchanged.
                recordRejection(rule, change, result);
                summary.setChangesRejected(summary.getChangesRejected() + 1);
            }
        }
    }

    // ── submission ─────────────────────────────────────────────────────────────

    private PlatformWriteResult submit(PlatformWriteConnector connector, ConnectionContext ctx,
                                       PlatformChange change) {
        if (connector == null || ctx == null) {
            // No write connector registered for this platform yet (documented
            // integration point): treat as unavailable (Req 13.2.7 invariance).
            return PlatformWriteResult.rejected(
                    "Write-back is not available for platform '" + change.platform()
                            + "': no write connector is registered");
        }
        try {
            PlatformWriteResult result = connector.submit(ctx, change);
            return result != null ? result : PlatformWriteResult.rejected("Connector returned no result");
        } catch (Exception e) {
            return PlatformWriteResult.rejected(rootMessage(e));
        }
    }

    // ── condition evaluation helpers ─────────────────────────────────────────

    private boolean conditionMet(BigDecimal actual, String operator, BigDecimal threshold) {
        if (actual == null || threshold == null) {
            // No threshold configured -> the rule applies to all candidates.
            return threshold == null;
        }
        int cmp = actual.compareTo(threshold);
        return switch (operator == null ? "gt" : operator.toLowerCase()) {
            case "gt" -> cmp > 0;
            case "gte" -> cmp >= 0;
            case "lt" -> cmp < 0;
            case "lte" -> cmp <= 0;
            case "eq" -> cmp == 0;
            default -> false;
        };
    }

    private BigDecimal metricValue(KeywordEntity keyword, String metric) {
        return switch (metric == null ? "acos" : metric.toLowerCase()) {
            case "acos" -> keyword.getAcos();
            case "roas" -> keyword.getRoas();
            case "ctr" -> keyword.getCtr();
            case "cvr" -> keyword.getCvr();
            case "spend" -> keyword.getSpend();
            case "sales" -> keyword.getSales();
            case "clicks" -> keyword.getClicks() == null ? null : BigDecimal.valueOf(keyword.getClicks());
            case "orders" -> keyword.getOrders() == null ? null : BigDecimal.valueOf(keyword.getOrders());
            default -> null;
        };
    }

    private Map<String, Object> parseCondition(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse automation rule condition_json; treating as empty: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String stringValue(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return v == null ? defaultValue : v.toString();
    }

    private static BigDecimal decimalValue(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── connection resolution (mirrors WriteBackServiceImpl) ───────────────────

    private PlatformConnectionEntity resolveConnection(UUID storeId) {
        List<PlatformConnectionEntity> connections = platformConnectionMapper.selectList(
                new LambdaQueryWrapper<PlatformConnectionEntity>()
                        .eq(PlatformConnectionEntity::getStoreId, storeId)
                        .orderByDesc(PlatformConnectionEntity::getUpdatedAt));
        if (connections.isEmpty()) {
            return null;
        }
        return connections.stream()
                .filter(c -> ConnectionStatus.CONNECTED.equalsIgnoreCase(c.getStatus()))
                .findFirst()
                .orElse(connections.get(0));
    }

    private Map<String, String> decryptConfig(String stored) {
        if (stored == null || stored.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> encrypted = objectMapper.readValue(
                    stored, new TypeReference<LinkedHashMap<String, String>>() {});
            Map<String, String> plain = new LinkedHashMap<>();
            encrypted.forEach((k, v) -> plain.put(k, v == null ? null : cryptoUtil.decrypt(v)));
            return plain;
        } catch (Exception e) {
            log.warn("Failed to read platform config for automation write-back: {}", e.getMessage());
            return Map.of();
        }
    }

    // ── audit (Req 13.2.4 / 13.2.7) ────────────────────────────────────────────

    private void recordApplied(AutomationRuleEntity rule, PlatformChange change, PlatformWriteResult result) {
        auditLogService.createLog(currentUserOrNull(), orgIdOrNull(),
                ACTION_APPLIED, AUDIT_ENTITY_TYPE, rule.getId(), auditDetail(change, result));
    }

    private void recordRejection(AutomationRuleEntity rule, PlatformChange change, PlatformWriteResult result) {
        auditLogService.createLog(currentUserOrNull(), orgIdOrNull(),
                ACTION_REJECTED, AUDIT_ENTITY_TYPE, rule.getId(), auditDetail(change, result));
    }

    private Map<String, Object> auditDetail(PlatformChange change, PlatformWriteResult result) {
        Map<String, Object> detail = new LinkedHashMap<>();
        Map<String, Object> changeDetail = new LinkedHashMap<>();
        changeDetail.put("platform", change.platform());
        changeDetail.put("changeType", change.changeType());
        changeDetail.put("subjectType", change.subjectType());
        changeDetail.put("subjectId", change.subjectId());
        changeDetail.put("currentValue", change.currentValue());
        changeDetail.put("recommendedValue", change.recommendedValue());
        changeDetail.put("sourceType", change.sourceType());
        changeDetail.put("sourceId", change.sourceId());
        detail.put("change", changeDetail);
        detail.put("automated", true);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accepted", result.accepted());
        response.put("platformReference", result.platformReference());
        response.put("message", result.message());
        detail.put("platformResponse", response);
        return detail;
    }

    private static UUID currentUserOrNull() {
        return parseUuidOrNull(SecurityUtils.getCurrentUserIdOrNull());
    }

    private static UUID orgIdOrNull() {
        return SecurityUtils.isAuthenticated() ? parseUuidOrNull(SecurityUtils.getCurrentOrgId()) : null;
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m != null ? m : cur.getClass().getSimpleName();
    }
}
