package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;
import com.adpilot.modules.advertising.entity.SearchTermDailyEntity;
import com.adpilot.modules.advertising.service.AiPersonality;
import com.adpilot.modules.advertising.service.HostingAdjustmentType;
import com.adpilot.modules.advertising.service.HostingPhase;
import com.adpilot.modules.advertising.service.PersonalityPolicyService;
import com.adpilot.modules.advertising.service.PersonalityResolver;
import com.adpilot.modules.advertising.support.RiskScoreCalculator;
import com.adpilot.modules.advertising.support.RiskScoreInput;
import com.adpilot.modules.advertising.support.RiskScoreResult;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation of the V3 Search Term Harvest and Keyword Engine (Requirement 5).
 *
 * <p>Reads search term data exclusively from {@code search_term_daily} via the
 * immutable {@link DataSnapshot}, scores confidence from clicks/orders/ACoS/
 * statistical significance, and proposes keyword additions and negatives per
 * configured thresholds.</p>
 *
 * <p>Validates: Requirements 5.1, 5.2, 5.3, 5.5, 5.6, 5.7, 5.10, 5.11, 5.12, 31.4.</p>
 */
@Service
public class V3SearchTermEngineImpl implements V3SearchTermEngine {

    private static final Logger log = LoggerFactory.getLogger(V3SearchTermEngineImpl.class);

    static final String ENTITY_TYPE_KEYWORD = "keyword";
    static final String FIELD_KEYWORD = "keyword_text";
    static final String FIELD_NEGATIVE = "negative_keyword_text";
    static final String CHANGE_TYPE_KEYWORD = "keyword";
    static final String CHANGE_TYPE_NEGATIVE = "negative_keyword";
    static final String SKIP_PHASE_DISABLED = "PHASE_DISABLED";
    static final String SKIP_NO_TARGET_ACOS = "NO_TARGET_ACOS";
    static final String SKIP_NO_DATA = "NO_DATA";
    static final String SKIP_EXPANSION_OFF = "KEYWORD_EXPANSION_OFF";

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final BigDecimal DEFAULT_CONFIDENCE_THRESHOLD = new BigDecimal("0.60");
    private static final int DEFAULT_MAX_KEYWORDS_PER_DAY = 5;
    private static final int DEFAULT_MAX_NEGATIVES_PER_DAY = 10;
    private static final int DEFAULT_MIN_CLICKS = 20;

    private final PersonalityResolver personalityResolver;
    private final PersonalityPolicyService personalityPolicyService;
    private final RiskScoreCalculator riskScoreCalculator;
    private final BrandWordProtectionService brandWordProtectionService;
    private final AiDecisionMapper aiDecisionMapper;

    @Value("${adpilot.hosting.phase:V1}")
    private String phaseConfig;

    public V3SearchTermEngineImpl(PersonalityResolver personalityResolver,
                                  PersonalityPolicyService personalityPolicyService,
                                  RiskScoreCalculator riskScoreCalculator,
                                  BrandWordProtectionService brandWordProtectionService,
                                  AiDecisionMapper aiDecisionMapper) {
        this.personalityResolver = personalityResolver;
        this.personalityPolicyService = personalityPolicyService;
        this.riskScoreCalculator = riskScoreCalculator;
        this.brandWordProtectionService = brandWordProtectionService;
        this.aiDecisionMapper = aiDecisionMapper;
    }

    @Override
    public List<CandidateDecision> evaluate(CampaignEntity campaign,
                                            DataSnapshot snapshot,
                                            SafetyBoundary resolvedBoundary) {
        return evaluateWithReason(campaign, snapshot, resolvedBoundary).candidates();
    }

