package com.adpilot.modules.advertising.operation;

import com.adpilot.modules.advertising.entity.OperationEntity;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * The outcome of an {@link OperationService} call — specifically the <strong>creation result</strong>
 * of {@code createOperation}, deliberately kept distinct from the submission result and the platform
 * final result (Req 6.8). A successful {@code createOperation} means the Operation was created and
 * persisted, NOT that Amazon has applied it.
 *
 * <p>The result carries the persisted Operation's identity and its resolved lifecycle state:
 * a {@link SyncState} for a {@code platform_mutation} (one of {@code local-only},
 * {@code awaiting_approval}, or {@code pending} at creation time) or an {@link ExecutionStatus} for a
 * {@code local_configuration} (Req 3.7/3.8). The {@code coalesced} flag indicates the activation was
 * recognized as a repeated click of an existing logical Operation and reused it rather than creating
 * a second one (Req 5.3).</p>
 *
 * <p>Validates: Requirements 6.8, 3.7, 5.3.</p>
 */
@Value
@Builder
public class OperationResult {

    /** The persisted Operation's id. */
    UUID operationId;

    /** The logical Operation id, stable across attempts of the same logical change (Req 5.1). */
    UUID logicalOperationId;

    /** The owning store. */
    UUID storeId;

    /** The execution scope of the Operation. */
    OperationScope operationScope;

    /** The resolved Sync_State for a {@code platform_mutation}; {@code null} for a {@code local_configuration}. */
    SyncState syncState;

    /** The resolved executionStatus for a {@code local_configuration}; {@code null} for a {@code platform_mutation}. */
    ExecutionStatus executionStatus;

    /** Target entity type. */
    String entityType;

    /** Target entity id. */
    UUID entityId;

    /**
     * {@code true} when this activation was coalesced into a pre-existing logical Operation by
     * {@code logicalIdempotencyKey} (a repeated click), so no new Operation was created (Req 5.3).
     */
    boolean coalesced;

    /**
     * Build a result from a persisted Operation_Record.
     *
     * @param entity    the persisted Operation; must not be {@code null}
     * @param coalesced whether this represents a coalesced repeated activation (Req 5.3)
     */
    public static OperationResult from(OperationEntity entity, boolean coalesced) {
        return OperationResult.builder()
                .operationId(entity.getId())
                .logicalOperationId(entity.getLogicalOperationId())
                .storeId(entity.getStoreId())
                .operationScope(OperationMachineValues.toOperationScope(entity.getOperationScope()))
                .syncState(OperationMachineValues.toSyncState(entity.getSyncState()))
                .executionStatus(OperationMachineValues.toExecutionStatus(entity.getExecutionStatus()))
                .entityType(entity.getEntityType())
                .entityId(entity.getEntityId())
                .coalesced(coalesced)
                .build();
    }
}
