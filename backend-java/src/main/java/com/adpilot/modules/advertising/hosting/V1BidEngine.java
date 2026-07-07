package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.operation.InFlightConflictLock;
import com.adpilot.modules.advertising.operation.OperationMachineValues;
import com.adpilot.modules.advertising.operation.OperationSource;
import com.adpilot.modules.advertising.service.AiPersonality;
import com.adpilot.modules.advertising.service.HostingAdjustmentType;
import com.adpilot.modules.advertising.service.HostingBidOptimizer;
import com.adpilot.modules.advertising.service.HostingPhase;
import com.adpilot.modules.advertising.service.PersonalityPolicyService;
import com.adpilot.modules.advertising.service.PersonalityResolver;
import com.adpilot.modules.advertising.support.RiskScoreCalculator;
import com.adpilot.modules.advertising.support.RiskScoreInput;
import com.adpilot.modules.advertising.support.RiskScoreResult;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V1 Bid Engine — keyword-level ACoS-based bid optimization (Requirement 36).
 *
 * <p>Replaces the legacy campaign-aggregated ACoS approach in {@code AiHostingOptimizer}
 * with true keyword-level ACoS computation. Each keyword's bid proposal is based on
 * its own performance data, never falling back to a campaign-wide aggregate.
 *
 * <p>This engine:
 * <ul>
 *   <li>Reads the immutable {@link DataSnapshot} (Req 36.3, 23.1)</li>
 *   <li>Computes per-keyword ACoS from keyword-level performance rows (Req 36.1)</li>
 *   <li>Skips keywords with insufficient data without fallback (Req 36.2)</li>
 *   <li>Honors {@code adjustmentCooldownHours} at keyword grain (Req 36.5)</li>
 *   <li>Checks keyword-level in-flight conflict lock (Req 36.5)</li>
 *   <li>Clamps proposed bids within minBid/maxBid/maxCpc/maxBidAdjustmentRatio (Req 36.6)</li>
 *   <li>Emits {@link CandidateDecision} objects to the {@link OptimizationCoordinator} (Req 36.3)</li>
 * </ul>
 *
 * <p>The engine only runs when the hosting phase is V1 or higher (i.e., when the phase
 * supports {@link HostingAdjustmentType#BID}).
 *
 * <p>Validates: Requirements 36.1, 36.2, 36.3, 36.4, 36.5, 36.6.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class V1BidEngine {

    private static final String ENTITY_TYPE_KEYWORD = "keyword";
    private static final String FIELD_BID = "bid";
    private static final String CHANGE_TYPE_BID = "bid";
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    /** Sentinel ACoS when there is spend but zero sales (infinite ACoS). */
    private static final BigDecimal NO_SALES_ACOS = new BigDecimal("999.99");

    private final KeywordMapper keywordMapper;
    private final OperationMapper operationMapper;
    private final InFlightConflictLock inFlightConflictLock;
    private final PersonalityResolver personalityResolver;
    private final PersonalityPolicyService personalityPolicyService;
    private final RiskScoreCalculator riskScoreCalculator;
    private final ObjectMapper objectMapper;
    private final LearningPeriodService learningPeriodService;

    /** Minimum clicks a keyword must have to be eligible for optimization. */
    @Value("${adpilot.hosting.v1.min-clicks:10}")
    private int minClicksThreshold;

    /** Minimum impressions a keyword must have to be eligible for optimization. */
    @Value("${adpilot.hosting.v1.min-impressions:100}")
    private int minImpressionsThreshold;

    /** Default minimum bid floor when boundary is not defined. */
    @Value("${adpilot.hosting.min-bid:0.02}")
    private BigDecimal defaultMinBid;

    /** Default maximum bid ceiling when boundary is not defined. */
    @Value("${adpilot.hosting.max-bid:100.00}")
    private BigDecimal defaultMaxBid;

    /**
     * Produce bid adjustment candidates for all eligible keywords in the given campaign.
     *
     * <p>This method reads keyword performance data from the immutable snapshot,
     * computes per-keyword ACoS, and produces candidates that the coordinator will
     * de-conflict, prioritize, and route.
     *
     * @param campaign         the campaign being optimized
     * @param snapshot         the immutable data snapshot for this optimization run
     * @param resolvedBoundary the resolved safety boundary for this campaign
     * @param phase            the current hosting phase (must support BID)
     * @return list of candidate decisions for eligible keywords; empty if phase doesn't support BID
     */
    public List<CandidateDecision> produceCandidates(CampaignEntity campaign,
                                                     DataSnapshot snapshot,
                                                     SafetyBoundary resolvedBoundary,
                                                     HostingPhase phase) {
        if (campaign == null || campaign.getTargetAcos() == null) {
            return Collections.emptyList();
        }

        // Phase gate: only run when phase supports BID (V1 or higher)
        if (!phase.supports(HostingAdjustmentType.BID)) {
            log.debug("V1BidEngine skipped: phase {} does not support BID", phase);
            return Collections.emptyList();
        }

        BigDecimal targetAcos = campaign.getTargetAcos();
        UUID campaignId = campaign.getId();
        UUID storeId = campaign.getStoreId();

        // Resolve personality and policy for this campaign
        AiPersonality personality = personalityResolver.resolveForCampaign(campaign);
        PersonalityPolicyEntity policy = personalityPolicyService.resolvePolicy(personality.machineValue());
        int cooldownHours = policy.getAdjustmentCooldownHours() != null
                ? policy.getAdjustmentCooldownHours() : 0;
        int lookbackDays = policy.getLookbackDays() != null ? policy.getLookbackDays() : 30;

        // Load enabled keywords for this campaign
        List<KeywordEntity> keywords = keywordMapper.selectList(
                new LambdaQueryWrapper<KeywordEntity>()
                        .eq(KeywordEntity::getCampaignId, campaignId)
                        .eq(KeywordEntity::getStatus, "enabled")
                        .isNotNull(KeywordEntity::getBid));

        List<CandidateDecision> candidates = new ArrayList<>();

        for (KeywordEntity keyword : keywords) {
            try {
                CandidateDecision candidate = processKeyword(
                        keyword, campaign, snapshot, resolvedBoundary,
                        targetAcos, personality, policy, cooldownHours);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            } catch (Exception e) {
                // Isolate per-keyword failures so one never aborts the entire campaign
                log.warn("V1BidEngine: error processing keyword {} in campaign {}: {}",
                        keyword.getId(), campaignId, e.getMessage());
            }
        }

        log.debug("V1BidEngine produced {} candidates for campaign {} ({} keywords evaluated)",
                candidates.size(), campaignId, keywords.size());
        return candidates;
    }

    /**
     * Process a single keyword: compute keyword-level ACoS, apply cooldown/conflict checks,
     * compute the bid adjustment, clamp to safety boundaries, and produce a candidate.
     *
     * @return a CandidateDecision if a bid change is warranted; null if skipped
     */
    private CandidateDecision processKeyword(KeywordEntity keyword,
                                             CampaignEntity campaign,
                                             DataSnapshot snapshot,
                                             SafetyBoundary resolvedBoundary,
                                             BigDecimal targetAcos,
                                             AiPersonality personality,
                                             PersonalityPolicyEntity policy,
                                             int cooldownHours) {
        UUID keywordId = keyword.getId();
        UUID campaignId = campaign.getId();
        BigDecimal currentBid = keyword.getBid();

        if (currentBid == null || currentBid.signum() <= 0) {
            return null;
        }

        // --- 1. Keyword-level in-flight conflict lock (Req 36.5) ---
        if (inFlightConflictLock.hasInFlightOperation(ENTITY_TYPE_KEYWORD, keywordId, FIELD_BID)) {
            log.debug("V1BidEngine: skipping keyword {} — in-flight bid operation exists", keywordId);
            return null;
        }

        // --- 2. Adjustment cooldown at keyword grain (Req 36.5) ---
        if (cooldownHours > 0 && isInCooldown(keywordId, cooldownHours)) {
            log.debug("V1BidEngine: skipping keyword {} — within cooldown period ({} hours)",
                    keywordId, cooldownHours);
            return null;
        }

        // --- 3. Compute keyword-level ACoS from snapshot data (Req 36.1) ---
        List<PerformanceDailyEntity> keywordPerformance =
                snapshot.getPerformanceDataByKeyword(campaignId, keywordId);

        // --- 4. Check sufficient data (Req 36.2) ---
        if (!hasSufficientData(keywordPerformance)) {
            log.debug("V1BidEngine: skipping keyword {} — insufficient data (rows={}, " +
                            "minClicks={}, minImpressions={})",
                    keywordId, keywordPerformance.size(), minClicksThreshold, minImpressionsThreshold);
            return null;
        }

        BigDecimal keywordAcos = computeKeywordAcos(keywordPerformance);
        if (keywordAcos == null) {
            // No spend and no sales — nothing to act on
            return null;
        }

        // --- 5. Compute direction and magnitude ---
        int direction = keywordAcos.compareTo(targetAcos);
        if (direction == 0) {
            return null; // At target — no change needed
        }

        // Personality-allowed magnitude (ratio): decrease when ACoS above target, increase otherwise
        BigDecimal allowedRatio = direction > 0
                ? nvl(policy.getMaxBidDecreaseRatio())
                : nvl(policy.getMaxBidIncreaseRatio());

        if (allowedRatio.signum() <= 0) {
            return null; // Policy disallows changes in this direction
        }

        BigDecimal maxChangePct = allowedRatio.multiply(HUNDRED);

        // --- 6. Compute the adjusted bid using the existing pure clamp logic ---
        HostingBidOptimizer.SafeBidAdjustment adjustment = HostingBidOptimizer.adjustBidWithinBoundary(
                currentBid, keywordAcos, targetAcos, maxChangePct,
                resolvedBoundary, defaultMinBid, defaultMaxBid);
        BigDecimal adjustedBid = adjustment.getAdjustedBid().setScale(4, RoundingMode.HALF_UP);

        // --- 7. Apply maxBidAdjustmentRatio clamp (Req 36.6) ---
        adjustedBid = clampByMaxBidAdjustmentRatio(currentBid, adjustedBid, resolvedBoundary);

        // --- 8. Apply maxCpc clamp (Req 36.6) ---
        adjustedBid = clampByMaxCpc(adjustedBid, resolvedBoundary);

        // --- 8a. Apply learning-period 10% cap (Req 19.2) ---
        if (learningPeriodService.isInLearningPeriod(campaignId)) {
            adjustedBid = learningPeriodService.clampBidForLearningPeriod(currentBid, adjustedBid);
        }

        if (adjustedBid.compareTo(currentBid) == 0) {
            return null; // No effective change after clamping
        }

        // --- 9. Compute risk score ---
        BigDecimal changeMagnitude = adjustedBid.subtract(currentBid).abs()
                .divide(currentBid, 6, RoundingMode.HALF_UP);
        BigDecimal dataConfidence = computeDataConfidence(keywordPerformance);
        BigDecimal historicalVolatility = computeHistoricalVolatility(keywordPerformance);
        BigDecimal absoluteDollarImpact = adjustedBid.subtract(currentBid).abs();

        RiskScoreInput riskInput = new RiskScoreInput(
                changeMagnitude, dataConfidence, historicalVolatility, absoluteDollarImpact);
        RiskScoreResult riskResult = riskScoreCalculator.calculate(riskInput);

        // --- 10. Build decision snapshot ---
        String snapshotJson = buildDecisionSnapshot(
                snapshot, keywordAcos, targetAcos, currentBid, adjustedBid,
                personality, policy, resolvedBoundary, riskResult, dataConfidence);

        // --- 11. Emit CandidateDecision ---
        return new CandidateDecision(
                UUID.randomUUID(),
                campaign.getStoreId(),
                campaignId,
                ENTITY_TYPE_KEYWORD,
                keywordId,
                FIELD_BID,
                HostingAdjustmentType.BID,
                CHANGE_TYPE_BID,
                CandidateDecision.ENGINE_V1_BID,
                currentBid,
                adjustedBid,
                riskResult.score(),
                null, // riskThreshold resolved later by coordinator
                dataConfidence,
                snapshotJson
        );
    }

    /**
     * Check if a keyword is within the adjustment cooldown period (Req 36.5).
     *
     * <p>Queries the operations table for the most recent ai_hosting bid operation
     * on this keyword and checks if it was created within the cooldown window.
     */
    boolean isInCooldown(UUID keywordId, int cooldownHours) {
        LocalDateTime cooldownCutoff = LocalDateTime.now().minusHours(cooldownHours);

        LambdaQueryWrapper<OperationEntity> wrapper = new LambdaQueryWrapper<OperationEntity>()
                .eq(OperationEntity::getEntityType, ENTITY_TYPE_KEYWORD)
                .eq(OperationEntity::getEntityId, keywordId)
                .eq(OperationEntity::getField, FIELD_BID)
                .eq(OperationEntity::getOperationSource,
                        OperationMachineValues.toValue(OperationSource.AI_HOSTING))
                .ge(OperationEntity::getCreatedAt, cooldownCutoff)
                .orderByDesc(OperationEntity::getCreatedAt)
                .last("LIMIT 1");

        return operationMapper.selectOne(wrapper) != null;
    }

    /**
     * Check if the keyword has sufficient performance data for optimization (Req 36.2).
     *
     * <p>A keyword must have accumulated at least {@code minClicksThreshold} clicks
     * and {@code minImpressionsThreshold} impressions across the lookback window.
     */
    boolean hasSufficientData(List<PerformanceDailyEntity> keywordPerformance) {
        if (keywordPerformance == null || keywordPerformance.isEmpty()) {
            return false;
        }

        long totalClicks = 0;
        long totalImpressions = 0;
        for (PerformanceDailyEntity row : keywordPerformance) {
            if (row.getClicks() != null) {
                totalClicks += row.getClicks();
            }
            if (row.getImpressions() != null) {
                totalImpressions += row.getImpressions();
            }
        }

        return totalClicks >= minClicksThreshold && totalImpressions >= minImpressionsThreshold;
    }

    /**
     * Compute keyword-level ACoS from performance rows (Req 36.1).
     *
     * <p>Aggregates spend and sales across all rows for this keyword over the lookback window.
     * Returns null when there is no signal (no spend and no sales).
     * Returns a large sentinel when there was spend but no sales.
     */
    BigDecimal computeKeywordAcos(List<PerformanceDailyEntity> keywordPerformance) {
        BigDecimal totalSpend = BigDecimal.ZERO;
        BigDecimal totalSales = BigDecimal.ZERO;

        for (PerformanceDailyEntity row : keywordPerformance) {
            if (row.getSpend() != null) {
                totalSpend = totalSpend.add(row.getSpend());
            }
            if (row.getSales() != null) {
                totalSales = totalSales.add(row.getSales());
            }
        }

        if (totalSpend.signum() <= 0 && totalSales.signum() <= 0) {
            return null; // No signal
        }
        if (totalSales.signum() <= 0) {
            return NO_SALES_ACOS; // Spend but no sales
        }
        // ACoS = spend / sales (as a ratio, not percentage)
        return totalSpend.divide(totalSales, 6, RoundingMode.HALF_UP);
    }

    /**
     * Clamp the adjusted bid so the change magnitude does not exceed
     * maxBidAdjustmentRatio (Req 36.6).
     *
     * <p>maxBidAdjustmentRatio represents the maximum allowed ratio of change
     * relative to the current bid (e.g., 0.5 means at most 50% change up or down).
     */
    BigDecimal clampByMaxBidAdjustmentRatio(BigDecimal currentBid,
                                            BigDecimal adjustedBid,
                                            SafetyBoundary boundary) {
        BigDecimal maxRatio = boundary.get(SafetyBoundaryLimit.MAX_BID_ADJUSTMENT_RATIO).orElse(null);
        if (maxRatio == null || maxRatio.signum() <= 0) {
            return adjustedBid; // No ratio constraint
        }

        BigDecimal maxChange = currentBid.multiply(maxRatio, MC);
        BigDecimal change = adjustedBid.subtract(currentBid);

        if (change.abs().compareTo(maxChange) > 0) {
            // Clamp to the maximum allowed change
            if (change.signum() > 0) {
                return currentBid.add(maxChange).setScale(4, RoundingMode.HALF_UP);
            } else {
                return currentBid.subtract(maxChange).setScale(4, RoundingMode.HALF_UP);
            }
        }
        return adjustedBid;
    }

    /**
     * Clamp the adjusted bid so it does not exceed maxCpc (Req 36.6).
     */
    BigDecimal clampByMaxCpc(BigDecimal adjustedBid, SafetyBoundary boundary) {
        BigDecimal maxCpc = boundary.get(SafetyBoundaryLimit.MAX_CPC).orElse(null);
        if (maxCpc == null) {
            return adjustedBid;
        }
        return adjustedBid.compareTo(maxCpc) > 0 ? maxCpc : adjustedBid;
    }

    /**
     * Compute data confidence based on volume and coverage of keyword performance data.
     * Returns a value in [0.0, 1.0] where higher means more confident.
     */
    BigDecimal computeDataConfidence(List<PerformanceDailyEntity> keywordPerformance) {
        if (keywordPerformance == null || keywordPerformance.isEmpty()) {
            return BigDecimal.ZERO;
        }

        long totalClicks = 0;
        int daysWithData = keywordPerformance.size();
        for (PerformanceDailyEntity row : keywordPerformance) {
            if (row.getClicks() != null) {
                totalClicks += row.getClicks();
            }
        }

        // Confidence increases with more clicks and more days of data
        // Asymptotic: clicks / (clicks + 50) gives diminishing returns
        BigDecimal clickConfidence = BigDecimal.valueOf(totalClicks)
                .divide(BigDecimal.valueOf(totalClicks + 50), 6, RoundingMode.HALF_UP);
        // Days factor: days / (days + 7) — 14 days gives ~0.67
        BigDecimal daysFactor = BigDecimal.valueOf(daysWithData)
                .divide(BigDecimal.valueOf(daysWithData + 7), 6, RoundingMode.HALF_UP);

        // Blend: 60% click confidence, 40% days factor
        BigDecimal confidence = clickConfidence.multiply(new BigDecimal("0.6"), MC)
                .add(daysFactor.multiply(new BigDecimal("0.4"), MC), MC);

        // Clamp to [0.0, 1.0]
        if (confidence.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
        if (confidence.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO;
        return confidence.setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * Compute historical volatility of keyword ACoS over the lookback window.
     * Returns a normalized value in [0.0, 1.0].
     */
    BigDecimal computeHistoricalVolatility(List<PerformanceDailyEntity> keywordPerformance) {
        if (keywordPerformance == null || keywordPerformance.size() < 2) {
            return new BigDecimal("0.5"); // Unknown volatility defaults to mid-range
        }

        // Compute daily ACoS values where possible
        List<BigDecimal> dailyAcos = new ArrayList<>();
        for (PerformanceDailyEntity row : keywordPerformance) {
            if (row.getSpend() != null && row.getSales() != null
                    && row.getSales().signum() > 0 && row.getSpend().signum() > 0) {
                dailyAcos.add(row.getSpend().divide(row.getSales(), 6, RoundingMode.HALF_UP));
            }
        }

        if (dailyAcos.size() < 2) {
            return new BigDecimal("0.5");
        }

        // Compute mean
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal acos : dailyAcos) {
            sum = sum.add(acos);
        }
        BigDecimal mean = sum.divide(BigDecimal.valueOf(dailyAcos.size()), 6, RoundingMode.HALF_UP);

        if (mean.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        // Compute coefficient of variation (std dev / mean), then normalize to [0, 1)
        BigDecimal variance = BigDecimal.ZERO;
        for (BigDecimal acos : dailyAcos) {
            BigDecimal diff = acos.subtract(mean);
            variance = variance.add(diff.multiply(diff, MC), MC);
        }
        variance = variance.divide(BigDecimal.valueOf(dailyAcos.size()), 6, RoundingMode.HALF_UP);

        // stdDev / mean gives CoV; normalize via x/(1+x) to [0,1)
        double stdDev = Math.sqrt(variance.doubleValue());
        double cov = stdDev / mean.doubleValue();
        double normalized = cov / (1.0 + cov);

        BigDecimal result = BigDecimal.valueOf(normalized).setScale(6, RoundingMode.HALF_UP);
        if (result.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
        if (result.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO;
        return result;
    }

    /**
     * Build the immutable DecisionSnapshot JSON for this bid candidate (Req 36.4, 34).
     */
    private String buildDecisionSnapshot(DataSnapshot snapshot,
                                         BigDecimal keywordAcos,
                                         BigDecimal targetAcos,
                                         BigDecimal currentBid,
                                         BigDecimal proposedBid,
                                         AiPersonality personality,
                                         PersonalityPolicyEntity policy,
                                         SafetyBoundary resolvedBoundary,
                                         RiskScoreResult riskResult,
                                         BigDecimal dataConfidence) {
        Map<String, BigDecimal> metricInputs = new HashMap<>();
        metricInputs.put("keyword_acos", keywordAcos);
        metricInputs.put("target_acos", targetAcos);
        metricInputs.put("current_bid", currentBid);
        metricInputs.put("proposed_bid", proposedBid);
        metricInputs.put("data_confidence", dataConfidence);

        List<DecisionSnapshot.EffectiveBoundary> effectiveBoundaries = new ArrayList<>();
        resolvedBoundary.getMinBid().ifPresent(v -> effectiveBoundaries.add(
                new DecisionSnapshot.EffectiveBoundary("MIN_BID", v.toPlainString(),
                        resolvedBoundary.sourceOf(SafetyBoundaryLimit.MIN_BID).map(Enum::name).orElse("SYSTEM"))));
        resolvedBoundary.getMaxBid().ifPresent(v -> effectiveBoundaries.add(
                new DecisionSnapshot.EffectiveBoundary("MAX_BID", v.toPlainString(),
                        resolvedBoundary.sourceOf(SafetyBoundaryLimit.MAX_BID).map(Enum::name).orElse("SYSTEM"))));
        resolvedBoundary.getMaxCpc().ifPresent(v -> effectiveBoundaries.add(
                new DecisionSnapshot.EffectiveBoundary("MAX_CPC", v.toPlainString(),
                        resolvedBoundary.sourceOf(SafetyBoundaryLimit.MAX_CPC).map(Enum::name).orElse("SYSTEM"))));
        resolvedBoundary.get(SafetyBoundaryLimit.MAX_BID_ADJUSTMENT_RATIO).ifPresent(v -> effectiveBoundaries.add(
                new DecisionSnapshot.EffectiveBoundary("MAX_BID_ADJUSTMENT_RATIO", v.toPlainString(),
                        resolvedBoundary.sourceOf(SafetyBoundaryLimit.MAX_BID_ADJUSTMENT_RATIO).map(Enum::name).orElse("SYSTEM"))));

        DecisionSnapshot decisionSnapshot = DecisionSnapshot.builder()
                .dataCutoff(LocalDateTime.now())
                .lookbackDays(snapshot.getLookbackDays())
                .metricInputs(metricInputs)
                .dqGateResult(new DecisionSnapshot.DqGateResult(true, null))
                .personality(personality.machineValue())
                .inheritanceChain(List.of(personality.machineValue()))
                .effectiveBoundaries(effectiveBoundaries)
                .riskFormulaVersion(riskResult.formulaVersion())
                .riskScore(riskResult.score())
                .ruleVersion(policy.getRuleVersion())
                .currency(snapshot.getCurrency())
                .marketplaceTimezone(snapshot.getMarketplaceTimezone())
                .executionMode(null) // resolved by the routing pipeline
                .killSwitchActive(false)
                .shadowModeActive(false)
                .build();

        try {
            return objectMapper.writeValueAsString(decisionSnapshot);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize DecisionSnapshot for keyword bid candidate", e);
            return "{}";
        }
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
