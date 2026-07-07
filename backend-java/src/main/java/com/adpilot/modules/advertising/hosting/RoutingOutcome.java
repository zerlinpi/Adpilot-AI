package com.adpilot.modules.advertising.hosting;

/**
 * The four possible outcomes produced by the {@link DecisionRoutingPipeline}
 * for a candidate AI decision (Requirement 7.10).
 *
 * <p>The pipeline NEVER produces a "submitted" outcome — submission is the sole
 * responsibility of the {@code OutboxWorker} which claims {@code pending} Operations.
 *
 * <p>Validates: Requirements 7.3, 7.4, 7.5, 7.10, 35.1, 35.3, 37.2, 37.3.</p>
 */
public enum RoutingOutcome {

    /**
     * No decision persisted, no Operation created.
     * Produced when:
     * <ul>
     *   <li>Kill switch is active for the scope (logs KILL_SWITCH).</li>
     *   <li>Engine is not enabled for the active phase (logs phase-disabled).</li>
     * </ul>
     */
    NO_OP("no_op"),

    /**
     * Decision is persisted in {@code ai_decisions} only; no Operation is created.
     * Produced when:
     * <ul>
     *   <li>Shadow mode is on (decision saved for analysis, never submitted).</li>
     *   <li>Execution mode is {@code observe_only} or {@code recommend_only}.</li>
     * </ul>
     */
    AI_DECISIONS_ONLY("ai_decisions_only"),

    /**
     * A {@code pending} Operation is created (with an Outbox row in the same
     * transaction). The OutboxWorker will claim and submit it.
     * Produced when Execution_Mode is {@code auto_execute}, the decision is not
     * high-risk, and the risk score is below the store's threshold.
     */
    PENDING_OPERATION("pending_operation"),

    /**
     * An {@code awaiting_approval} Operation is created, linked to an
     * {@code approval_requests} record. The Operation transitions to {@code pending}
     * only upon explicit human approval.
     * Produced when:
     * <ul>
     *   <li>Execution mode is {@code approval_required}.</li>
     *   <li>Execution mode is {@code auto_execute} AND the decision is high-risk
     *       or risk score is at/above the store's threshold.</li>
     * </ul>
     */
    AWAITING_APPROVAL_OPERATION("awaiting_approval_operation");

    private final String value;

    RoutingOutcome(String value) {
        this.value = value;
    }

    /**
     * The canonical wire/storage value (lowercase, underscored).
     */
    public String value() {
        return value;
    }
}
