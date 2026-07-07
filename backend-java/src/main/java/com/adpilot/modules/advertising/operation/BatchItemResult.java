package com.adpilot.modules.advertising.operation;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * The uniform per-item outcome of one element of a {@link BatchOperationService#createBatch} call
 * (Req 6.7, 6.8, 36.2).
 *
 * <p>This type represents the <strong>creation result</strong> only — whether an Operation was
 * created and persisted for the referenced record — and deliberately NOT the submission result or
 * the platform final result (Req 6.8). A successful item means an Operation_Record now exists, not
 * that Amazon has applied the change. Callers (and the Frontend, Req 36.2) must surface API
 * completion as "Operations were created", never as platform success.</p>
 *
 * <p>Every item — succeeded or failed — carries the referencing record identifier ({@link #entityId}
 * with its {@link #entityType}) so the per-item result can be correlated back to the row the
 * operator selected, even for items whose creation failed. On success the resolved
 * {@link SyncState}/{@link ExecutionStatus} and the persisted {@link #operationId} are populated and
 * {@link #failureReason} is {@code null}; on failure {@link #created} is {@code false},
 * {@link #failureReason} explains why creation failed, and the lifecycle fields are {@code null}.</p>
 *
 * <p>Validates: Requirements 6.6, 6.7, 6.8, 36.2.</p>
 */
@Value
@Builder
public class BatchItemResult {

    /** The referenced record's entity type (echoed back for correlation). */
    String entityType;

    /** The referenced record identifier (Req 6.7) — present for both succeeded and failed items. */
    UUID entityId;

    /**
     * The creation result (Req 6.7): {@code true} when an Operation was created and persisted for
     * this record, {@code false} when creation failed.
     */
    boolean created;

    /**
     * {@code true} when this item coalesced into a pre-existing logical Operation by
     * {@code logicalIdempotencyKey} (a repeated activation) rather than creating a new one (Req 5.3).
     * A coalesced item still counts as {@link #created} {@code true} because an Operation exists.
     */
    boolean coalesced;

    /** The persisted Operation's id when {@link #created}; {@code null} when creation failed. */
    UUID operationId;

    /** Resolved Sync_State for a created {@code platform_mutation}; {@code null} otherwise. */
    SyncState syncState;

    /** Resolved executionStatus for a created {@code local_configuration}; {@code null} otherwise. */
    ExecutionStatus executionStatus;

    /**
     * The human-readable reason creation failed (Req 6.7); {@code null} for a created item. Carries
     * the failure code/message from the rejected {@code createOperation} attempt.
     */
    String failureReason;

    /**
     * Build a succeeded item from the creation result of one {@code createOperation} call.
     *
     * @param command the originating command (for entity correlation); must not be {@code null}
     * @param result  the creation result returned by {@code createOperation}; must not be {@code null}
     */
    public static BatchItemResult created(CreateOperationCommand command, OperationResult result) {
        return BatchItemResult.builder()
                .entityType(command.getEntityType())
                .entityId(command.getEntityId())
                .created(true)
                .coalesced(result.isCoalesced())
                .operationId(result.getOperationId())
                .syncState(result.getSyncState())
                .executionStatus(result.getExecutionStatus())
                .failureReason(null)
                .build();
    }

    /**
     * Build a failed item, retaining the referencing record identifier and the failure reason.
     *
     * @param command       the originating command (for entity correlation); must not be {@code null}
     * @param failureReason the human-readable reason creation failed
     */
    public static BatchItemResult failed(CreateOperationCommand command, String failureReason) {
        return BatchItemResult.builder()
                .entityType(command.getEntityType())
                .entityId(command.getEntityId())
                .created(false)
                .coalesced(false)
                .operationId(null)
                .syncState(null)
                .executionStatus(null)
                .failureReason(failureReason)
                .build();
    }
}
