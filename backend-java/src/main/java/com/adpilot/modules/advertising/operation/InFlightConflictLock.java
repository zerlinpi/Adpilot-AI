package com.adpilot.modules.advertising.operation;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The in-flight conflict lock (Requirement 5.6).
 *
 * <p>Before a new Operation is created against an advertising object, this lock checks the
 * {@code operations} table for any existing Operation against the same object whose
 * {@code sync_state} is in the {@link SyncState#UNSETTLED Unsettled_State} set. If one exists, the
 * new conflicting Operation is rejected with an {@link InFlightConflictException} that informs the
 * operator an Operation is already in progress.</p>
 *
 * <p>The query is driven by the {@code (entity_type, entity_id, sync_state)} index
 * ({@code idx_operations_entity_state}): it filters on {@code entity_type = ?}, {@code entity_id = ?}
 * and {@code sync_state IN (<unsettled machine values>)}, mapping each {@link SyncState} in
 * {@link SyncState#UNSETTLED} to its persisted machine value through
 * {@link OperationMachineValues#toValue(SyncState)} so the spellings (note the hyphen in
 * {@code amazon-processing}) match the stored column exactly.</p>
 *
 * <p>The optional {@code field} narrows the conflict to a single writable field when relevant;
 * when {@code null} the lock is object-level (any Unsettled_State Operation on the object conflicts),
 * which matches the Requirement 5.6 "same object" semantics.</p>
 *
 * <p>Validates: Requirements 5.6.</p>
 */
@Component
public class InFlightConflictLock {

    /**
     * The {@link SyncState#UNSETTLED} set rendered as the persisted lowercase machine values used in
     * the {@code sync_state IN (...)} predicate. Computed once; the set is immutable.
     */
    private static final List<String> UNSETTLED_MACHINE_VALUES = SyncState.UNSETTLED.stream()
            .map(OperationMachineValues::toValue)
            .collect(Collectors.toUnmodifiableList());

    private final OperationMapper operationMapper;

    public InFlightConflictLock(OperationMapper operationMapper) {
        this.operationMapper = operationMapper;
    }

    /**
     * @return {@code true} if the object {@code (entityType, entityId)} already has at least one
     *         Operation in an Unsettled_State.
     */
    public boolean hasInFlightOperation(String entityType, UUID entityId) {
        return findInFlightOperation(entityType, entityId, null).isPresent();
    }

    /**
     * @return {@code true} if the object {@code (entityType, entityId)} already has at least one
     *         Operation in an Unsettled_State; when {@code field} is non-null the check is narrowed
     *         to Operations against that field (plus whole-object multi-field Operations, whose
     *         {@code field} is {@code null}).
     */
    public boolean hasInFlightOperation(String entityType, UUID entityId, String field) {
        return findInFlightOperation(entityType, entityId, field).isPresent();
    }

    /**
     * Object-level guard: reject the new Operation if any Unsettled_State Operation exists against
     * {@code (entityType, entityId)}.
     *
     * @throws InFlightConflictException if a conflicting in-flight Operation exists (Req 5.6).
     */
    public void rejectIfInFlight(String entityType, UUID entityId) {
        rejectIfInFlight(entityType, entityId, null);
    }

    /**
     * Reject the new Operation if a conflicting Unsettled_State Operation exists against
     * {@code (entityType, entityId)} — narrowed to {@code field} when relevant.
     *
     * @throws InFlightConflictException if a conflicting in-flight Operation exists (Req 5.6).
     */
    public void rejectIfInFlight(String entityType, UUID entityId, String field) {
        findInFlightOperation(entityType, entityId, field).ifPresent(existing -> {
            throw new InFlightConflictException(
                    entityType,
                    entityId,
                    existing.getId(),
                    OperationMachineValues.toSyncState(existing.getSyncState()));
        });
    }

    /**
     * Query the {@code operations} table for the existing in-flight Operation against the object,
     * driving the {@code (entity_type, entity_id, sync_state)} index. Returns at most one row (the
     * presence of any conflict is sufficient to reject).
     *
     * @param entityType the advertising object type (e.g. {@code campaign}, {@code keyword}); required.
     * @param entityId   the object id; required.
     * @param field      optional writable field to narrow the conflict to; {@code null} for an
     *                   object-level check.
     */
    public Optional<OperationEntity> findInFlightOperation(String entityType, UUID entityId, String field) {
        if (entityType == null || entityId == null) {
            throw new IllegalArgumentException("entityType and entityId are required for the in-flight conflict lock");
        }
        // The Unsettled_State set is non-empty by definition, but guard against a misconfigured map.
        Set<String> values = Set.copyOf(UNSETTLED_MACHINE_VALUES);
        if (values.isEmpty()) {
            return Optional.empty();
        }

        LambdaQueryWrapper<OperationEntity> wrapper = new LambdaQueryWrapper<OperationEntity>()
                // index columns first so the planner can use idx_operations_entity_state.
                .eq(OperationEntity::getEntityType, entityType)
                .eq(OperationEntity::getEntityId, entityId)
                .in(OperationEntity::getSyncState, UNSETTLED_MACHINE_VALUES);

        if (field != null) {
            // A whole-object (multi-field) in-flight Operation has a null field and still conflicts
            // with a field-scoped change; otherwise narrow to the same field.
            wrapper.and(w -> w.eq(OperationEntity::getField, field)
                    .or()
                    .isNull(OperationEntity::getField));
        }

        wrapper.orderByAsc(OperationEntity::getCreatedAt)
                .last("LIMIT 1");

        return Optional.ofNullable(operationMapper.selectOne(wrapper));
    }
}
