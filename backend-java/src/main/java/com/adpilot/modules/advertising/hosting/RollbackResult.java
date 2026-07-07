package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.operation.OperationResult;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of a rollback attempt.
 *
 * <p>A rollback attempt can result in one of two outcomes:</p>
 * <ul>
 *   <li><b>Warning</b>: overlapping subsequent operations were detected on the same entity+field.
 *       The result carries {@code confirmationRequired=true}, the list of conflicting operation IDs,
 *       and no compensating operation. The caller must explicitly confirm to proceed.</li>
 *   <li><b>Success</b>: the compensating Operation was created and routed through the standard
 *       pipeline. The result carries the {@link OperationResult} of the new compensating operation.</li>
 * </ul>
 *
 * <p>Validates: Requirements 10.4, 10.5.</p>
 */
@Value
@Builder
public class RollbackResult {

    /**
     * Whether explicit confirmation is required before proceeding.
     * When {@code true}, overlapping subsequent operations were detected.
     */
    boolean confirmationRequired;

    /**
     * The IDs of overlapping subsequent operations that conflict with the rollback.
     * Present only when {@code confirmationRequired} is {@code true}.
     */
    List<UUID> conflictingOperationIds;

    /**
     * A human-readable warning message when overlapping operations are detected.
     */
    String warningMessage;

    /**
     * The result of the compensating Operation creation.
     * Present only when the rollback was executed (either no overlaps, or confirmed).
     */
    OperationResult operationResult;

    /**
     * Create a result indicating that confirmation is required due to overlapping operations.
     */
    public static RollbackResult requiresConfirmation(List<UUID> conflictingIds, String warning) {
        return RollbackResult.builder()
                .confirmationRequired(true)
                .conflictingOperationIds(conflictingIds)
                .warningMessage(warning)
                .build();
    }

    /**
     * Create a result indicating that the rollback was successfully executed.
     */
    public static RollbackResult success(OperationResult operationResult) {
        return RollbackResult.builder()
                .confirmationRequired(false)
                .operationResult(operationResult)
                .build();
    }
}
