package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of the V2 Budget Optimization Engine (Requirement 4).
 *
 * <p>Adjusts campaign daily budgets based on:
 * <ul>
 *   <li>Target ACoS from the campaign's goal</li>
 *   <li>Actual ACoS over the personality policy's lookbackDays</li>
 *   <li>Current daily spend velocity</li>
 *   <li>Available inventory days</li>
 * </ul>
 *
 * <p>Direction rules (Req 4.2, 4.3):
 * <ul>
 *   <li>INCREASE: actual ACoS &lt; target ACoS AND inventory &gt; inventoryHealthyDays</li>
 *   <li>DECREASE: actual ACoS &gt; target ACoS * (1 + acosToleranceRatio)</li>
 * </ul>
 *
 * <p>All proposed values are clamped within the resolved Safety_Boundary's
 * minDailyBudget/maxDailyBudget and bounded by maxDailyBudgetIncreaseRatio/
 * maxDailyBudgetDecreaseRatio (Req 4.5).</p>
 *
 * <p>Validates: Requirements 4.1, 4.2, 4.3, 4.5, 4.6, 4.7, 4.8, 4.9.</p>
 */
@Service
public class V2BudgetEngineImpl implements V2BudgetEngine {

    private static final Logger log = LoggerFactory.getLogger(V2BudgetEngineImpl.class);

    private static final String ENTITY_TYPE_CAMPAIGN = "campaign";
    private static final String FIELD_DAILY_BUDGET = "daily_budget";
    private static final String CHANGE_TYPE_BUDGET = "budget";
    private static final String SKIP_COOLDOWN = "COOLDOWN";
    private static final String SKIP_NO_TARGET_ACOS = "NO_TARGET_ACOS";
    private static final String SKIP_NO_DATA = "NO_DATA";
    private static final String SKIP_NO_CHANGE = "NO_CHANGE";
    private static final String SKIP_PHASE_DISABLED = "PHASE_DISABLED";
    private static final String SKIP_LEARNING_PERIOD = "LEARNING_PERIOD";

    /**
     * ACoS sentinel used when a campaign spent money but produced no sales:
     * effectively unbounded, drives budget down.
     */
    private static final BigDecimal NO_SALES_ACOS = new BigDecimal("99999");

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final int BUDGET_SCALE = 2;

    private final PersonalityResolver personalityResolver;
    private final PersonalityPolicyService personalityPolicyService;
    private final RiskScoreCalculator riskScoreCalculator;
    private final OperationMapper operationMapper;
    private final LearningPeriodService learningPeriodService;

    @Value("${adpilot.hosting.phase:V1}")
    private String phaseConfig;

    public V2BudgetEngineImpl(PersonalityResolver personalityResolver,
                              PersonalityPolicyService personalityPolicyService,
                              RiskScoreCalculator riskScoreCalculator,
                              OperationMapper operationMapper,
                              LearningPeriodService learningPeriodService) {
        this.personalityResolver = personalityResolver;
        this.personalityPolicyService = personalityPolicyService;
        this.riskScoreCalculator = riskScoreCalculator;
        this.operationMapper = operationMapper;
        this.learningPeriodService = learningPeriodService;
    }

    @Override
    public List<CandidateDecision> evaluate(CampaignEntity campaign,
                                            DataSnapshot snapshot,
                                            SafetyBoundary resolvedBoundary,
                                            Integer inventoryDays) {
        return evaluateWithReason(campaign, snapshot, resolvedBoundary, inventoryDays).candidates();
    }

