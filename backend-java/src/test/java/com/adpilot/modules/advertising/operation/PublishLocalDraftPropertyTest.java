package com.adpilot.modules.advertising.operation;

import java.util.List;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link OperationServiceImpl#publishLocalDraft(UUID)} (task 7.7 lifecycle,
 * task 7.15 property).
 *
 * <p>Feature: advertising-workspace-rework, Property 30: Publishing a local-only draft creates a new
 * linked Operation.
 *
 * <p>Validates: Requirements 12.10.
 *
 * <p>Property 30 (transcribed from the design's Correctness Properties section): <em>For any terminal
 * {@code local-only} draft Operation, "submit to Amazon" creates a NEW {@code pending}
 * {@code platform_mutation} Operation carrying its own new {@code logicalOperationId} and a
 * {@code parentOperationId} referencing the original draft, and does not transition the original
 * {@code local-only} Operation.</em>
 *
 * <p>The publish is driven through the real {@link OperationServiceImpl#createOperation} pipeline
 * (the sole authority for routing a newly-created Operation) against mocked collaborators (mirroring
 * {@code OperationServiceImplTest} and {@code CancellationRoutingPropertyTest}). The Store is held
 * write-capable so the new Operation lands {@code pending} as the property's "submit to Amazon" path
 * requires; the other dimensions — entity type/id, target field, before/after draft values, and the
 * draft's Operation_Source — vary freely to prove the publish behaviour depends only on the draft
 * being a terminal {@code local-only} record. For each generated draft the property asserts:
 *
 * <ol>
 *   <li>a brand-new {@code platform_mutation} Operation lands in {@code pending} (Req 12.10);</li>
 *   <li>the new Operation is a distinct record from the draft (its own id);</li>
 *   <li>the recorded command carries its OWN new {@code logicalOperationId}, distinct from the
 *       draft's, and a {@code parentOperationId} referencing the original draft (Req 12.10);</li>
 *   <li>the draft's change facts (scope, source, entity, field, before/after) are copied, not
 *       swapped;</li>
 *   <li>the new Operation gets its own Outbox + pending overlay, while the original {@code local-only}
 *       draft is NEVER transitioned or mutated (no sync_state update is issued against it).</li>
 * </ol>
 *
 * <p>The {@link SecurityContextHolder} is left empty so per-user data-scope validation is skipped,
 * keeping the focus on the publish routing.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 30: Publishing a local-only draft creates a new linked Operation")
class PublishLocalDraftPropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    /** An arbitrary terminal {@code local-only} draft awaiting a publish-to-Amazon. */
    record DraftSpec(OperationSource source,
                     UUID storeId,
                     String entityType,
                     UUID entityId,
                     String field,
                     String beforeValue,
                     String afterValue) {}

    /**
     * Feature: advertising-workspace-rework, Property 30: Publishing a local-only draft creates a new
     * linked Operation.
     *
     * <p>Validates: Requirements 12.10.
     *
     * <p>For any terminal {@code local-only} draft, publishing it creates a NEW {@code pending}
     * {@code platform_mutation} Operation with its own new {@code logicalOperationId} and a
     * {@code parentOperationId} referencing the draft, and never transitions the original draft.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 30: publishing a local-only draft creates a new pending platform_mutation linked by parentOperationId without transitioning the draft")
    void publishCreatesNewLinkedOperationWithoutTransitioningDraft(@ForAll("localOnlyDrafts") DraftSpec spec) {
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
        // The real state machine is the sole authority for the resulting Sync_State.
        OperationStateMachine stateMachine = new OperationStateMachine();
        ConfirmedValueWriter confirmedValueWriter = Mockito.mock(ConfirmedValueWriter.class);
        OperationPendingChangeMapper pendingChangeMapper = Mockito.mock(OperationPendingChangeMapper.class);
        OperationOutboxMapper outboxMapper = Mockito.mock(OperationOutboxMapper.class);
        PlatformConnectionMapper platformConnectionMapper = Mockito.mock(PlatformConnectionMapper.class);
        AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

        // The Store is now write-capable, so the publish lands pending with an Outbox entry.
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(true);
        when(platformConnectionMapper.selectList(any())).thenReturn(List.of(connectedConnection("amazon")));
        // record() echoes a persisted entity reflecting the resolved command (mirrors the unit test).
        when(operationRecordService.record(any())).thenAnswer(inv -> persist(inv.getArgument(0)));

        // The terminal local-only draft to publish; its logicalOperationId must NOT be reused.
        UUID draftLogicalOperationId = UUID.randomUUID();
        OperationEntity draft = OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(spec.storeId())
                .operationSource(OperationMachineValues.toValue(spec.source()))
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .syncState(OperationMachineValues.toValue(SyncState.LOCAL_ONLY))
                .entityType(spec.entityType())
                .entityId(spec.entityId())
                .field(spec.field())
                .logicalOperationId(draftLogicalOperationId)
                .logicalIdempotencyKey("draft-logical-key")
                .attemptId(UUID.randomUUID())
                .attemptNumber(1)
                .beforeValue(spec.beforeValue())
                .afterValue(spec.afterValue())
                .reversible(true)
                .affectedCount(1)
                .createdAt(java.time.LocalDateTime.now())
                .build();
        when(operationRecordService.findById(draft.getId())).thenReturn(java.util.Optional.of(draft));

        OperationServiceImpl service = new OperationServiceImpl(
                permissionChecker, dataScopeService, inFlightConflictLock, idempotencyService,
                entityVersionGuard, writeCapabilityService, operationRecordService, operationMapper,
                stateMachine, confirmedValueWriter, pendingChangeMapper,
                outboxMapper, platformConnectionMapper, auditLogService,
                new OperationJsonCodec(new ObjectMapper()),
                org.mockito.Mockito.mock(com.adpilot.modules.advertising.operation.alert.WriteBackAlerting.class));

        ArgumentCaptor<OperationRecordCommand> commandCaptor =
                ArgumentCaptor.forClass(OperationRecordCommand.class);

        OperationResult result = service.publishLocalDraft(draft.getId());

        // (1) A brand-new platform_mutation lands in pending with its own lifecycle (Req 12.10).
        assertThat(result.getSyncState())
                .as("publishing a local-only draft must create a new pending Operation")
                .isEqualTo(SyncState.PENDING);
        assertThat(result.isCoalesced()).isFalse();

        // (2) The new Operation is a distinct record from the draft.
        assertThat(result.getOperationId())
                .as("the publish Operation must be a NEW record, not the draft")
                .isNotEqualTo(draft.getId());

        verify(operationRecordService).record(commandCaptor.capture());
        OperationRecordCommand cmd = commandCaptor.getValue();

        // (3) Its OWN new logicalOperationId (distinct from the draft's) and a parentOperationId
        //     referencing the original draft (Req 12.10).
        assertThat(cmd.getLogicalOperationId())
                .as("the publish Operation must carry its own new logicalOperationId")
                .isNotNull()
                .isNotEqualTo(draftLogicalOperationId);
        assertThat(cmd.getParentOperationId())
                .as("the publish Operation must link back to the original draft via parentOperationId")
                .isEqualTo(draft.getId());

        // (4) The draft's change facts are copied, not swapped (Req 12.10).
        assertThat(cmd.getOperationScope()).isEqualTo(OperationScope.PLATFORM_MUTATION);
        assertThat(cmd.getOperationSource()).isEqualTo(spec.source());
        assertThat(cmd.getEntityType()).isEqualTo(spec.entityType());
        assertThat(cmd.getEntityId()).isEqualTo(spec.entityId());
        assertThat(cmd.getField()).isEqualTo(spec.field());

        // (5) Its own Outbox + pending overlay are written; the original draft is NEVER transitioned
        //     or mutated — no sync_state update is issued against it (Req 12.10).
        verify(outboxMapper, times(1)).insert(any());
        verify(pendingChangeMapper, times(1)).insert(any());
        verify(operationMapper, never()).update(any(), any());
    }

    // --- generators ------------------------------------------------------------------------------

    /**
     * Generates terminal {@code local-only} drafts varying every dimension the publish behaviour must
     * ignore: the draft's Operation_Source, its Store, entity type/id, target field, and JSON-encoded
     * before/after values.
     */
    @Provide
    Arbitrary<DraftSpec> localOnlyDrafts() {
        Arbitrary<OperationSource> sources = Arbitraries.of(
                OperationSource.MANUAL,
                OperationSource.RECOMMENDATION,
                OperationSource.ONE_CLICK_OPTIMIZE,
                OperationSource.AI_HOSTING,
                OperationSource.CREATION);
        Arbitrary<UUID> storeIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> entityTypes =
                Arbitraries.of("campaign", "ad_group", "keyword", "product_ad", "target");
        Arbitrary<UUID> entityIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> fields = Arbitraries.of("status", "bid", "budget", "state", "match_type");
        // JSON-encoded scalar values, matching how the draft stores before/after (round-tripped
        // through the codec by publishLocalDraft).
        Arbitrary<String> beforeValues = Arbitraries.strings().alpha().numeric().ofMinLength(1)
                .ofMaxLength(12).map(v -> "\"" + v + "\"");
        Arbitrary<String> afterValues = Arbitraries.strings().alpha().numeric().ofMinLength(1)
                .ofMaxLength(12).map(v -> "\"" + v + "\"");

        return Combinators.combine(sources, storeIds, entityTypes, entityIds, fields,
                        beforeValues, afterValues)
                .as(DraftSpec::new);
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