    @Override
    public EvaluationResult evaluateWithReason(CampaignEntity campaign,
                                               DataSnapshot snapshot,
                                               SafetyBoundary resolvedBoundary) {
        // Phase gate (Req 5.12): V3 keyword engine only runs at phase V3
        HostingPhase phase = HostingPhase.parse(phaseConfig);
        if (!phase.supports(HostingAdjustmentType.KEYWORD)) {
            log.debug("V3SearchTermEngine skipped campaign {} - phase {} does not support KEYWORD",
                    campaign.getId(), phase);
            return EvaluationResult.skipped(SKIP_PHASE_DISABLED);
        }

        // Need target ACoS for keyword/negative evaluation
        BigDecimal targetAcos = campaign.getTargetAcos();
        if (targetAcos == null || targetAcos.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("V3SearchTermEngine skipped campaign {} - NO_TARGET_ACOS", campaign.getId());
            return EvaluationResult.skipped(SKIP_NO_TARGET_ACOS);
        }

        // Resolve personality and policy
        AiPersonality personality = personalityResolver.resolveForCampaign(campaign);
        PersonalityPolicyEntity policy = personalityPolicyService.resolvePolicy(
                personality.machineValue());
        String ruleVersion = policy.getRuleVersion();

        // Req 5.7: keywordExpansionMode OFF = maxNewKeywordsPerRun == 0
        boolean keywordExpansionEnabled = policy.getMaxNewKeywordsPerRun() != 0;

        // Read search term data from snapshot (Req 31.4)
        List<SearchTermDailyEntity> searchTermRows =
                snapshot.getSearchTermDataByCampaign(campaign.getId());
        if (searchTermRows == null || searchTermRows.isEmpty()) {
            log.debug("V3SearchTermEngine skipped campaign {} - no search term data",
                    campaign.getId());
            return EvaluationResult.skipped(SKIP_NO_DATA);
        }

        // Note: even if keyword expansion is off, we still evaluate negatives

        // Resolve per-day caps (Req 5.5, 5.6)
        int maxKeywordsPerDay = resolvedBoundary.get(SafetyBoundaryLimit.MAX_KEYWORDS_PER_DAY)
                .map(BigDecimal::intValue)
                .orElse(policy.getMaxNewKeywordsPerRun() > 0
                        ? policy.getMaxNewKeywordsPerRun() : DEFAULT_MAX_KEYWORDS_PER_DAY);
        int maxNegativesPerDay = resolvedBoundary.get(SafetyBoundaryLimit.MAX_NEGATIVES_PER_DAY)
                .map(BigDecimal::intValue)
                .orElse(DEFAULT_MAX_NEGATIVES_PER_DAY);

        // Resolve thresholds
        BigDecimal confidenceThreshold = policy.getNegativeConfidenceThreshold() != null
                && policy.getNegativeConfidenceThreshold().compareTo(BigDecimal.ZERO) > 0
                ? policy.getNegativeConfidenceThreshold() : DEFAULT_CONFIDENCE_THRESHOLD;
        int minClicks = policy.getNegativeKeywordMinClicks() > 0
                ? policy.getNegativeKeywordMinClicks() : DEFAULT_MIN_CLICKS;

        // Aggregate search terms across the lookback window
        Map<String, AggregatedSearchTerm> aggregated = aggregateSearchTerms(searchTermRows);

        // Count existing decisions today for per-day cap enforcement
        int keywordsAddedToday = countDecisionsToday(campaign.getId(),
                "keyword_addition");
        int negativesAddedToday = countDecisionsToday(campaign.getId(),
                "negative_keyword_addition");

        List<CandidateDecision> candidates = new ArrayList<>();

        for (Map.Entry<String, AggregatedSearchTerm> entry : aggregated.entrySet()) {
            String searchTerm = entry.getKey();
            AggregatedSearchTerm agg = entry.getValue();

            // Score confidence (Req 5.1)
            BigDecimal confidence = computeConfidence(agg);

            // Req 5.2: Keyword addition
            if (keywordExpansionEnabled
                    && agg.totalOrders > 0
                    && agg.effectiveAcos().compareTo(targetAcos) < 0
                    && confidence.compareTo(confidenceThreshold) > 0) {

                if (keywordsAddedToday < maxKeywordsPerDay) {
                    String matchType = determineMatchType(agg);
                    CandidateDecision candidate = buildKeywordCandidate(
                            campaign, agg, searchTerm, matchType, confidence,
                            targetAcos, personality, ruleVersion);
                    candidates.add(candidate);
                    keywordsAddedToday++;
                }
                // Overflow: queued for next day (Req 5.5)
                continue;
            }

            // Req 5.3: Negative keyword
            if (agg.totalClicks > minClicks
                    && agg.totalOrders == 0
                    && confidence.compareTo(confidenceThreshold) > 0) {

                // Req 5.4: Brand word protection
                BrandProtectionResult brandCheck =
                        brandWordProtectionService.checkNegativeCandidate(
                                campaign.getStoreId(), searchTerm);
                if (brandCheck.rejected()) {
                    log.info("V3SearchTermEngine rejected negative '{}' for campaign {} - {}",
                            searchTerm, campaign.getId(), brandCheck.reason());
                    continue;
                }

                if (negativesAddedToday < maxNegativesPerDay) {
                    CandidateDecision candidate = buildNegativeCandidate(
                            campaign, agg, searchTerm, confidence,
                            targetAcos, personality, ruleVersion);
                    candidates.add(candidate);
                    negativesAddedToday++;
                }
                // Overflow: queued for next day (Req 5.6)
            }
        }

        if (candidates.isEmpty()) {
            return EvaluationResult.skipped(SKIP_NO_DATA);
        }

        log.info("V3SearchTermEngine produced {} candidates for campaign {}",
                candidates.size(), campaign.getId());
        return EvaluationResult.of(candidates);
    }