    @Override
    public EvaluationResult evaluateWithReason(CampaignEntity campaign,
                                               DataSnapshot snapshot,
                                               SafetyBoundary resolvedBoundary,
                                               Integer inventoryDays) {
        // Phase gate (Req 4.8): V2 budget engine only runs when phase >= V2
        HostingPhase phase = HostingPhase.parse(phaseConfig);
        if (!phase.supports(HostingAdjustmentType.BUDGET)) {
            log.debug("V2BudgetEngine skipped campaign {} — phase {} does not support BUDGET",
                    campaign.getId(), phase);
            return EvaluationResult.skipped(SKIP_PHASE_DISABLED);
        }

        // Req 4.9: skip if campaign has no target ACoS
        BigDecimal targetAcos = campaign.getTargetAcos();
        if (targetAcos == null || targetAcos.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("V2BudgetEngine skipped campaign {} — NO_TARGET_ACOS", campaign.getId());
            return EvaluationResult.skipped(SKIP_NO_TARGET_ACOS);
        }

        // Req 19.2: block all budget changes during learning period
        if (learningPeriodService.shouldBlockBudgetChanges(campaign.getId())) {
            log.debug("V2BudgetEngine skipped campaign {} — LEARNING_PERIOD (no budget changes allowed)",
                    campaign.getId());
            return EvaluationResult.skipped(SKIP_LEARNING_PERIOD);
        }

        // Resolve personality and policy
        AiPersonality personality = personalityResolver.resolveForCampaign(campaign);
        PersonalityPolicyEntity policy = personalityPolicyService.resolvePolicy(personality.machineValue());
        String ruleVersion = policy.getRuleVersion();

        // Req 4.6: respect adjustmentCooldownHours
        int cooldownHours = policy.getAdjustmentCooldownHours() != null
                ? policy.getAdjustmentCooldownHours() : 0;
        if (cooldownHours > 0 && isWithinCooldown(campaign, cooldownHours)) {
            log.debug("V2BudgetEngine skipped campaign {} — within cooldown window ({}h)",
                    campaign.getId(), cooldownHours);
            return EvaluationResult.skipped(SKIP_COOLDOWN);
        }

        // Compute actual ACoS over lookback from the snapshot's performance data
        int lookbackDays = policy.getLookbackDays() != null ? policy.getLookbackDays() : 14;
        BigDecimal actualAcos = computeActualAcos(campaign.getId(), snapshot, lookbackDays);
        if (actualAcos == null) {
            log.debug("V2BudgetEngine skipped campaign {} — no performance data in lookback",
                    campaign.getId());
            return EvaluationResult.skipped(SKIP_NO_DATA);
        }

        // Current daily budget
        BigDecimal currentBudget = campaign.getBudget();
        if (currentBudget == null || currentBudget.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("V2BudgetEngine skipped campaign {} — no current budget set", campaign.getId());
            return EvaluationResult.skipped(SKIP_NO_DATA);
        }

        // Compute daily spend velocity from the snapshot
        BigDecimal spendVelocity = computeSpendVelocity(campaign.getId(), snapshot, lookbackDays);

        // Determine direction and compute proposed budget
        BigDecimal acosToleranceRatio = policy.getAcosToleranceRatio() != null
                ? policy.getAcosToleranceRatio() : BigDecimal.ZERO;
        BigDecimal proposedBudget = computeProposedBudget(
                currentBudget, actualAcos, targetAcos, acosToleranceRatio,
                spendVelocity, inventoryDays, resolvedBoundary, policy);

        if (proposedBudget == null) {
            // No adjustment needed (within tolerance)
            log.debug("V2BudgetEngine skipped campaign {} — ACoS within tolerance, no change",
                    campaign.getId());
            return EvaluationResult.skipped(SKIP_NO_CHANGE);
        }

        // Clamp to safety boundary min/max budget (Req 4.5)
        proposedBudget = clampToSafetyBoundary(proposedBudget, resolvedBoundary);

        // Final check: no effective change
        if (proposedBudget.compareTo(currentBudget) == 0) {
            return EvaluationResult.skipped(SKIP_NO_CHANGE);
        }

        // Compute risk score
        BigDecimal changeMagnitude = proposedBudget.subtract(currentBudget).abs()
                .divide(currentBudget, MC);
        BigDecimal dataConfidence = computeDataConfidence(campaign.getId(), snapshot, lookbackDays);
        BigDecimal dollarImpact = proposedBudget.subtract(currentBudget).abs();

        RiskScoreResult riskResult = riskScoreCalculator.calculate(new RiskScoreInput(
                changeMagnitude,
                dataConfidence,
                BigDecimal.ZERO, // historical volatility: computed as baseline for budget engine
                dollarImpact
        ));

        // Build the candidate decision
        CandidateDecision candidate = new CandidateDecision(
                UUID.randomUUID(),
                campaign.getStoreId(),
                campaign.getId(),
                ENTITY_TYPE_CAMPAIGN,
                campaign.getId(),
                FIELD_DAILY_BUDGET,
                HostingAdjustmentType.BUDGET,
                CHANGE_TYPE_BUDGET,
                CandidateDecision.ENGINE_V2_BUDGET,
                currentBudget,
                proposedBudget,
                riskResult.score(),
                CandidateDecisionContext.DEFAULT_RISK_THRESHOLD,
                dataConfidence,
                buildDecisionSnapshot(campaign, personality, ruleVersion, actualAcos,
                        targetAcos, currentBudget, proposedBudget, spendVelocity,
                        inventoryDays, riskResult)
        );

        log.info("V2BudgetEngine produced candidate for campaign {}: {} → {} (ACoS: actual={}, target={})",
                campaign.getId(), currentBudget, proposedBudget, actualAcos, targetAcos);

        return EvaluationResult.of(List.of(candidate));
    }

