package com.adpilot.modules.advertising.operation;

/**
 * Thrown when an {@link OperationRecordCommand} cannot be persisted as a complete, well-formed
 * {@code Operation_Record} — either because a required audit field is missing (Req 8.1) or because it
 * violates the {@code operationScope} ↔ state-field separation invariant of Requirements 3.7/3.8
 * (a {@code platform_mutation} must carry a {@link SyncState} and no {@link ExecutionStatus}; a
 * {@code local_configuration} must carry an {@link ExecutionStatus} and no {@link SyncState}).
 *
 * <p>This is an unchecked exception because a violation indicates a programming error in the calling
 * layer (it built an inconsistent command), not a recoverable runtime condition.</p>
 */
public class OperationRecordValidationException extends RuntimeException {

    public OperationRecordValidationException(String message) {
        super(message);
    }

    public OperationRecordValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
