package com.adpilot.modules.advertising.operation;

import java.util.UUID;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.PermissionChecker;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.OperationPendingChangeEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.mapper.OperationPendingChangeMapper;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the write-capability gate of the {@link OperationServiceImpl#createOperation}
 * pipeline (task 6.1).
 *
 * <p>Feature: advertising-workspace-rework, Property 2: Write-capability gating yields local-only.
 *
 * <p>Validates: Requirements 2.4, 3.2, 9.4, 12.5, 21.5, 53.3.
 *
 * <p>Property 2 (transcribed from the design's Correctness Properties section): <em>For any
 * {@code platform_mutation} Operation created against a Store that is not write-capable, the
 * Operation resolves to the terminal {@code local-only} Sync_State, no platform submission is
 * attempted, and the pending value is surfaced through the Pending_Overlay.</em>
 *
 * <p>The pipeline is driven against mocked collaborators (mirroring {@code OperationServiceImplTest})
 * with {@link WriteCapabilityService#isWriteCapable(UUID)} pinned to {@code false}. The generated
 * {@code platform_mutation} commands vary across Store, entity type/id, target field, before/after
 * values, Operation_Source, and the {@code approvalRequired} flag — proving the gate dominates every
 * one of those dimensions (a not-write-capable Store is local-only even when approval would otherwise
 * have applied). For each generated command the property asserts the three independent facts of
 * Property 2:
 *
 * <ol>
 *   <li>the resolved Sync_State is exactly the terminal {@code local-only} (and no
 *       {@code executionStatus} is carried, because this is a {@code platform_mutation});</li>
 *   <li>NO Outbox entry is written — no platform submission is attempted (Req 2.4, 9.4, 53.3);</li>
 *   <li>the pending value is surfaced through the Pending_Overlay — a pending-change record is
 *       persisted carrying the Operation's after value (Req 21.5).</li>
 * </ol>
 *
 * <p>The {@link SecurityContextHolder} is left empty so the acting user resolves to the reserved
 * system actor and per-user data-scope validation is skipped (Req 12.5), keeping the focus on the
 * routing and persistence wiring of the gate.
 */
@Label("Feature: advertising-workspace-rework, Property 2: Write-capability gating yields local-only")
class WriteCapabilityGatingPropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    /** An arbitrary creation request for a {@code platform_mutation} against a single field. */
    record MutationSpec(UUID storeId,
                        OperationSource source,
                        String entityType,
                        UUID entityId,
                        String field,
                        String beforeValue,
                        String afterValue,
                        boolean approvalRequired) {}

    /**
     * Feature: advertising-workspace-rework, Property 2: Write-capability gating yields local-only.
     *
     * <p>Validates: Requirements 2.4, 3.2, 9.4, 12.5, 21.5, 53.3.
     *
     * <p>For any {@code platform_mutation} created against a not-write-capable Store, the resolved
     * Sync_State is the terminal {@code local-only}, no Outbox entry is written (no submission is
     * attempted), and the pending after value is surfaced through the Pending_Overlay.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 2: a platform_mutation against a not-write-capable Store resolves local-only with no Outbox and a pending overlay")
    void notWriteCapablePlatformMutationResolvesLocalOnly(@ForAll("mutations") MutationSpec spec) {
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
        OperationStateMachine stateMachine = new OperationStateMachine();
        ConfirmedValueWriter confirmedValueWriter = Mockito.mock(ConfirmedValueWriter.class);
        OperationPendingChangeMapper pendingChangeMapper = Mockito.mock(OperationPendingChangeMapper.class);
        OperationOutboxMapper outboxMapper = Mockito.mock(OperationOutboxMapper.class);
        PlatformConnectionMapper platformConnectionMapper = Mockito.mock(PlatformConnectionMapper.class);
        AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

        // The Store is NOT write-capable — the gate under test.
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(false);
        // No prior in-flight or logical Operation: this is a fresh creation, not a coalesce/conflict.
        when(inFlightConflictLock.findInFlightOperation(any(), any(), any()))
                .thenReturn(java.util.Optional.empty());
        when(idempotencyService.findLogicalOperation(any(), any()))
                .thenReturn(java.util.Optional.empty());
        // record() echoes a persisted entity reflecting the resolved command.
        when(operationRecordService.record(any())).thenAnswer(inv -> persist(inv.getArgument(0)));

        OperationServiceImpl service = new OperationServiceImpl(
                permissionChecker, dataScopeService, inFlightConflictLock, idempotencyService,
                entityVersionGuard, writeCapabilityService, operationRecordService, operationMapper,
                stateMachine, confirmedValueWriter, pendingChangeMapper,
                outboxMapper, platformConnectionMapper, auditLogService,
                new OperationJsonCodec(new ObjectMapper()),
                org.mockito.Mockito.mock(com.adpilot.modules.advertising.operation.alert.WriteBackAlerting.class));

        OperationResult result = service.createOperation(
                CreateOperationCommand.builder()
                        .storeId(spec.storeId())
                        .operationSource(spec.source())
                        .operationScope(OperationScope.PLATFORM_MUTATION)
                        .entityType(spec.entityType())
                        .entityId(spec.entityId())
                        .field(spec.field())
                        .beforeValue(spec.beforeValue())
                        .afterValue(spec.afterValue())
                        .approvalRequired(spec.approvalRequired())
                        .logicalIdempotencyKey(UUID.randomUUID().toString())
                        .build());

        // (1) Resolves to the terminal local-only Sync_State — even though approvalRequired may be
        //     set, the not-write-capable gate dominates (Req 2.4, 3.2, 53.3).
        assertThat(result.getSyncState())
                .as("a not-write-capable platform_mutation must resolve to terminal local-only")
                .isEqualTo(SyncState.LOCAL_ONLY);
        assertThat(result.getExecutionStatus())
                .as("a platform_mutation never carries an executionStatus")
                .isNull();

        // (2) No platform submission is attempted: no Outbox entry is written (Req 2.4, 9.4, 53.3).
        verify(outboxMapper, never()).insert(any());

        // (3) The pending value is surfaced through the Pending_Overlay: a pending-change record is
        //     persisted carrying the Operation's after value (Req 21.5).
        ArgumentCaptor<OperationPendingChangeEntity> pendingCaptor =
                ArgumentCaptor.forClass(OperationPendingChangeEntity.class);
        verify(pendingChangeMapper, times(1)).insert(pendingCaptor.capture());
        OperationPendingChangeEntity pending = pendingCaptor.getValue();
        assertThat(pending.getEntityType()).isEqualTo(spec.entityType());
        assertThat(pending.getEntityId()).isEqualTo(spec.entityId());
        assertThat(pending.getField()).isEqualTo(spec.field());
        assertThat(pending.getAfterValue())
                .as("the Pending_Overlay must surface the Operation's pending after value")
                .contains(spec.afterValue());
    }

    // --- helpers ---------------------------------------------------------------------------------

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

    // --- generators ------------------------------------------------------------------------------

    /**
     * Generates {@code platform_mutation} requests that vary across every dimension the gate must
     * ignore: Store, Operation_Source, entity type/id, target field, before/after values, and the
     * {@code approvalRequired} flag.
     */
    @Provide
    Arbitrary<MutationSpec> mutations() {
        Arbitrary<UUID> storeIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<OperationSource> sources = Arbitraries.of(OperationSource.class);
        Arbitrary<String> entityTypes =
                Arbitraries.of("campaign", "ad_group", "keyword", "product_ad", "target");
        Arbitrary<UUID> entityIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> fields = Arbitraries.of("status", "bid", "budget", "state", "match_type");
        Arbitrary<String> beforeValues =
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> afterValues =
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
        Arbitrary<Boolean> approval = Arbitraries.of(true, false);

        return Combinators.combine(storeIds, sources, entityTypes, entityIds, fields,
                        beforeValues, afterValues, approval)
                .as(MutationSpec::new);
    }
}
