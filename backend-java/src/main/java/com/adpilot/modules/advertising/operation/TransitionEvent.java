package com.adpilot.modules.advertising.operation;

/**
 * The set of events that drive an {@code Operation}'s {@link SyncState} through the legal
 * transitions enumerated in Requirement 4.1.
 *
 * <p>Each event names <em>why</em> a transition happens (an operator action, a platform
 * acknowledgement, a timeout, a reconciliation outcome). {@link OperationStateMachine} maps each
 * {@code (from, event)} pair to exactly one target {@link SyncState}; an event that is not legal for
 * a given source state is rejected. Several events may legally apply from more than one source state
 * (for example {@link #PLATFORM_EFFECTIVE} confirms an Operation from {@code submitted},
 * {@code amazon-processing}, {@code cancel_requested}, {@code reconciliation_required}, or
 * {@code expired}), and two distinct events may legally land on the same target (from
 * {@code awaiting_approval} both {@link #REJECT} and {@link #CANCEL} resolve to {@code cancelled}).</p>
 *
 * <p>Validates: Requirements 4.1, 3.1.</p>
 */
public enum TransitionEvent {

    /**
     * The change's absolute change ratio meets/exceeds the approval threshold, so a not-yet-submitted
     * Operation is held for operator approval ({@code pending} → {@code awaiting_approval}).
     */
    REQUEST_APPROVAL,

    /**
     * No approval is required and the Store is write-capable, so a not-yet-submitted Operation is sent
     * to the platform ({@code pending} → {@code submitted}).
     */
    SUBMIT,

    /**
     * An operator approves an Operation held for approval ({@code awaiting_approval} →
     * {@code submitted}).
     */
    APPROVE,

    /**
     * An operator rejects an Operation held for approval ({@code awaiting_approval} →
     * {@code cancelled}).
     */
    REJECT,

    /**
     * An operator explicitly cancels a not-yet-submitted Operation ({@code pending} or
     * {@code awaiting_approval} → {@code cancelled}), per Requirement 4.7.
     */
    CANCEL,

    /**
     * A newer Operation replaces a not-yet-submitted Operation against the same object
     * ({@code pending} or {@code awaiting_approval} → {@code superseded}), per Requirement 4.11.
     */
    SUPERSEDE,

    /**
     * An operator requests cancellation of an already-submitted or in-flight Operation
     * ({@code submitted} or {@code amazon-processing} → {@code cancel_requested}), per
     * Requirement 4.8 — a local cancel does NOT guarantee the platform stops.
     */
    REQUEST_CANCEL,

    /**
     * The platform acknowledges a submitted Operation and is applying it
     * ({@code submitted} → {@code amazon-processing}).
     */
    PLATFORM_PROCESSING,

    /**
     * The platform confirms the change is applied. Legal from {@code submitted} (the platform may
     * confirm directly), {@code amazon-processing}, {@code cancel_requested} (the change had already
     * become effective; undoing it requires a compensating rollback Operation), {@code expired} (the
     * platform returned success after the timeout), and {@code reconciliation_required} (the
     * reconciled platform state shows the change applied) — in all cases the target is
     * {@code effective}.
     */
    PLATFORM_EFFECTIVE,

    /**
     * The platform rejects the change or its submission errored. Legal from {@code submitted},
     * {@code amazon-processing}, {@code expired} (the platform returned failure after the timeout),
     * and {@code reconciliation_required} (the reconciled platform state shows the change did not
     * apply or errored) — in all cases the target is {@code failed}.
     */
    PLATFORM_FAILED,

    /**
     * An in-flight Operation exceeds its platform timeout ({@code submitted} or
     * {@code amazon-processing} → {@code expired}), per Requirement 4.4.
     */
    TIMEOUT,

    /**
     * The platform confirms the change was not applied. Legal from {@code cancel_requested} (the
     * platform supports cancel and confirmed the change was not applied) and
     * {@code reconciliation_required} (the reconciled platform state shows the change was not applied
     * and the Operation never took effect) — in both cases the target is {@code cancelled}.
     */
    CANCEL_CONFIRMED,

    /**
     * The platform's actual state cannot yet be determined and must be reconciled before any further
     * action. Legal from {@code cancel_requested} (the cancellation request itself errored or could
     * not be confirmed) and {@code expired} (the platform's actual state must be reconciled before any
     * retry) — in both cases the target is {@code reconciliation_required}.
     */
    RECONCILE,

    /**
     * An operator retries a failed Operation, which creates a NEW attempt in the {@code pending} state
     * ({@code failed} → {@code pending}), per Requirements 4.2 and 4.5. The state machine only encodes
     * the legal {@code failed} → {@code pending} edge; {@code OperationService} is responsible for
     * minting the new {@code attemptId}, {@code submissionIdempotencyKey}, and incremented
     * {@code attemptNumber}.
     */
    RETRY,

    /**
     * The platform has confirmed acceptance of the submitted request — the HTTP call returned a 2xx
     * with the Amazon request ID and external entity ID. Advances the Operation from
     * {@code submitting} → {@code submitted} (Requirement 16.7).
     */
    PLATFORM_ACCEPTED,

    /**
     * The platform returned a retryable/transient rejection (429, 5xx) and the same Operation will be
     * re-attempted by the Outbox after a backoff delay. Returns the Operation from
     * {@code submitting} → {@code pending} so it can be re-claimed (Requirement 16.7).
     */
    RETRYABLE_REJECT,

    /**
     * The platform returned a permanent rejection (4xx, invalid token, etc.) that will not succeed on
     * retry. Advances the Operation from {@code submitting} → {@code failed} (Requirement 16.7).
     */
    PERMANENT_REJECT
}
