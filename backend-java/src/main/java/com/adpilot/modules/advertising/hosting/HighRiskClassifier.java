package com.adpilot.modules.advertising.hosting;

/**
 * Classifies AI hosting decisions as high-risk based on change type
 * (Requirement 7.5).
 *
 * <p>High-risk decisions are unconditionally routed to {@code awaiting_approval}
 * within executing modes ({@code approval_required} and {@code auto_execute}),
 * irrespective of the computed risk score.
 *
 * <p>The following are classified as high-risk:
 * <ul>
 *   <li>Campaign state changes (pause/enable).</li>
 *   <li>Keyword additions (positive keyword expansion).</li>
 *   <li>Negative keyword additions.</li>
 *   <li>Large budget reductions exceeding the configurable
 *       {@code maxDailyBudgetDecreaseRatio} threshold.</li>
 * </ul>
 *
 * <p>Ordinary budget reductions within the threshold are NOT high-risk and are
 * routed by risk score and execution mode like other normal adjustments.
 *
 * <p>A negative keyword matching a Brand_Word is NOT an approvable high-risk
 * action — it is hard-rejected upstream and never reaches this classifier.
 *
 * <p>Validates: Requirements 7.5.</p>
 */
public interface HighRiskClassifier {

    /**
     * Determine whether the given candidate decision context represents a
     * high-risk action that must route to {@code awaiting_approval}.
     *
     * @param context the candidate decision context
     * @return {@code true} if the decision is classified as high-risk
     */
    boolean isHighRisk(CandidateDecisionContext context);

    /**
     * Return a reason string explaining why the decision is (or is not)
     * classified as high-risk.
     *
     * @param context the candidate decision context
     * @return a reason string (e.g., "high_risk_state_change",
     *         "high_risk_keyword_addition", "high_risk_large_budget_decrease",
     *         or "not_high_risk")
     */
    String classificationReason(CandidateDecisionContext context);
}
