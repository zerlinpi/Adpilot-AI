package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.service.HostingAdjustmentType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Immutable context record carrying all information the {@link DecisionRoutingPipeline}
 * needs to route a single candidate decision through the fixed-precedence logic
 * (Requirement 7.10).
 *
 * <p>This record is assembled by the {@code OptimizationCoordinator} (or the engine
 * that produced the candidate) and handed to the pipeline. It captures the resolved
 * governance flags, execution mode, risk score, and classification inputs so the
 * pipeline is a pure function over this context — no I/O during routing.
 *
 * @param storeId              the store this decision belongs to
 * @param campaignId           the campaign the decision targets
 * @param adjustmentType       the type of adjustment (BID, BUDGET, KEYWORD, NEGATIVE)
 * @param changeType           the wire change type (e.g., "bid", "budget", "state",
 *                             "keyword", "negative_keyword")
 * @param killSwitchActive     whether the kill switch is active for the scope
 * @param shadowModeActive     whether shadow mode is on
 * @param phaseEnabled         whether the engine is enabled for the current phase
 * @param canaryStoreAllowed   whether the store is included when org-level canary is active
 * @param executionMode        the resolved execution mode for this campaign
 * @param riskScore            the computed risk score ∈ [0.0, 1.0]
 * @param riskThreshold        the store's auto-execute threshold (default 0.3)
 * @param budgetDecreaseRatio  the ratio of budget decrease (only relevant for budget
 *                             reductions); null or zero for non-budget changes
 * @param maxDailyBudgetDecreaseRatio the configured max decrease ratio threshold above
 *                             which a budget reduction is classified as "large"
 */
public record CandidateDecisionContext(
        UUID storeId,
        UUID campaignId,
        HostingAdjustmentType adjustmentType,
        String changeType,
        boolean killSwitchActive,
        boolean shadowModeActive,
        boolean phaseEnabled,
        boolean canaryStoreAllowed,
        ExecutionMode executionMode,
        BigDecimal riskScore,
        BigDecimal riskThreshold,
        BigDecimal budgetDecreaseRatio,
        BigDecimal maxDailyBudgetDecreaseRatio
) {
    /** Default auto-execute risk threshold per Requirement 7.4. */
    public static final BigDecimal DEFAULT_RISK_THRESHOLD = new BigDecimal("0.3");

    /** Default max daily budget decrease ratio (e.g., 0.30 = 30% cut). */
    public static final BigDecimal DEFAULT_MAX_BUDGET_DECREASE_RATIO = new BigDecimal("0.30");

    public CandidateDecisionContext(
            UUID storeId,
            UUID campaignId,
            HostingAdjustmentType adjustmentType,
            String changeType,
            boolean killSwitchActive,
            boolean shadowModeActive,
            boolean phaseEnabled,
            ExecutionMode executionMode,
            BigDecimal riskScore,
            BigDecimal riskThreshold,
            BigDecimal budgetDecreaseRatio,
            BigDecimal maxDailyBudgetDecreaseRatio
    ) {
        this(storeId, campaignId, adjustmentType, changeType, killSwitchActive, shadowModeActive,
                phaseEnabled, true, executionMode, riskScore, riskThreshold,
                budgetDecreaseRatio, maxDailyBudgetDecreaseRatio);
    }

    public CandidateDecisionContext {
        if (storeId == null) throw new IllegalArgumentException("storeId must not be null");
        if (executionMode == null) throw new IllegalArgumentException("executionMode must not be null");
        if (riskScore == null) throw new IllegalArgumentException("riskScore must not be null");
        if (riskThreshold == null) {
            riskThreshold = DEFAULT_RISK_THRESHOLD;
        }
        if (maxDailyBudgetDecreaseRatio == null) {
            maxDailyBudgetDecreaseRatio = DEFAULT_MAX_BUDGET_DECREASE_RATIO;
        }
    }
}
