package com.adpilot.modules.advertising.operation;

/**
 * The terminal execution status carried by a {@code local_configuration} Operation (one whose
 * {@link OperationScope} is {@link OperationScope#LOCAL_CONFIGURATION}).
 *
 * <p>A {@code local_configuration} Operation is never submitted to Amazon and never carries a
 * {@link SyncState}; instead it carries exactly one of these statuses. A {@code platform_mutation}
 * Operation carries a {@link SyncState} and leaves its execution status null.</p>
 *
 * <p>Validates: Requirements 3.7, 3.8.</p>
 */
public enum ExecutionStatus {
    APPLIED,
    FAILED,
    CANCELLED
}