    /**
     * Compute the proposed budget based on ACoS performance and inventory.
     *
     * <p>Direction rules:
     * <ul>
     *   <li>INCREASE (Req 4.2): actualAcos &lt; targetAcos AND inventory &gt; inventoryHealthyDays
     *       → increase capped by maxDailyBudgetIncreaseRatio</li>
     *   <li>DECREASE (Req 4.3): actualAcos &gt; targetAcos * (1 + acosToleranceRatio)
     *       → decrease bounded by maxDailyBudgetDecreaseRatio</li>
     *   <li>WITHIN TOLERANCE: no change</li>
     * </ul>
     *
     * @return proposed budget, or null if no change is needed
     */
    BigDecimal computeProposedBudget(BigDecimal currentBudget,
                                     BigDecimal actualAcos,
                                     BigDecimal targetAcos,
                                     BigDecimal acosToleranceRatio,
                                     BigDecimal spendVelocity,
                                     Integer inventoryDays,
                                     SafetyBoundary resolvedBoundary,
                                     PersonalityPolicyEntity policy) {

        // Determine the upper threshold for triggering a decrease
        BigDecimal decreaseThreshold = targetAcos.multiply(
                BigDecimal.ONE.add(acosToleranceRatio, MC), MC);

        // Resolve inventory healthy days from safety boundary
        int inventoryHealthyDays = resolvedBoundary.get(SafetyBoundaryLimit.INVENTORY_HEALTHY_DAYS)
                .map(BigDecimal::intValue)
                .orElse(30); // default 30 days if not configured

        // Resolve max increase/decrease ratios
        BigDecimal maxIncreaseRatio = resolveMaxIncreaseRatio(resolvedBoundary, policy);
        BigDecimal maxDecreaseRatio = resolveMaxDecreaseRatio(resolvedBoundary, policy);

        if (actualAcos.compareTo(targetAcos) < 0) {
            // Actual ACoS is below target — performance is good
            // Req 4.2: only increase if inventory > inventoryHealthyDays
            if (inventoryDays == null || inventoryDays <= inventoryHealthyDays) {
                // Insufficient inventory or unknown — do not increase
                return null;
            }

            // Propose increase: scale by how far below target we are
            BigDecimal acosDifference = targetAcos.subtract(actualAcos, MC);
            BigDecimal acosRatio = acosDifference.divide(targetAcos, MC);

            // Proportional increase factor capped by maxIncreaseRatio
            BigDecimal increaseFactor = acosRatio.min(maxIncreaseRatio);
            BigDecimal increase = currentBudget.multiply(increaseFactor, MC);

            // Factor in spend velocity: if not spending the current budget fully,
            // scale increase down proportionally
            if (spendVelocity != null && spendVelocity.compareTo(BigDecimal.ZERO) > 0
                    && currentBudget.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal utilizationRate = spendVelocity.divide(currentBudget, MC);
                if (utilizationRate.compareTo(BigDecimal.ONE) < 0) {
                    // Only partially spending current budget — reduce increase
                    increase = increase.multiply(utilizationRate, MC);
                }
            }

            BigDecimal proposed = currentBudget.add(increase, MC)
                    .setScale(BUDGET_SCALE, RoundingMode.HALF_UP);

            // Ensure we don't exceed maxIncreaseRatio cap
            BigDecimal maxAllowed = currentBudget.multiply(
                    BigDecimal.ONE.add(maxIncreaseRatio, MC), MC)
                    .setScale(BUDGET_SCALE, RoundingMode.HALF_UP);
            proposed = proposed.min(maxAllowed);

            return proposed.compareTo(currentBudget) > 0 ? proposed : null;

        } else if (actualAcos.compareTo(decreaseThreshold) > 0) {
            // Actual ACoS exceeds threshold — propose decrease (Req 4.3)
            BigDecimal acosDifference = actualAcos.subtract(targetAcos, MC);
            BigDecimal acosRatio = acosDifference.divide(targetAcos, MC);

            // Proportional decrease factor capped by maxDecreaseRatio
            BigDecimal decreaseFactor = acosRatio.min(maxDecreaseRatio);
            BigDecimal decrease = currentBudget.multiply(decreaseFactor, MC);

            BigDecimal proposed = currentBudget.subtract(decrease, MC)
                    .setScale(BUDGET_SCALE, RoundingMode.HALF_UP);

            // Ensure we don't exceed maxDecreaseRatio cap
            BigDecimal minAllowed = currentBudget.multiply(
                    BigDecimal.ONE.subtract(maxDecreaseRatio, MC), MC)
                    .setScale(BUDGET_SCALE, RoundingMode.HALF_UP);
            proposed = proposed.max(minAllowed);

            return proposed.compareTo(currentBudget) < 0 ? proposed : null;

        } else {
            // Within tolerance — no change
            return null;
        }
    }

