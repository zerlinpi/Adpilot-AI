package com.adpilot.modules.advertising.operation;

import java.util.EnumSet;
import java.util.Set;

/**
 * The explicit lifecycle status of a {@code platform_mutation} Operation with respect to the
 * external platform (Amazon).
 *
 * <ul>
 *   <li>{@link #LOCAL_ONLY} — persisted internally, no platform submission attempted or possible;
 *       a terminal local state.</li>
 *   <li>{@link #PENDING} — created and awaiting submission.</li>
 *   <li>{@link #AWAITING_APPROVAL} — created but held because the change meets/exceeds the approval
 *       threshold and needs operator approval before submission.</li>
 *   <li>{@link #SUBMITTING} — the Outbox has claimed the Operation and the platform call is
 *       in progress; a transient state that distinguishes "call in flight" from "idle pending".
 *       Only a confirmed acceptance advances to {@code SUBMITTED}; a retryable rejection returns
 *       to {@code PENDING} for re-claim; a permanent rejection advances to {@code FAILED}
 *       (Requirement 16.7).</li>
 *   <li>{@link #SUBMITTED} — sent to the platform, awaiting acknowledgement.</li>
 *   <li>{@link #AMAZON_PROCESSING} — acknowledged by the platform and being applied there.</li>
 *   <li>{@link #EFFECTIVE} — confirmed applied on the platform.</li>
 *   <li>{@link #FAILED} — platform rejected or submission errored.</li>
 *   <li>{@link #CANCEL_REQUESTED} — a cancellation has been requested for an already-submitted or
 *       in-flight Operation; the final platform state is still being resolved.</li>
 *   <li>{@link #CANCELLED} — confirmed not applied on the platform; the Operation never took
 *       effect.</li>
 *   <li>{@link #SUPERSEDED} — replaced by a newer Operation (for example after a personality
 *       switch).</li>
 *   <li>{@link #EXPIRED} — an in-flight Operation that exceeded its platform timeout; NOT terminal,
 *       because the platform may still return a final result.</li>
 *   <li>{@link #RECONCILIATION_REQUIRED} — the platform's actual state must be reconciled before any
 *       further action such as a retry.</li>
 * </ul>
 *
 * <p>Validates: Requirements 4.1, 7.1, 16.7.</p>
 */
public enum SyncState {
    LOCAL_ONLY,
    PENDING,
    AWAITING_APPROVAL,
    SUBMITTING,
    SUBMITTED,
    AMAZON_PROCESSING,
    EFFECTIVE,
    FAILED,
    CANCEL_REQUESTED,
    CANCELLED,
    SUPERSEDED,
    EXPIRED,
    RECONCILIATION_REQUIRED;

    /**
     * The Unsettled_State set — the Sync_States in which an Operation's outcome on the platform is
     * not yet finally settled. For an Operation in any of these states, the Pending_Overlay surfaces
     * the Operation's pending value and the in-flight conflict lock blocks a new conflicting
     * Operation against the same object.
     *
     * <p>Defined as EXACTLY {@code PENDING}, {@code AWAITING_APPROVAL}, {@code SUBMITTING},
     * {@code SUBMITTED}, {@code AMAZON_PROCESSING}, {@code CANCEL_REQUESTED}, {@code EXPIRED}, and
     * {@code RECONCILIATION_REQUIRED}. The settled states are {@code LOCAL_ONLY}, {@code EFFECTIVE},
     * {@code FAILED}, {@code CANCELLED}, and {@code SUPERSEDED}.</p>
     *
     * <p>Validates: Requirements 7.1, 16.7.</p>
     */
    public static final Set<SyncState> UNSETTLED = EnumSet.of(
            PENDING,
            AWAITING_APPROVAL,
            SUBMITTING,
            SUBMITTED,
            AMAZON_PROCESSING,
            CANCEL_REQUESTED,
            EXPIRED,
            RECONCILIATION_REQUIRED);

    /**
     * @return {@code true} if this Sync_State is an Unsettled_State (its platform outcome is not yet
     *         finally settled).
     */
    public boolean isUnsettled() {
        return UNSETTLED.contains(this);
    }
}
