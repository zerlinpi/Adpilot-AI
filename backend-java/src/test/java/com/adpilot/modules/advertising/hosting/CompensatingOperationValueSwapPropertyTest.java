package com.adpilot.modules.advertising.hosting;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link RollbackServiceImpl}'s compensating-operation value swap.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 36: Compensating operation swaps values
 *
 * <p><b>Validates: Requirements 10.2</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>For any effective + reversible operation with before/after values, the compensating
 *       operation's {@code beforeValue} equals the original's {@code afterValue}
 *       (round-tripped through the JSON codec).</li>
 *   <li>The compensating operation's {@code afterValue} equals the original's
 *       {@code beforeValue}.</li>
 *   <li>The compensating operation always uses {@link OperationSource#MANUAL} and links to the
 *       original via {@code parentOperationId}.</li>
 *   <li>Swapping twice (rolling back a rollback) returns to the original before/after values.</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 36: Compensating operation swaps values")
class CompensatingOperationValueSwapPropertyTest {

    // A real codec so JSON round-tripping matches production behavior exactly.
    private final OperationJsonCodec jsonCodec = new OperationJsonCodec(new ObjectMapper());

    // ── Property 1 & 2 & 3: single rollback swaps values + MANUAL source + parent link ──

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 36: Compensating operation swaps values
     *
     * <p><b>Validates: Requirements 10.2</b></p>
     *
     * <p>For any effective + reversible operation, the compensating operation swaps before/after
     * (round-tripped through the JSON codec), uses {@link OperationSource#MANUAL}, and sets
     * {@code parentOperationId} to the original operation's ID.</p>
     */
    @Property(tries = 200)
    @Label("compensating operation swaps before/after values, is MANUAL, and links to the original")
    void compensatingOperationSwapsValues(
            @ForAll("domainValues") Object beforeDomainValue,
            @ForAll("domainValues") Object afterDomainValue) {

        OperationMapper operationMapper = mock(OperationMapper.class);
        OperationService operationService = mock(OperationService.class);
        RollbackServiceImpl rollbackService = new RollbackServiceImpl(
                operationMapper, operationService, jsonCodec, new ReversibilityClassifier());

        // The entity stores values as canonical JSON text, exactly as the persistence layer does.
        String beforeJson = jsonCodec.toJson(beforeDomainValue);
        String afterJson = jsonCodec.toJson(afterDomainValue);
        OperationEntity original = buildEffectiveReversibleOperation(beforeJson, afterJson);

        when(operationMapper.selectById(original.getId().toString())).thenReturn(original);
        when(operationMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(operationService.createOperation(any(CreateOperationCommand.class)))
                .thenReturn(buildMockResult(UUID.randomUUID(), original.getStoreId()));

        RollbackResult result = rollbackService.rollback(original.getId());

        assertThat(result.isConfirmationRequired()).isFalse();

        CreateOperationCommand cmd = captureCreatedCommand(operationService);

        // Property 1: compensating.beforeValue == round-trip(original.afterValue).
        Object expectedBefore = jsonCodec.fromJson(afterJson, Object.class);
        // Property 2: compensating.afterValue == round-trip(original.beforeValue).
        Object expectedAfter = jsonCodec.fromJson(beforeJson, Object.class);

        assertThat(cmd.getBeforeValue())
                .as("compensating.beforeValue must equal original.afterValue (swapped)")
                .isEqualTo(expectedBefore);
        assertThat(cmd.getAfterValue())
                .as("compensating.afterValue must equal original.beforeValue (swapped)")
                .isEqualTo(expectedAfter);

        // Property 3: always MANUAL and linked to the original.
        assertThat(cmd.getOperationSource())
                .as("compensating operation must be MANUAL")
                .isEqualTo(OperationSource.MANUAL);
        assertThat(cmd.getParentOperationId())
                .as("compensating operation must link to the original via parentOperationId")
                .isEqualTo(original.getId());
    }

    // ── Property 4: rollback of a rollback returns to the original values ──────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 36: Compensating operation swaps values
     *
     * <p><b>Validates: Requirements 10.2</b></p>
     *
     * <p>Swapping twice (rolling back the compensating operation) returns to the original
     * before/after values — the swap is its own inverse.</p>
     */
    @Property(tries = 200)
    @Label("rolling back a rollback returns to the original before/after values")
    void doubleRollbackReturnsToOriginalValues(
            @ForAll("domainValues") Object beforeDomainValue,
            @ForAll("domainValues") Object afterDomainValue) {

        OperationMapper operationMapper = mock(OperationMapper.class);
        OperationService operationService = mock(OperationService.class);
        RollbackServiceImpl rollbackService = new RollbackServiceImpl(
                operationMapper, operationService, jsonCodec, new ReversibilityClassifier());

        String beforeJson = jsonCodec.toJson(beforeDomainValue);
        String afterJson = jsonCodec.toJson(afterDomainValue);
        OperationEntity original = buildEffectiveReversibleOperation(beforeJson, afterJson);

        when(operationMapper.selectById(original.getId().toString())).thenReturn(original);
        when(operationMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(operationService.createOperation(any(CreateOperationCommand.class)))
                .thenReturn(buildMockResult(UUID.randomUUID(), original.getStoreId()));

        // First rollback → compensating command (values swapped).
        rollbackService.rollback(original.getId());
        CreateOperationCommand firstCompensating = captureCreatedCommand(operationService);

        // Materialize the compensating operation as a persisted entity (values stored as JSON).
        OperationEntity compensatingEntity = buildEffectiveReversibleOperation(
                jsonCodec.toJson(firstCompensating.getBeforeValue()),
                jsonCodec.toJson(firstCompensating.getAfterValue()));

        OperationMapper mapper2 = mock(OperationMapper.class);
        OperationService service2 = mock(OperationService.class);
        RollbackServiceImpl rollbackService2 = new RollbackServiceImpl(
                mapper2, service2, jsonCodec, new ReversibilityClassifier());
        when(mapper2.selectById(compensatingEntity.getId().toString())).thenReturn(compensatingEntity);
        when(mapper2.selectList(any())).thenReturn(Collections.emptyList());
        when(service2.createOperation(any(CreateOperationCommand.class)))
                .thenReturn(buildMockResult(UUID.randomUUID(), compensatingEntity.getStoreId()));

        // Second rollback → should restore the original values.
        rollbackService2.rollback(compensatingEntity.getId());
        CreateOperationCommand secondCompensating = captureCreatedCommand(service2);

        Object originalBefore = jsonCodec.fromJson(beforeJson, Object.class);
        Object originalAfter = jsonCodec.fromJson(afterJson, Object.class);

        assertThat(secondCompensating.getBeforeValue())
                .as("rollback-of-rollback restores the original beforeValue")
                .isEqualTo(originalBefore);
        assertThat(secondCompensating.getAfterValue())
                .as("rollback-of-rollback restores the original afterValue")
                .isEqualTo(originalAfter);
    }

    // ── Generators ─────────────────────────────────────────────────────────────────────

    /**
     * Generates raw domain values that round-trip cleanly through the JSON codec as
     * {@code Object} (numbers, strings, booleans) — representative of bid/budget/state payloads.
     */
    @Provide
    Arbitrary<Object> domainValues() {
        Arbitrary<Object> integers = Arbitraries.integers().between(-100_000, 100_000).map(i -> (Object) i);
        Arbitrary<Object> doubles = Arbitraries.doubles().between(0.0, 10_000.0).ofScale(2).map(d -> (Object) d);
        Arbitrary<Object> strings = Arbitraries.of("ENABLED", "PAUSED", "ARCHIVED", "ACTIVE", "bid", "budget")
                .map(s -> (Object) s);
        Arbitrary<Object> booleans = Arbitraries.of(Boolean.TRUE, Boolean.FALSE).map(b -> (Object) b);
        return Arbitraries.oneOf(integers, doubles, strings, booleans);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────

    private OperationEntity buildEffectiveReversibleOperation(String beforeJson, String afterJson) {
        OperationEntity entity = new OperationEntity();
        entity.setId(UUID.randomUUID());
        entity.setStoreId(UUID.randomUUID());
        entity.setOperationSource(OperationMachineValues.toValue(OperationSource.AI_HOSTING));
        entity.setOperationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION));
        entity.setEntityType("keyword");
        entity.setEntityId(UUID.randomUUID());
        entity.setField("bid");
        entity.setSyncState(OperationMachineValues.toValue(SyncState.EFFECTIVE));
        entity.setReversible(true);
        entity.setBeforeValue(beforeJson);
        entity.setAfterValue(afterJson);
        entity.setAffectedCount(1);
        entity.setCreatedAt(LocalDateTime.now().minusHours(2));
        entity.setLogicalOperationId(UUID.randomUUID());
        return entity;
    }

    private OperationResult buildMockResult(UUID operationId, UUID storeId) {
        return OperationResult.builder()
                .operationId(operationId)
                .logicalOperationId(UUID.randomUUID())
                .storeId(storeId)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .syncState(SyncState.PENDING)
                .entityType("keyword")
                .entityId(UUID.randomUUID())
                .coalesced(false)
                .build();
    }

    private CreateOperationCommand captureCreatedCommand(OperationService operationService) {
        ArgumentCaptor<CreateOperationCommand> captor = ArgumentCaptor.forClass(CreateOperationCommand.class);
        org.mockito.Mockito.verify(operationService).createOperation(captor.capture());
        return captor.getValue();
    }
}
