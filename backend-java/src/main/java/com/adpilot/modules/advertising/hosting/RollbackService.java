package com.adpilot.modules.advertising.hosting;

import java.util.UUID;

/**
 * Service interface for rolling back effective, reversible Operations by creating
 * a compensating Operation that swaps before/after values and routes through the
 * standard pipeline.
 *
 * <p>Rollback semantics (Requirements 10.1, 10.2, 10.4, 10.5, 10.6):</p>
 * <ul>
 *   <li>Only Operations in {@code effective} state with {@code reversible=true} can be
 *       rolled back.</li>
 *   <li>The compensating Operation uses {@code OperationSource.MANUAL} and sets
 *       {@code parentOperationId} to the original Operation's ID.</li>
 *   <li>Before/after values are swapped: compensating.beforeValue = original.afterValue,
 *       compensating.afterValue = original.beforeValue.</li>
 *   <li>If subsequent overlapping effective Operations exist on the same entity+field,
 *       a warning is returned requiring explicit confirmation.</li>
 *   <li>The compensating Operation routes through the standard pipeline (risk assessment,
 *       execution-mode, approval routing, outbox, verification).</li>
 * </ul>
 *
 * <p>Validates: Requirements 10.1, 10.2, 10.4, 10.5, 10.6.</p>
 */
public interface RollbackService {

    /**
     * Attempt to roll back an Operation.
     *
     * <p>If overlapping subsequent operations are detected, the result will contain a warning
     * with the conflicting operation IDs. The caller must then call
     * {@link #rollbackWithConfirmation(UUID)} to proceed despite the conflicts.</p>
     *
     * @param operationId the ID of the effective, reversible Operation to roll back
     * @return the rollback result (either a warning requiring confirmation, or the created
     *         compensating operation result)
     * @throws com.adpilot.common.exception.BusinessException if the operation is not found,
     *         not effective, or not reversible
     */
    RollbackResult rollback(UUID operationId);

    /**
     * Proceed with the rollback despite overlapping subsequent operations (explicit confirmation).
     *
     * <p>This bypasses the overlap warning check and creates the compensating Operation
     * regardless of subsequent changes to the same entity+field.</p>
     *
     * @param operationId the ID of the effective, reversible Operation to roll back
     * @return the rollback result containing the created compensating operation
     * @throws com.adpilot.common.exception.BusinessException if the operation is not found,
     *         not effective, or not reversible
     */
    RollbackResult rollbackWithConfirmation(UUID operationId);
}
