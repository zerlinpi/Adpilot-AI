package com.adpilot.modules.advertising.operation;

import com.adpilot.modules.advertising.entity.OperationEntity;

import java.util.Optional;
import java.util.UUID;

/**
 * The persistence building block for the {@code Operation_Record} (Req 8).
 *
 * <p>This is the narrow, focused responsibility of persisting a single, audit-complete
 * Operation_Record and reading it back. It validates and maps an {@link OperationRecordCommand} into
 * the {@code operations} table with ALL required audit fields and enforces the {@code operationScope}
 * ↔ state-field separation invariant (a {@code platform_mutation} carries a {@link SyncState} and no
 * {@link ExecutionStatus}; a {@code local_configuration} carries an {@link ExecutionStatus} and no
 * {@link SyncState}).</p>
 *
 * <p>It is deliberately NOT the {@code OperationService.createOperation} pipeline: it performs no
 * permission/scope checks, no in-flight conflict lock, no idempotency coalescing, no optimistic-lock
 * guard, no approval-threshold evaluation, no write-capability resolution, and writes no Outbox entry.
 * Those orchestration concerns compose this building block in a later step.</p>
 *
 * <p>Validates: Requirements 8.1, 3.8, 22.10, 49.9, 49.15.</p>
 */
public interface OperationRecordService {

    /**
     * Validate, map, and persist a complete Operation_Record.
     *
     * @param command the domain command; must not be {@code null}
     * @return the persisted entity, including its generated id and timestamps
     * @throws OperationRecordValidationException if a required audit field is missing or the
     *         scope/state separation invariant is violated
     */
    OperationEntity record(OperationRecordCommand command);

    /**
     * Load an Operation_Record by id.
     *
     * @param operationId the Operation id
     * @return the entity if present, otherwise {@link Optional#empty()}
     */
    Optional<OperationEntity> findById(UUID operationId);
}