    // ── Package-private for testability ──────────────────────────────────────

    Map<String, AggregatedSearchTerm> aggregateSearchTerms(List<SearchTermDailyEntity> rows) {
        Map<String, AggregatedSearchTerm> result = new HashMap<>();
        for (SearchTermDailyEntity row : rows) {
            String term = row.getSearchTerm();
            if (term == null || term.isBlank()) {
                continue;
            }
            result.computeIfAbsent(term, k -> new AggregatedSearchTerm()).addRow(row);
        }
        return result;
    }

    BigDecimal computeConfidence(AggregatedSearchTerm agg) {
        // Click component: asymptotic normalization clicks / (clicks + 50)
        BigDecimal clickComponent = BigDecimal.valueOf(agg.totalClicks)
                .divide(BigDecimal.valueOf(agg.totalClicks + 50), MC);
        // Days component: daysOfData / (daysOfData + 7)
        BigDecimal daysComponent = BigDecimal.valueOf(agg.daysOfData)
                .divide(BigDecimal.valueOf(agg.daysOfData + 7), MC);
        // Order significance boost
        BigDecimal orderBoost = agg.totalOrders > 0
                ? new BigDecimal("0.15") : BigDecimal.ZERO;
        // Weighted: 0.45 * clicks + 0.40 * days + orderBoost
        BigDecimal confidence = new BigDecimal("0.45").multiply(clickComponent, MC)
                .add(new BigDecimal("0.40").multiply(daysComponent, MC), MC)
                .add(orderBoost, MC);
        // Clamp [0, 1]
        if (confidence.compareTo(BigDecimal.ONE) > 0) confidence = BigDecimal.ONE;
        if (confidence.compareTo(BigDecimal.ZERO) < 0) confidence = BigDecimal.ZERO;
        return confidence.setScale(6, RoundingMode.HALF_UP);
    }

    String determineMatchType(AggregatedSearchTerm agg) {
        if (agg.totalOrders >= 3 && agg.totalClicks >= 50) return "exact";
        if (agg.totalOrders >= 1 && agg.totalClicks >= 30) return "phrase";
        return "broad";
    }

