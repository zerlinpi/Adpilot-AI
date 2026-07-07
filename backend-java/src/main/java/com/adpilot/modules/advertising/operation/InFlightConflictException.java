package com.adpilot.modules.advertising.operation;

import com.adpilot.common.exception.BusinessException;

import java.util.UUID;

/**
 * Thrown by the {@link InFlightConflictLock} when a new Operation is attempted against an
 * advertising object that already has an Operation in any {@link SyncState#UNSETTLED Unsettled_State}
 * ({@code pending}, {@code awaiting_approval}, {@code submitted}, {@code amazon-processing},
 * {@code cancel_requested}, {@code expired}, or {@code reconciliation_required}).
 *
 * <p>Per Requirement 5.6 the Advertising_Module SHALL reject a new conflicting Operation against the
 * same object and SHALL inform the operator that an Operation is already in progress. This is a
 * recoverable, operator-facing rejection (not a programming error), so it is surfaced as a
 * {@link BusinessException} carrying HTTP {@code 409 Conflict}.</p>
 *
 * <p>Validates: Requirements 5.6.</p>
 */
public class InFlightConflictException extends BusinessException {

    /** Stable machine code distinguishing this rejection from other 409s. */
    public static final String CODE = "OPERATION_IN_PROGRESS";

    /** The entity type ({@code campaign}, {@code keyword}, ...) the conflict was detected on. */
    private final String entityType;

    /** The id of the object that already has an in-flight Operation. */
    private final UUID entityId;

    /** The id of the existing in-flight Operation, where known; {@code null} otherwise. */
    private final UUID conflictingOperationId;

    /** The Sync_State the existing in-flight Operation is in, where known; {@code null} otherwise. */
    private final SyncState conflictingSyncState;

    public InFlightConflictException(String entityType,
                                     UUID entityId,
                                     UUID conflictingOperationId,
                                     SyncState conflictingSyncState) {
        super(409, CODE, buildMessage(entityType, entityId, conflictingSyncState));
        this.entityType = entityType;
        this.entityId = entityId;
        this.conflictingOperationId = conflictingOperationId;
        this.conflictingSyncState = conflictingSyncState;
    }

    private static String buildMessage(String entityType, UUID entityId, SyncState conflictingSyncState) {
        StringBuilder sb = new StringBuilder("该对象已有一个操作正在进行中，请等待其完成后再试");
        if (entityType != null) {
            sb.append("（对象类型：").append(entityType);
            if (entityId != null) {
                sb.append("，对象ID：").append(entityId);
            }
            if (conflictingSyncState != null) {
                sb.append("，当前状态：").append(OperationMachineValues.toValue(conflictingSyncState));
            }
            sb.append("）");
        }
        return sb.toString();
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public UUID getConflictingOperationId() {
        return conflictingOperationId;
    }

    public SyncState getConflictingSyncState() {
        return conflictingSyncState;
    }
}
