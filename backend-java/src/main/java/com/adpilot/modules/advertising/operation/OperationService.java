package com.adpilot.modules.advertising.operation;

import java.util.UUID;

/**
 * The single orchestrating entry point for the Operation lifecycle (Req 2, 3, 4, 5, 6).
 *
 * <p>This service composes the focused building blocks under
 * {@code com.adpilot.modules.advertising.operation} and {@code ...advertising.platform} into the
 * end-to-end write path. It owns the cross-cutting concerns the building blocks deliberately leave
 * out — permission and data-scope enforcement, the in-flight conflict lock, idempotency coalescing,
 * the optimistic-lock guard, approval-threshold evaluation, and write-capability resolution — and
 * the single-transaction persistence that writes the Operation_Record, the pending-change record,
 * the audit log entry, and (for a write-capable {@code platform_mutation}) the Outbox entry, never
 * calling the platform inside the transaction (Req 6.1, 6.3).</p>
 *
 * <p>The {@link #createOperation(CreateOperationCommand)} pipeline is implemented here. The lifecycle
 * transition methods ({@link #transition}, {@link #cancel}, {@link #retry}, {@link #undo},
 * {@link #reconcile}, {@link #approve}, {@link #reject}, {@link #publishLocalDraft}) are declared as
 * the full contract and are implemented by the Requirement 4/8/12 tasks; until then they throw
 * {@link UnsupportedOperationException} rather than silently no-op.</p>
 *
 * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.6, 3.2, 3.7, 6.1, 6.2, 6.3, 9.4, 53.3.</p>
 */
public interface OperationService {

    /**
     * Create an Operation, running the full creation pipeline in order — permission check
     * (Req 27), data-scope + per-record ownership validation (Req 24, 25), in-flight conflict lock
     * (Req 5.6), idempotency coalescing by {@code logicalIdempotencyKey} (Req 5.3),
     * optimistic-version check (Req 5.4/5.5), approval-threshold evaluation (Req 4, 22.8), and
     * write-capability resolution (Req 53) — then persisting the Operation, pending-change, audit,
     * and (only for a write-capable {@code platform_mutation}) Outbox records in one transaction
     * with no platform call (Req 6.1).
     *
     * @param cmd the requested change; must not be {@code null}
     * @return the creation result (the Operation was created and persisted, NOT that the platform
     *         applied it — Req 6.8); a coalesced repeated activation returns the existing Operation
     */
    OperationResult createOperation(CreateOperationCommand cmd);

    /** Apply a lifecycle event to an Operation (Req 4). Implemented by the Requirement 4 task. */
    OperationResult transition(UUID operationId, TransitionEvent event);

    /** Cancel an Operation, routing by its current state (Req 4.7/4.8). Implemented by the Requirement 4 task. */
    OperationResult cancel(UUID operationId);

    /**
     * Supersede an in-flight Operation that a newer Operation replaces against the same object (for
     * example after an AI_Personality switch, Req 49.14), routing by its current submission state
     * (Req 4.11): a not-yet-submitted Operation is transitioned DIRECTLY to {@code superseded}, while
     * an already-submitted or in-flight Operation is routed through the {@code cancel_requested} path
     * rather than directly to {@code superseded}; the confirmed value is left unchanged. Implemented
     * by the Requirement 4 task.
     */
    OperationResult supersede(UUID operationId);

    /** Retry a failed Operation as a NEW attempt (Req 4.2/4.5). Implemented by the Requirement 4 task. */
    OperationResult retry(UUID operationId);

    /** Undo an Operation via a compensating Operation (Req 8.4/8.5). Implemented by the Requirement 8 task. */
    OperationResult undo(UUID operationId);

    /** Reconcile an Operation against the platform's actual state (Req 4.10, 56.5). Implemented by the Requirement 4 task. */
    OperationResult reconcile(UUID operationId);

    /** Approve an {@code awaiting_approval} Operation → {@code pending} (Req 7.7, 24.2). Implemented by the Requirement 4 task. */
    OperationResult approve(UUID operationId);

    /** Reject an {@code awaiting_approval} Operation → {@code cancelled}. Implemented by the Requirement 4 task. */
    OperationResult reject(UUID operationId);

    /** Publish a terminal {@code local-only} draft as a NEW {@code pending} Operation (Req 12.10). Implemented by the Requirement 12 task. */
    OperationResult publishLocalDraft(UUID localOnlyOperationId);
}