    int countDecisionsToday(UUID campaignId, String decisionType) {
        LambdaQueryWrapper<AiDecisionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiDecisionEntity::getCampaignId, campaignId)
                .eq(AiDecisionEntity::getDecisionType, decisionType)
                .ge(AiDecisionEntity::getCreatedAt, LocalDate.now().atStartOfDay());
        return Math.toIntExact(aiDecisionMapper.selectCount(wrapper));
    }

    private CandidateDecision buildKeywordCandidate(CampaignEntity campaign,
                                                    AggregatedSearchTerm agg,
                                                    String searchTerm,
                                                    String matchType,
                                                    BigDecimal confidence,
                                                    BigDecimal targetAcos,
                                                    AiPersonality personality,
                                                    String ruleVersion) {
        RiskScoreResult riskResult = riskScoreCalculator.calculate(new RiskScoreInput(
                new BigDecimal("0.5"), confidence, BigDecimal.ZERO, agg.totalSpend));
        String snapshot = buildKeywordSnapshot(campaign, agg, searchTerm, matchType,
                confidence, targetAcos, personality, ruleVersion, riskResult);
        return new CandidateDecision(
                UUID.randomUUID(),
                campaign.getStoreId(),
                campaign.getId(),
                ENTITY_TYPE_KEYWORD,
                campaign.getId(),
                FIELD_KEYWORD,
                HostingAdjustmentType.KEYWORD,
                CHANGE_TYPE_KEYWORD,
                CandidateDecision.ENGINE_V3_KEYWORD,
                null, null,
                riskResult.score(),
                CandidateDecisionContext.DEFAULT_RISK_THRESHOLD,
                confidence,
                snapshot);
    }

    private CandidateDecision buildNegativeCandidate(CampaignEntity campaign,
                                                     AggregatedSearchTerm agg,
                                                     String searchTerm,
                                                     BigDecimal confidence,
                                                     BigDecimal targetAcos,
                                                     AiPersonality personality,
                                                     String ruleVersion) {
        RiskScoreResult riskResult = riskScoreCalculator.calculate(new RiskScoreInput(
                new BigDecimal("0.6"), confidence, BigDecimal.ZERO, agg.totalSpend));
        String snapshot = buildNegativeSnapshot(campaign, agg, searchTerm,
                confidence, targetAcos, personality, ruleVersion, riskResult);
        return new CandidateDecision(
                UUID.randomUUID(),
                campaign.getStoreId(),
                campaign.getId(),
                ENTITY_TYPE_KEYWORD,
                campaign.getId(),
                FIELD_NEGATIVE,
                HostingAdjustmentType.NEGATIVE,
                CHANGE_TYPE_NEGATIVE,
                CandidateDecision.ENGINE_V3_KEYWORD,
                null, null,
                riskResult.score(),
                CandidateDecisionContext.DEFAULT_RISK_THRESHOLD,
                confidence,
                snapshot);
    }

    private String buildKeywordSnapshot(CampaignEntity campaign, AggregatedSearchTerm agg,
                                        String searchTerm, String matchType,
                                        BigDecimal confidence, BigDecimal targetAcos,
                                        AiPersonality personality, String ruleVersion,
                                        RiskScoreResult riskResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"engine\":\"V3_KEYWORD\"");
        sb.append(",\"type\":\"keyword_addition\"");
        sb.append(",\"campaignId\":\"").append(campaign.getId()).append("\"");
        sb.append(",\"storeId\":\"").append(campaign.getStoreId()).append("\"");
        sb.append(",\"personality\":\"").append(personality.machineValue()).append("\"");
        sb.append(",\"ruleVersion\":\"").append(ruleVersion).append("\"");
        sb.append(",\"searchTerm\":\"").append(escapeJson(searchTerm)).append("\"");
        sb.append(",\"matchType\":\"").append(matchType).append("\"");
        sb.append(",\"confidence\":").append(confidence.toPlainString());
        sb.append(",\"targetAcos\":").append(targetAcos.toPlainString());
        sb.append(",\"evidence\":{");
        sb.append("\"clicks\":").append(agg.totalClicks);
        sb.append(",\"orders\":").append(agg.totalOrders);
        sb.append(",\"spend\":").append(agg.totalSpend.toPlainString());
        sb.append(",\"sales\":").append(agg.totalSales.toPlainString());
        sb.append(",\"acos\":").append(agg.effectiveAcos().toPlainString());
        sb.append(",\"daysOfData\":").append(agg.daysOfData);
        sb.append("}");
        sb.append(",\"riskScore\":").append(riskResult.score().toPlainString());
        sb.append(",\"formulaVersion\":\"").append(riskResult.formulaVersion()).append("\"");
        sb.append(",\"reversible\":false");
        sb.append("}");
        return sb.toString();
    }

    private String buildNegativeSnapshot(CampaignEntity campaign, AggregatedSearchTerm agg,
                                         String searchTerm, BigDecimal confidence,
                                         BigDecimal targetAcos, AiPersonality personality,
                                         String ruleVersion, RiskScoreResult riskResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"engine\":\"V3_KEYWORD\"");
        sb.append(",\"type\":\"negative_keyword_addition\"");
        sb.append(",\"campaignId\":\"").append(campaign.getId()).append("\"");
        sb.append(",\"storeId\":\"").append(campaign.getStoreId()).append("\"");
        sb.append(",\"personality\":\"").append(personality.machineValue()).append("\"");
        sb.append(",\"ruleVersion\":\"").append(ruleVersion).append("\"");
        sb.append(",\"searchTerm\":\"").append(escapeJson(searchTerm)).append("\"");
        sb.append(",\"matchType\":\"exact\"");
        sb.append(",\"confidence\":").append(confidence.toPlainString());
        sb.append(",\"targetAcos\":").append(targetAcos.toPlainString());
        sb.append(",\"evidence\":{");
        sb.append("\"clicks\":").append(agg.totalClicks);
        sb.append(",\"orders\":").append(agg.totalOrders);
        sb.append(",\"spend\":").append(agg.totalSpend.toPlainString());
        sb.append(",\"sales\":").append(agg.totalSales.toPlainString());
        sb.append(",\"acos\":").append(agg.effectiveAcos().toPlainString());
        sb.append(",\"daysOfData\":").append(agg.daysOfData);
        sb.append("}");
        sb.append(",\"riskScore\":").append(riskResult.score().toPlainString());
        sb.append(",\"formulaVersion\":\"").append(riskResult.formulaVersion()).append("\"");
        sb.append(",\"reversible\":false");
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ── Inner class: aggregated search term ──────────────────────────────────

    static class AggregatedSearchTerm {
        int totalClicks = 0;
        int totalOrders = 0;
        long totalImpressions = 0;
        BigDecimal totalSpend = BigDecimal.ZERO;
        BigDecimal totalSales = BigDecimal.ZERO;
        int daysOfData = 0;
        UUID adGroupId;

        void addRow(SearchTermDailyEntity row) {
            totalClicks += row.getClicks() != null ? row.getClicks() : 0;
            totalOrders += row.getOrders() != null ? row.getOrders() : 0;
            totalImpressions += row.getImpressions() != null ? row.getImpressions() : 0;
            if (row.getSpend() != null) {
                totalSpend = totalSpend.add(row.getSpend());
            }
            if (row.getSales() != null) {
                totalSales = totalSales.add(row.getSales());
            }
            daysOfData++;
            if (adGroupId == null) {
                adGroupId = row.getAdGroupId();
            }
        }

        BigDecimal effectiveAcos() {
            if (totalSales.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }
            return totalSpend.divide(totalSales, MC);
        }
    }
}
