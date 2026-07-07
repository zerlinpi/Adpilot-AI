package com.adpilot.modules.advertising.operation;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the in-flight conflict lock served by {@link InFlightConflictLock}.
 *
 * <p>Feature: advertising-workspace-rework, Property 16: In-flight conflict lock.
 *
 * <p>Validates: Requirements 5.6.
 *
 * <p>Property 16 (design): for any object that already has an Operation in any
 * {@link SyncState#UNSETTLED Unsettled_State}, a new conflicting Operation against the same object
 * is rejected; for an object whose existing Operations are all in settled states (or none exist), a
 * new Operation is allowed.
 *
 * <p>The {@link OperationMapper} is mocked so it behaves exactly like the production
 * {@code sync_state IN (<unsettled machine values>)} query: it returns the existing row only when
 * that row's persisted {@code sync_state} machine value is one of the values the lock placed into
 * the {@code IN (...)} predicate of its {@link LambdaQueryWrapper}. A generated "existing operation
 * state" (any {@link SyncState}, or none) drives the mock, and the test asserts the lock rejects
 * iff that state is an Unsettled_State.
 */
class InFlightConflictLockPropertyTest {

    static {
        // Register entity metadata so MyBatis-Plus can resolve lambda columns and materialise the
        // bound parameter values (including the IN-list) for query-wrapper introspection, without a
        // running Spring/MyBatis context.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OperationEntity.class);
    }

    /**
     * Feature: advertising-workspace-rework, Property 16: In-flight conflict lock.
     *
     * <p>Validates: Requirements 5.6.
     *
     * <p>For a generated existing-operation Sync_State (or none): if it is an Unsettled_State,
     * {@link InFlightConflictLock#rejectIfInFlight(String, UUID)} throws
     * {@link InFlightConflictException}; if it is settled or absent, it does not throw. The mock only
     * returns a row when its {@code sync_state} is in the lock's {@code IN (...)} predicate, exactly
     * mirroring the production query.
     */
    @Property(tries = 200)
    void rejectsIffExistingOperationIsInAnUnsettledState(
            @ForAll("entityTypes") String entityType,
            @ForAll("maybeExistingState") Optional<SyncState> existingState) {

        UUID entityId = UUID.randomUUID();

        OperationMapper operationMapper = Mockito.mock(OperationMapper.class);
        InFlightConflictLock lock = new InFlightConflictLock(operationMapper);

        // Model the single (optional) pre-existing Operation against this object as an object-level
        // (multi-field => field == null) row carrying the generated machine sync_state.
        OperationEntity existing = existingState
                .map(state -> OperationEntity.builder()
                        .id(UUID.randomUUID())
                        .storeId(UUID.randomUUID())
                        .operationSource(OperationMachineValues.toValue(OperationSource.MANUAL))
                        .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                        .entityType(entityType)
                        .entityId(entityId)
                        .field(null)
                        .logicalOperationId(UUID.randomUUID())
                        .logicalIdempotencyKey("key")
                        .attemptId(UUID.randomUUID())
                        .syncState(OperationMachineValues.toValue(state))
                        .build())
                .orElse(null);

        // The mock reproduces the production IN-query: a row is visible iff its persisted
        // sync_state machine value is among the values the lock put in the IN (...) predicate, and
        // its (entity_type, entity_id) match the wrapper's equality filters.
        when(operationMapper.selectOne(any())).thenAnswer(inv -> {
            if (existing == null) {
                return null;
            }
            List<Object> filterValues = filterValues(inv.getArgument(0));
            boolean stateQueried = filterValues.contains(existing.getSyncState());
            boolean entityMatches = filterValues.contains(existing.getEntityType())
                    && filterValues.contains(existing.getEntityId());
            return (stateQueried && entityMatches) ? existing : null;
        });

        boolean shouldReject = existingState.isPresent() && existingState.get().isUnsettled();

        if (shouldReject) {
            SyncState state = existingState.get();
            assertThatThrownBy(() -> lock.rejectIfInFlight(entityType, entityId))
                    .isInstanceOf(InFlightConflictException.class)
                    .satisfies(thrown -> {
                        InFlightConflictException ex = (InFlightConflictException) thrown;
                        assertThat(ex.getEntityType()).isEqualTo(entityType);
                        assertThat(ex.getEntityId()).isEqualTo(entityId);
                        assertThat(ex.getConflictingSyncState()).isEqualTo(state);
                    });
            // hasInFlightOperation agrees with the rejection decision.
            assertThat(lock.hasInFlightOperation(entityType, entityId)).isTrue();
        } else {
            assertThatCode(() -> lock.rejectIfInFlight(entityType, entityId))
                    .doesNotThrowAnyException();
            assertThat(lock.hasInFlightOperation(entityType, entityId)).isFalse();
        }
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Materialise the bound parameter values the lock placed into its {@link LambdaQueryWrapper}
     * (the {@code entity_type}/{@code entity_id} equality values and every {@code sync_state} value
     * in the {@code IN (...)} predicate), so the mock can filter exactly as the SQL would.
     */
    private static List<Object> filterValues(LambdaQueryWrapper<?> wrapper) {
        // Force SQL segment generation so MyBatis-Plus materialises the bound parameter values.
        wrapper.getTargetSql();
        Map<String, Object> pairs = wrapper.getParamNameValuePairs();
        return new ArrayList<>(pairs.values());
    }

    // --- generators --------------------------------------------------------

    /** Representative advertising object types the lock guards. */
    @Provide
    Arbitrary<String> entityTypes() {
        return Arbitraries.of("campaign", "keyword", "ad_group", "target", "negative_keyword", "product_ad");
    }

    /** Any Sync_State (settled or unsettled) or none — the full input space of an existing row. */
    @Provide
    Arbitrary<Optional<SyncState>> maybeExistingState() {
        return Arbitraries.of(SyncState.class).optional();
    }
}
