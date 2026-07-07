package com.adpilot.modules.advertising.hosting;

/**
 * The result of routing a candidate decision through the
 * {@link DecisionRoutingPipeline} (Requirement 7.10).
 *
 * <p>Contains the routing outcome plus an optional reason string explaining why
 * that outcome was chosen (useful for logging and Decision_Snapshot recording).
 *
 * @param outcome the determined routing outcome
 * @param reason  a human-readable explanation of why this outcome was produced
 *               (e.g., "KILL_SWITCH", "shadow_mode", "phase_disabled",
 *               "observe_only", "recommend_only", "high_risk_state_change",
 *               "high_risk_keyword_addition", "high_risk_large_budget_decrease",
 *               "risk_above_threshold", "auto_execute_below_threshold")
 */
public record RoutingResult(
        RoutingOutcome outcome,
        String reason
) {
    public RoutingResult {
        if (outcome == null) throw new IllegalArgumentException("outcome must not be null");
        if (reason == null || reason.isBlank()) {
            reason = outcome.value();
        }
    }

    /** Convenience factory for NO_OP outcomes. */
    public static RoutingResult noOp(String reason) {
        return new RoutingResult(RoutingOutcome.NO_OP, reason);
    }

    /** Convenience factory for AI_DECISIONS_ONLY outcomes. */
    public static RoutingResult aiDecisionsOnly(String reason) {
        return new RoutingResult(RoutingOutcome.AI_DECISIONS_ONLY, reason);
    }

    /** Convenience factory for PENDING_OPERATION outcomes. */
    public static RoutingResult pending(String reason) {
        return new RoutingResult(RoutingOutcome.PENDING_OPERATION, reason);
    }

    /** Convenience factory for AWAITING_APPROVAL_OPERATION outcomes. */
    public static RoutingResult awaitingApproval(String reason) {
        return new RoutingResult(RoutingOutcome.AWAITING_APPROVAL_OPERATION, reason);
    }
}
