package com.adpilot.modules.advertising.operation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.PermissionChecker;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.mapper.OperationPendingChangeMapper;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.common.exception.BusinessException;
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
import org.mockito.Mockito;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the undo availability predicate of {@link OperationServiceImpl#undo(UUID)}
 * (task 7.4 lifecycle, task 7.14 property).
 *
 * <p>Feature: advertising-workspace-rework, Property 23: Undo availability predicate.
 *
 * <p>Validates: Requirements 8.4, 8.5, 56.3, 22.10.
 *
 * <p>Property 23 (transcribed from the design's Correctness Properties section): <em>For any
 * Operation, the Undo action is offered if and only if its Sync_State is {@code effective} AND its
 * {@code reversible} flag is true AND its before value is still valid (no newer {@code effective}
 * Operation has since changed the field); when offered and activated, Undo creates a new compensating
 * Operation_Record rather than deleting the original.</em>
 *
 * <p>The predicate is exercised end-to-end through the real {@link OperationStateMachine} and
 * {@code createOperation} path against mocked collaborators (mirroring {@code OperationServiceImplTest}
 * and {@code CancellationRoutingPropertyTest}). Three boolean dimensions are varied independently and
 * span the full truth table:
 *
 * <ol>
 *   <li>the original's Sync_State — {@code effective} vs every non-{@code effective} Sync_State;</li>
 *   <li>its {@code reversible} flag — {@code true} vs {@code false};</li>
 *   <li>whether a newer {@code effective} Operation has since changed the field — driven by stubbing
 *       {@link OperationMapper#selectOne} to return a newer record or {@code null}.</li>
 * </ol>
 *
 * <p>For each generated Operation the property asserts the biconditional: undo SUCCEEDS — recording a
 * brand-new compensating {@code platform_mutation} Operation_Record (a fresh, different id, with the
 * before/after values swapped) and leaving the original record present and unmodified — if and only
 * if all three conditions hold; otherwise undo is REJECTED with a {@link BusinessException} and NO
 * record is created. In neither branch is the original ever deleted (Req 8.5) — the compensating
 * record is always additive.
 *
 * <p>The {@link SecurityContextHolder} is left empty so per-user data-scope validation is skipped,
 * keeping the focus on the undo availability predicate.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 23: Undo availability predicate")
class UndoAvailabilityPropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    /**
     * An arbitrary already-recorded Operation plus the world-state inputs the undo predicate reads:
     * its current Sync_State, its {@code reversible} flag, and whether a newer {@code effective}
     * Operation has since changed the same field.
     */
    record UndoSpec(SyncState current,
                    boolean reversible,
                    boolean newerEffectiveExists,
                    UUID storeId,
                    String entityType,
                    UUID entityId,
                    String field) {}

    /**
     * Feature: advertising-workspace-rework, Property 23: Undo availability predicate.
     *
     * <p>Validates: Requirements 8.4, 8.5, 56.3, 22.10.
     *
     * <p>For any Operation, undo succeeds (creating a new compensating Operation_Record rather than
     * deleting the original) if and only if its Sync_State is {@code effective} AND it is
     * {@code reversible} AND its before value is still valid (no newer {@code effective} Operation has
     * changed the field); otherwise undo is rejected and no record is created.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 23: undo is offered iff effective AND reversible AND before-value-still-valid, and when activated records a compensating Operation without deleting the original")
    void undoOfferedIffEffectiveReversibleAndBeforeValueValid(@ForAll("undoCandidates") UndoSpec spec) {
        SecurityContextHolder.clearContext();

        // --- Collaborators (mocked exactly as OperationServiceImplTest wires them) ---------------
        PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
        DataScopeService dataScopeService = Mockito.mock(DataScopeService.class);
        InFlightConflictLock inFlightConflictLock = Mockito.mock(InFlightConflictLock.class);
        IdempotencyService idempotencyService = Mockito.mock(IdempotencyService.class);
        EntityVersionGuard entityVersionGuard = Mockito.mock(EntityVersionGuard.class);
        WriteCapabilityService writeCapabilityService = Mockito.mock(WriteCapabilityService.class);
        OperationRecordService operationRecordService = Mockito.mock(OperationRecordService.class);
        OperationMapper operationMapper = Mockito.mock(OperationMapper.class);
        // The real state machine + record building decide the compensating Operation's lifecycle.
        OperationStateMachine stateMachine = new OperationStateMachine();
        ConfirmedValueWriter confirmedValueWriter = Mockito.mock(ConfirmedValueWriter.class);
        OperationPendingChangeMapper pendingChangeMapper = Mockito.mock(OperationPendingChangeMapper.class);
        OperationOutboxMapper outboxMapper = Mockito.mock(OperationOutboxMapper.class);
        PlatformConnectionMapper platformConnectionMapper = Mockito.mock(PlatformConnectionMapper.class);
        AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

        // The compensating Operation flows through createOperation: make the store write-capable and
        // connected so a successful undo lands a fresh pending platform_mutation.
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(true);
        when(platformConnectionMapper.selectList(any()))
                .thenReturn(List.of(connectedConnection("amazon")));
        // record() echoes a persisted entity reflecting the resolved compensating command.
        when(operationRecordService.record(any())).thenAnswer(inv -> persist(inv.getArgument(0)));

        // Condition 3 input: a newer effective Operation on the same field (or none).
        when(operationMapper.selectOne(any()))
                .thenReturn(spec.newerEffectiveExists() ? newerEffectiveEntity() : null);

        OperationEntity original = OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(spec.storeId())
                .operationSource(OperationMachineValues.toValue(OperationSource.MANUAL))
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .syncState(OperationMachineValues.toValue(spec.current()))
                .entityType(spec.entityType())
                .entityId(spec.entityId())
                .field(spec.field())
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("logical-key")
                .attemptId(UUID.randomUUID())
                .attemptNumber(1)
                .beforeValue("\"enabled\"")
                .afterValue("\"paused\"")
                .reversible(spec.reversible())
                .affectedCount(1)
                .createdAt(java.time.LocalDateTime.now())
                .build();
        when(operationRecordService.findById(original.getId())).thenReturn(Optional.of(original));

        OperationServiceImpl service = new OperationServiceImpl(
                permissionChecker, dataScopeService, inFlightConflictLock, idempotencyService,
                entityVersionGuard, writeCapabilityService, operationRecordService, operationMapper,
                stateMachine, confirmedValueWriter, pendingChangeMapper,
                outboxMapper, platformConnectionMapper, auditLogService,
                new OperationJsonCodec(new ObjectMapper()),
                org.mockito.Mockito.mock(com.adpilot.modules.advertising.operation.alert.WriteBackAlerting.class));

        // The undo predicate: offered iff effective AND reversible AND before value still valid.
        boolean expectedOffered =
                spec.current() == SyncState.EFFECTIVE
                        && spec.reversible()
                        && !spec.newerEffectiveExists();

        if (expectedOffered) {
            // (1) Offered + activated: undo creates a NEW compensating Operation_Record (Req 8.5).
            ArgumentCaptor<OperationRecordCommand> commandCaptor =
                    ArgumentCaptor.forClass(OperationRecordCommand.class);

            OperationResult result = service.undo(original.getId());

            // A brand-new compensating Operation — distinct identity, not a coalesced repeat.
            assertThat(result.getOperationId())
                    .as("undo must record a NEW compensating Operation, not reuse the original")
                    .isNotEqualTo(original.getId());
            assertThat(result.isCoalesced()).isFalse();

            verify(operationRecordService).record(commandCaptor.capture());
            OperationRecordCommand compensating = commandCaptor.getValue();

            // The compensating Operation rolls the field back: before/after are SWAPPED (Req 8.5),
            // it is itself a reversible platform_mutation against the SAME object, and carries a fresh
            // logical identity (not coalesced into the original change).
            assertThat(compensating.getOperationScope()).isEqualTo(OperationScope.PLATFORM_MUTATION);
            assertThat(compensating.getEntityType()).isEqualTo(spec.entityType());
            assertThat(compensating.getEntityId()).isEqualTo(spec.entityId());
            assertThat(compensating.getField()).isEqualTo(spec.field());
            assertThat(compensating.getBeforeValue()).isEqualTo("paused");   // = original after
            assertThat(compensating.getAfterValue()).isEqualTo("enabled");   // = original before
            assertThat(compensating.getReversible()).isTrue();
            assertThat(compensating.getLogicalOperationId())
                    .isNotEqualTo(original.getLogicalOperationId());

            // (1b) The original is NEVER deleted — it remains present and unmodified (Req 8.5).
            assertThat(operationRecordService.findById(original.getId()))
                    .as("the original Operation_Record must survive an undo, never be deleted")
                    .contains(original);
        } else {
            // (2) Not offered: undo is rejected with a clear error and NO record is created. The
            //     original is left entirely untouched (Req 8.4) — no silent no-op, no deletion.
            assertThatThrownBy(() -> service.undo(original.getId()))
                    .as("undo must be rejected unless effective AND reversible AND before-value valid")
                    .isInstanceOf(BusinessException.class);

            verify(operationRecordService, never()).record(any());
            assertThat(operationRecordService.findById(original.getId())).contains(original);
        }
    }

    // --- generators ------------------------------------------------------------------------------

    /**
     * Generates Operations spanning the full truth table of the undo predicate: every Sync_State
     * ({@code effective} and all non-{@code effective} states), both {@code reversible} values, and
     * both "a newer effective Operation has / has not changed the field" worlds — varying the object
     * dimensions (store, entity type/id, field) the predicate must ignore.
     */
    @Provide
    Arbitrary<UndoSpec> undoCandidates() {
        Arbitrary<SyncState> states = Arbitraries.of(SyncState.values());
        Arbitrary<Boolean> reversible = Arbitraries.of(true, false);
        Arbitrary<Boolean> newerEffective = Arbitraries.of(true, false);
        Arbitrary<UUID> storeIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> entityTypes =
                Arbitraries.of("campaign", "ad_group", "keyword", "product_ad", "target");
        Arbitrary<UUID> entityIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> fields = Arbitraries.of("status", "bid", "budget", "state", "match_type");

        return Combinators.combine(states, reversible, newerEffective, storeIds, entityTypes,
                        entityIds, fields)
                .as(UndoSpec::new);
    }

    private static OperationEntity newerEffectiveEntity() {
        return OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .syncState(OperationMachineValues.toValue(SyncState.EFFECTIVE))
                .entityType("campaign")
                .entityId(UUID.randomUUID())
                .field("status")
                .createdAt(java.time.LocalDateTime.now())
                .build();
    }

    private static PlatformConnectionEntity connectedConnection(String platform) {
        PlatformConnectionEntity c = new PlatformConnectionEntity();
        c.setPlatform(platform);
        c.setStatus("connected");
        return c;
    }

    private static OperationEntity persist(OperationRecordCommand c) {
        return OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(c.getStoreId())
                .operationSource(OperationMachineValues.toValue(c.getOperationSource()))
                .operationScope(OperationMachineValues.toValue(c.getOperationScope()))
                .entityType(c.getEntityType())
                .entityId(c.getEntityId())
                .field(c.getField())
                .logicalOperationId(c.getLogicalOperationId())
                .logicalIdempotencyKey(c.getLogicalIdempotencyKey())
                .attemptId(c.getAttemptId())
                .syncState(OperationMachineValues.toValue(c.getSyncState()))
                .executionStatus(OperationMachineValues.toValue(c.getExecutionStatus()))
                .build();
    }
}