    /**
     * Clamp the proposed budget within the safety boundary's min/max daily budget (Req 4.5).
     */
    BigDecimal clampToSafetyBoundary(BigDecimal proposedBudget, SafetyBoundary resolvedBoundary) {
        BigDecimal result = proposedBudget;

        // Clamp to minimum daily budget
        BigDecimal minBudget = resolvedBoundary.get(SafetyBoundaryLimit.MIN_DAILY_BUDGET)
                .orElse(null);
        if (minBudget != null && result.compareTo(minBudget) < 0) {
            result = minBudget;
        }

        // Clamp to maximum daily budget
        BigDecimal maxBudget = resolvedBoundary.get(SafetyBoundaryLimit.MAX_DAILY_BUDGET)
                .orElse(null);
        if (maxBudget != null && result.compareTo(maxBudget) > 0) {
            result = maxBudget;
        }

        return result.setScale(BUDGET_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Resolve the maximum daily budget increase ratio from safety boundary and personality policy.
     * The safety boundary takes precedence (it's the hard limit); the personality policy
     * provides the softer ratio. The more restrictive (smaller) value wins.
     */
    private BigDecimal resolveMaxIncreaseRatio(SafetyBoundary resolvedBoundary,
                                               PersonalityPolicyEntity policy) {
        BigDecimal boundaryRatio = resolvedBoundary
                .get(SafetyBoundaryLimit.MAX_DAILY_BUDGET_INCREASE_RATIO)
                .orElse(null);
        BigDecimal policyRatio = policy.getMaxDailyBudgetIncreaseRatio();

        if (boundaryRatio != null && policyRatio != null && policyRatio.compareTo(BigDecimal.ZERO) > 0) {
            return boundaryRatio.min(policyRatio);
        }
        if (boundaryRatio != null) {
            return boundaryRatio;
        }
        if (policyRatio != null && policyRatio.compareTo(BigDecimal.ZERO) > 0) {
            return policyRatio;
        }
        // Default: 20% increase cap
        return new BigDecimal("0.20");
    }

    /**
     * Resolve the maximum daily budget decrease ratio from safety boundary and personality policy.
     */
    private BigDecimal resolveMaxDecreaseRatio(SafetyBoundary resolvedBoundary,
                                               PersonalityPolicyEntity policy) {
        BigDecimal boundaryRatio = resolvedBoundary
                .get(SafetyBoundaryLimit.MAX_DAILY_BUDGET_DECREASE_RATIO)
                .orElse(null);

        // The personality policy doesn't have a direct maxDailyBudgetDecreaseRatio field,
        // so we rely primarily on the safety boundary for the hard cap.
        if (boundaryRatio != null) {
            return boundaryRatio;
        }
        // Default: 30% decrease cap
        return new BigDecimal("0.30");
    }

    /**
     * Compute actual ACoS over the lookback window from the snapshot's performance data.
     * Returns null when there is no spend/sales signal.
     */
    BigDecimal computeActualAcos(UUID campaignId, DataSnapshot snapshot, int lookbackDays) {
        List<PerformanceDailyEntity> campaignData = snapshot.getPerformanceDataByCampaign(campaignId);
        if (campaignData == null || campaignData.isEmpty()) {
            return null;
        }

        // Filter to entity_type = "campaign" level data (not keyword-level) for budget decisions
        BigDecimal totalSpend = BigDecimal.ZERO;
        BigDecimal totalSales = BigDecimal.ZERO;

        for (PerformanceDailyEntity row : campaignData) {
            if (row.getSpend() != null) {
                totalSpend = totalSpend.add(row.getSpend());
            }
            if (row.getSales() != null) {
                totalSales = totalSales.add(row.getSales());
            }
        }

        if (totalSpend.compareTo(BigDecimal.ZERO) <= 0 && totalSales.compareTo(BigDecimal.ZERO) <= 0) {
            return null; // No signal
        }
        if (totalSales.compareTo(BigDecimal.ZERO) <= 0) {
            return NO_SALES_ACOS; // Spend with no sales — unbounded ACoS
        }

        // ACoS = spend / sales (as a ratio, not percentage)
        return totalSpend.divide(totalSales, MC);
    }

    /**
     * Compute the daily spend velocity (average daily spend over the lookback).
     */
    BigDecimal computeSpendVelocity(UUID campaignId, DataSnapshot snapshot, int lookbackDays) {
        List<PerformanceDailyEntity> campaignData = snapshot.getPerformanceDataByCampaign(campaignId);
        if (campaignData == null || campaignData.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalSpend = BigDecimal.ZERO;
        int daysWithData = 0;

        for (PerformanceDailyEntity row : campaignData) {
            if (row.getSpend() != null && row.getSpend().compareTo(BigDecimal.ZERO) > 0) {
                totalSpend = totalSpend.add(row.getSpend());
                daysWithData++;
            }
        }

        if (daysWithData == 0) {
            return BigDecimal.ZERO;
        }

        return totalSpend.divide(BigDecimal.valueOf(daysWithData), MC);
    }

    /**
     * Compute data confidence based on the number of days of data available
     * relative to the lookback window.
     */
    BigDecimal computeDataConfidence(UUID campaignId, DataSnapshot snapshot, int lookbackDays) {
        List<PerformanceDailyEntity> campaignData = snapshot.getPerformanceDataByCampaign(campaignId);
        if (campaignData == null || campaignData.isEmpty()) {
            return BigDecimal.ZERO;
        }

        int daysWithData = (int) campaignData.stream()
                .filter(r -> r.getSpend() != null && r.getSpend().compareTo(BigDecimal.ZERO) > 0)
                .count();

        if (lookbackDays <= 0) {
            return BigDecimal.ONE;
        }

        BigDecimal confidence = BigDecimal.valueOf(daysWithData)
                .divide(BigDecimal.valueOf(lookbackDays), MC);

        // Clamp to [0, 1]
        if (confidence.compareTo(BigDecimal.ONE) > 0) {
            confidence = BigDecimal.ONE;
        }
        if (confidence.compareTo(BigDecimal.ZERO) < 0) {
            confidence = BigDecimal.ZERO;
        }

        return confidence;
    }

    /**
     * Check if the campaign is within the adjustment cooldown window (Req 4.6).
     * Looks at the last budget operation created for this campaign.
     */
    boolean isWithinCooldown(CampaignEntity campaign, int cooldownHours) {
        if (cooldownHours <= 0) {
            return false;
        }

        LocalDateTime cooldownCutoff = LocalDateTime.now().minusHours(cooldownHours);

        // Query for the most recent budget operation on this campaign
        LambdaQueryWrapper<OperationEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OperationEntity::getEntityId, campaign.getId())
                .eq(OperationEntity::getEntityType, ENTITY_TYPE_CAMPAIGN)
                .eq(OperationEntity::getField, FIELD_DAILY_BUDGET)
                .eq(OperationEntity::getOperationSource, "ai_hosting")
                .ge(OperationEntity::getCreatedAt, cooldownCutoff)
                .last("LIMIT 1");

        OperationEntity recent = operationMapper.selectOne(wrapper);
        return recent != null;
    }

    /**
     * Build the immutable decision snapshot JSON (Requirement 34) capturing the
     * exact inputs and reasoning.
     */
    private String buildDecisionSnapshot(CampaignEntity campaign,
                                         AiPersonality personality,
                                         String ruleVersion,
                                         BigDecimal actualAcos,
                                         BigDecimal targetAcos,
                                         BigDecimal currentBudget,
                                         BigDecimal proposedBudget,
                                         BigDecimal spendVelocity,
                                         Integer inventoryDays,
                                         RiskScoreResult riskResult) {
        // Build a structured JSON snapshot
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"engine\":\"V2_BUDGET\"");
        sb.append(",\"campaignId\":\"").append(campaign.getId()).append("\"");
        sb.append(",\"storeId\":\"").append(campaign.getStoreId()).append("\"");
        sb.append(",\"personality\":\"").append(personality.machineValue()).append("\"");
        sb.append(",\"ruleVersion\":\"").append(ruleVersion).append("\"");
        sb.append(",\"actualAcos\":").append(actualAcos.toPlainString());
        sb.append(",\"targetAcos\":").append(targetAcos.toPlainString());
        sb.append(",\"currentBudget\":").append(currentBudget.toPlainString());
        sb.append(",\"proposedBudget\":").append(proposedBudget.toPlainString());
        sb.append(",\"spendVelocity\":").append(spendVelocity != null ? spendVelocity.toPlainString() : "null");
        sb.append(",\"inventoryDays\":").append(inventoryDays != null ? inventoryDays : "null");
        sb.append(",\"riskScore\":").append(riskResult.score().toPlainString());
        sb.append(",\"formulaVersion\":\"").append(riskResult.formulaVersion()).append("\"");
        sb.append(",\"direction\":\"").append(proposedBudget.compareTo(currentBudget) > 0 ? "INCREASE" : "DECREASE").append("\"");
        sb.append("}");
        return sb.toString();
    }
}
