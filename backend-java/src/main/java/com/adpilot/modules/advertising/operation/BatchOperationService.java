package com.adpilot.modules.advertising.operation;

import java.util.List;

/**
 * A thin orchestration layer over {@link OperationService#createOperation(CreateOperationCommand)}
 * that applies the Bulk_Operation semantics of Requirement 6 (and Requirements 25.4, 36.2).
 *
 * <p>The contract is deliberately narrow: it does NOT re-implement permission, ownership,
 * idempotency, conflict, or persistence logic — each item is delegated to {@code createOperation},
 * which owns all of that. This component adds only the two batch-level concerns:</p>
 *
 * <ol>
 *   <li><b>Cross-store batch rejection</b> (Req 6.5, 25.4) — if the batch references records
 *       belonging to more than one Store, the WHOLE batch is rejected up front rather than silently
 *       excluding the cross-store records.</li>
 *   <li><b>Partial-success per-item attempts</b> (Req 6.6, 6.7, 6.8, 36.2) — when the batch is within
 *       a single Store, each item is attempted independently so that one item's failure does not
 *       abort the others, and a uniform per-item {@link BatchItemResult} list is returned reporting
 *       the record id, the creation result, and the failure reason where creation failed. The result
 *       represents <em>creation</em>, never platform application (Req 6.8).</li>
 * </ol>
 *
 * <p>Validates: Requirements 6.5, 6.6, 6.7, 6.8, 25.4, 36.2.</p>
 */
public interface BatchOperationService {

    /**
     * Create a batch of Operations with cross-store rejection and per-item partial-success semantics.
     *
     * @param commands the per-item change requests; must not be {@code null} or empty
     * @return a uniform per-item creation result, one entry per command, in the same order
     * @throws com.adpilot.common.exception.BusinessException if the batch is null/empty or references
     *         records belonging to more than one Store (the whole batch is rejected, Req 6.5)
     */
    List<BatchItemResult> createBatch(List<CreateOperationCommand> commands);
}
