package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.OperationJsonCodec;
import com.adpilot.modules.advertising.operation.OperationMachineValues;
import com.adpilot.modules.advertising.operation.OperationResult;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationSource;
import com.adpilot.modules.advertising.operation.SyncState;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link RollbackService}.
 *
 * <p>Creates a compensating Operation that reverses an effective, reversible change by
 * swapping its before/after values. The compensating Operation is marked with
 * {@code OperationSource.MANUAL} and links to the original via {@code parentOperationId}.</p>
 *
 * <p>Before creating the compensating Operation, this service checks for subsequent
 * effective operations on the same entity+field. If any exist, the rollback requires
 * explicit confirmation from the user (the field's confirmed value may have changed
 * since the original operation took effect).</p>
 *
 * <p>The compensating Operation routes through the standard pipeline via
 * {@link OperationService#createOperation(CreateOperationCommand)}, which handles
 * risk assessment, execution-mode/approval routing, outbox submission, and
 * read-after-write verification.</p>
 *
 * <p>Validates: Requirements 10.1, 10.2, 10.4, 10.5, 10.6.</p>
 */
@Slf4j
@Service("hostingRollbackServiceImpl")
@RequiredArgsConstructor
public class RollbackServiceImpl implements RollbackService {

    private final OperationMapper operationMapper;
    private final OperationService operationService;
    private final OperationJsonCodec jsonCodec;
    private final ReversibilityClassifier reversibilityClassifier;

    @Override
    @Transactional
    public RollbackResult rollback(UUID operationId) {
        OperationEntity original = validateAndLoadOperation(operationId);

        // Check for overlapping subsequent operations on the same entity+field (Req 10.5).
        List<UUID> conflictingIds = findOverlappingSubsequentOperations(original);
        if (!conflictingIds.isEmpty()) {
            String warning = String.format(
                    "Rollback may conflict with %d subsequent operation(s) on the same entity+field: %s. "
                            + "These operations took effect after the original and the field value may have changed. "
                            + "Confirm to proceed with rollback despite potential conflicts.",
                    conflictingIds.size(),
                    conflictingIds.stream().map(UUID::toString).collect(Collectors.joining(", "))
            );
            log.warn("Rollback of operation {} requires confirmation: {} overlapping operations detected",
                    operationId, conflictingIds.size());
            return RollbackResult.requiresConfirmation(conflictingIds, warning);
        }

        // No conflicts — proceed directly.
        return executeRollback(original);
    }

    @Override
    @Transactional
    public RollbackResult rollbackWithConfirmation(UUID operationId) {
        OperationEntity original = validateAndLoadOperation(operationId);

        log.info("Rollback of operation {} proceeding with explicit confirmation (overlaps acknowledged)",
                operationId);

        return executeRollback(original);
    }

    // ---- Private helpers -----------------------------------------------------------------------

    /**
     * Load and validate the operation for rollback eligibility.
     *
     * @throws BusinessException if not found, not effective, or not reversible
     */
    private OperationEntity validateAndLoadOperation(UUID operationId) {
        if (operationId == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "operationId is required");
        }

        OperationEntity original = operationMapper.selectById(operationId.toString());
        if (original == null) {
            throw new BusinessException(404, "OPERATION_NOT_FOUND",
                    "Operation not found: " + operationId);
        }

        // Must be in effective state (Req 10.1).
        SyncState currentState = OperationMachineValues.toSyncState(original.getSyncState());
        if (currentState != SyncState.EFFECTIVE) {
            throw new BusinessException(409, "OPERATION_NOT_EFFECTIVE",
                    "Only effective operations can be rolled back. Current state: "
                            + OperationMachineValues.toValue(currentState));
        }

        // Must be reversible (Req 10.1, 10.3).
        if (!Boolean.TRUE.equals(original.getReversible())) {
            throw new BusinessException(409, "OPERATION_NOT_REVERSIBLE",
                    "This operation is not reversible and cannot be rolled back (changeType is non-reversible)");
        }

        return original;
    }

    /**
     * Find effective operations on the same entity+field that occurred after the original.
     * These represent "overlapping subsequent operations" per Requirement 10.5.
     *
     * @return a list of conflicting operation IDs (empty if no overlaps)
     */
    private List<UUID> findOverlappingSubsequentOperations(OperationEntity original) {
        LambdaQueryWrapper<OperationEntity> wrapper = new LambdaQueryWrapper<OperationEntity>()
                .eq(OperationEntity::getEntityType, original.getEntityType())
                .eq(OperationEntity::getEntityId, original.getEntityId())
                .eq(OperationEntity::getSyncState, OperationMachineValues.toValue(SyncState.EFFECTIVE))
                // Strictly newer than the original.
                .ne(OperationEntity::getId, original.getId().toString())
                .gt(OperationEntity::getCreatedAt, original.getCreatedAt());

        // Match on the same field: if the original targets a specific field, look for operations
        // on that same field or whole-object operations (null field).
        if (original.getField() != null && !original.getField().isBlank()) {
            wrapper.and(w -> w.eq(OperationEntity::getField, original.getField())
                    .or()
                    .isNull(OperationEntity::getField));
        }

        List<OperationEntity> overlapping = operationMapper.selectList(wrapper);
        return overlapping.stream()
                .map(OperationEntity::getId)
                .collect(Collectors.toList());
    }

    /**
     * Create and route the compensating Operation through the standard pipeline.
     */
    private RollbackResult executeRollback(OperationEntity original) {
        // Build the compensating Operation command (Req 10.2):
        // - before/after values SWAPPED
        // - OperationSource.MANUAL
        // - parentOperationId = original operation ID
        // - Routes through the standard pipeline (Req 10.4)
        CreateOperationCommand compensating = CreateOperationCommand.builder()
                .storeId(original.getStoreId())
                .operationSource(OperationSource.MANUAL)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType(original.getEntityType())
                .entityId(original.getEntityId())
                .field(original.getField())
                // SWAP: compensating before = original after; compensating after = original before.
                .beforeValue(jsonCodec.fromJson(original.getAfterValue(), Object.class))
                .afterValue(jsonCodec.fromJson(original.getBeforeValue(), Object.class))
                // The compensating Operation is itself reversible (the rollback can be rolled back).
                .reversible(Boolean.TRUE)
                .affectedCount(original.getAffectedCount())
                // Link to the original operation (Req 10.2).
                .parentOperationId(original.getId())
                .build();

        log.info("Creating compensating rollback Operation for original {} on {}#{} field={}",
                original.getId(), original.getEntityType(), original.getEntityId(),
                original.getField());

        OperationResult result = operationService.createOperation(compensating);

        log.info("Compensating rollback Operation {} created with syncState={} for original {}",
                result.getOperationId(), result.getSyncState(), original.getId());

        return RollbackResult.success(result);
    }
}
