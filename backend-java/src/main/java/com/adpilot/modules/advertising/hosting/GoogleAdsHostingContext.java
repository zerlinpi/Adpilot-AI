package com.adpilot.modules.advertising.hosting;

import java.math.BigDecimal;

/**
 * The resolved personality-policy inputs the {@link GoogleAdsHostingEngine} needs to
 * produce candidate decisions for a single Google Ads campaign
 * (platform-workspace-rbac Req 8.1).
 *
 * <p>Carrying these as an explicit, immutable input keeps the engine a pure function
 * over (campaign data, safety boundary, phase, policy) — exactly like
 * {@link V1BidEngine} / {@link V2BudgetEngineImpl} read their personality policy — so the
 * engine can be unit-tested deterministically. The wiring layer
 * ({@code GoogleAdsHostingService}) resolves these values from the campaign's effective
 * AI_Personality / Personality_Policy before calling the engine.</p>
 *
 * @param targetAcos             the campaign's target ACoS as a ratio (cost / conversion value);
 *                              must be {@code > 0} for budget/bid optimization to run
 * @param lookbackDays          the resolved personality policy's lookback window in days
 * @param personality           the resolved AI_Personality machine value (e.g. {@code balanced})
 * @param ruleVersion           the Personality_Rule_Version recorded on every emitted AI decision
 * @param acosToleranceRatio    the tolerance band around target ACoS within which no change is made
 * @param maxBudgetIncreaseRatio the maximum single-run daily-budget increase ratio (e.g. 0.20 = +20%)
 * @param maxBudgetDecreaseRatio the maximum single-run daily-budget decrease ratio (e.g. 0.30 = -30%)
 * @param approvalChangeRatio   the absolute change ratio at/above which approval is required
 * @param enableCampaignCreate  whether campaign-create candidates may be produced for strong performers
 * @param strongPerformerRatio  the fraction of target ACoS below which a campaign is a "strong performer"
 *                              eligible for a scale-out campaign-create candidate (e.g. 0.5 = half target)
 */
public record GoogleAdsHostingContext(
        BigDecimal targetAcos,
        int lookbackDays,
        String personality,
        String ruleVersion,
        BigDecimal acosToleranceRatio,
        BigDecimal maxBudgetIncreaseRatio,
        BigDecimal maxBudgetDecreaseRatio,
        BigDecimal approvalChangeRatio,
        boolean enableCampaignCreate,
        BigDecimal strongPerformerRatio
) {

    private static final BigDecimal DEFAULT_MAX_INCREASE = new BigDecimal("0.20");
    private static final BigDecimal DEFAULT_MAX_DECREASE = new BigDecimal("0.30");
    private static final BigDecimal DEFAULT_APPROVAL_RATIO = new BigDecimal("0.30");
    private static final BigDecimal DEFAULT_STRONG_PERFORMER_RATIO = new BigDecimal("0.50");

    public GoogleAdsHostingContext {
        if (lookbackDays < 0) {
            lookbackDays = 0;
        }
        if (acosToleranceRatio == null || acosToleranceRatio.signum() < 0) {
            acosToleranceRatio = BigDecimal.ZERO;
        }
        if (maxBudgetIncreaseRatio == null || maxBudgetIncreaseRatio.signum() <= 0) {
            maxBudgetIncreaseRatio = DEFAULT_MAX_INCREASE;
        }
        if (maxBudgetDecreaseRatio == null || maxBudgetDecreaseRatio.signum() <= 0) {
            maxBudgetDecreaseRatio = DEFAULT_MAX_DECREASE;
        }
        if (approvalChangeRatio == null || approvalChangeRatio.signum() < 0) {
            approvalChangeRatio = DEFAULT_APPROVAL_RATIO;
        }
        if (strongPerformerRatio == null || strongPerformerRatio.signum() <= 0) {
            strongPerformerRatio = DEFAULT_STRONG_PERFORMER_RATIO;
        }
        if (personality == null || personality.isBlank()) {
            personality = "balanced";
        }
        if (ruleVersion == null || ruleVersion.isBlank()) {
            ruleVersion = "v1.0";
        }
    }

    /**
     * A sensible default context for a target ACoS and lookback, using the balanced
     * personality defaults; convenient for callers that have not resolved a richer policy.
     */
    public static GoogleAdsHostingContext defaults(BigDecimal targetAcos, int lookbackDays) {
        return new GoogleAdsHostingContext(
                targetAcos, lookbackDays, "balanced", "v1.0",
                BigDecimal.ZERO, DEFAULT_MAX_INCREASE, DEFAULT_MAX_DECREASE,
                DEFAULT_APPROVAL_RATIO, false, DEFAULT_STRONG_PERFORMER_RATIO);
    }
}
