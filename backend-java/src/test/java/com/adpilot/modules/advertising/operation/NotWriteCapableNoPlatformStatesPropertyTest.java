package com.adpilot.modules.advertising.operation;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.PermissionChecker;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.mapper.OperationPendingChangeMapper;
import com.adpilot.modules.advertising.operation.callback.CallbackOutcome;
import com.adpilot.modules.advertising.operation.callback.OperationCallbackServiceImpl;
import com.adpilot.modules.advertising.operation.callback.PlatformCallback;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
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
import org.mockito.Mockito;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the not-write-capable invariant spanning Operation creation/lifecycle and
 * inbound platform callbacks (tasks 6.1 and 10.3).
 *
 * <p>Feature: advertising-workspace-rework, Property 12: Not-write-capable stores never enter
 * platform states.
 *
 * <p>Validates: Requirements 4.12, 53.2.
 *
 * <p>Property 12 (transcribed from the design's Correctness Properties section): <em>For any
 * Operation on a Store that is not write-capable, the Operation never enters {@code submitted},
 * {@code amazon-processing}, {@code effective}, {@code cancel_requested}, or
 * {@code reconciliation_required}, and inbound platform callbacks for that Store are not
 * processed.</em>
 *
 * <p>The two halves of the invariant are checked independently, each against mocked collaborators
 * with {@link WriteCapabilityService#isWriteCapable(UUID)} pinned to {@code false}:</p>
 *
 * <ol>
 *   <li><b>Creation never enters a platform state.</b> A {@code platform_mutation} created against a
 *       not-write-capable Store resolves to a local-tracked Sync_State ({@code local-only},
 *       {@code pending}, {@code awaiting_approval}, {@code cancelled}, or {@code superseded}) and
 *       NEVER to one of the platform-dependent states {@code submitted}, {@code amazon-processing},
 *       {@code effective}, {@code cancel_requested}, or {@code reconciliation_required}; no Outbox
 *       entry is written, so no platform submission is even queued (Req 4.12, 53.2).</li>
 *   <li><b>Inbound callbacks are not processed.</b> For an Operation whose Store is not
 *       write-capable, processing any inbound platform callback — regardless of the raw platform
 *       status it reports — yields {@link CallbackOutcome#IGNORED_NOT_WRITE_CAPABLE} and drives NO
 *       Sync_State transition (Req 53.2).</li>
 * </ol>
 */
@Label("Feature: advertising-workspace-rework, Property 12: Not-write-capable stores never enter platform states")
class NotWriteCapableNoPlatformStatesPropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    /**
     * The platform-dependent Sync_States that are unreachable for a not-write-capable Store
     * (Req 4.12, 53.2).
     */
    private static final Set<SyncState> FORBIDDEN_PLATFORM_STATES = EnumSet.of(
            SyncState.SUBMITTED,
            SyncState.AMAZON_PROCESSING,
            SyncState.EFFECTIVE,
            SyncState.CANCEL_REQUESTED,
            SyncState.RECONCILIATION_REQUIRED);

    /**
     * The local-tracked Sync_States in which a not-write-capable Store's Operations are kept
     * (Req 4.12).
     */
    private static final Set<SyncState> LOCAL_TRACKED_STATES = EnumSet.of(
            SyncState.LOCAL_ONLY,
            SyncState.PENDING,
            SyncState.AWAITING_APPROVAL,
            SyncState.CANCELLED,
            SyncState.SUPERSEDED);

    /** An arbitrary creation request for a {@code platform_mutation} against a single field. */
    record MutationSpec(UUID storeId,
                        OperationSource source,
                        String entityType,
                        UUID entityId,
                        String field,
                        String beforeValue,
                        String afterValue,
                        boolean approvalRequired) {}

    /** An arbitrary inbound callback targeting an Operation on a not-write-capable Store. */
    record CallbackSpec(UUID storeId,
                        SyncState currentState,
                        String platformStatus,
                        String submissionIdempotencyKey,
                        String platformReference) {}

    // ---------------------------------------------------------------------------------------------
    // Half 1 — a created Operation on a not-write-capable Store never enters a platform state.
    // ---------------------------------------------------------------------------------------------

    /**
     * Feature: advertising-workspace-rework, Property 12: Not-write-capable stores never enter
     * platform states.
     *
     * <p>Validates: Requirements 4.12, 53.2.
     *
     * <p>For any {@code platform_mutation} created against a not-write-capable Store, the resolved
     * Sync_State is a local-tracked state and never one of {@code submitted},
     * {@code amazon-processing}, {@code effective}, {@code cancel_requested}, or
     * {@code reconciliation_required}, and no platform submission (Outbox entry) is queued.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 12: a platform_mutation on a not-write-capable Store never enters a platform Sync_State")
    void createdOperationNeverEntersPlatformState(@ForAll("mutations") MutationSpec spec) {
        SecurityContextHolder.clearContext();

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

        // The Store is NOT write-capable — the prerequisite of Property 12.
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(false);
        when(inFlightConflictLock.findInFlightOperation(any(), any(), any())).thenReturn(Optional.empty());
        when(idempotencyService.findLogicalOperation(any(), any())).thenReturn(Optional.empty());
        when(operationRecordService.record(any())).thenAnswer(inv -> persist(inv.getArgument(0)));

        OperationServiceImpl service = new OperationServiceImpl(
                permissionChecker, dataScopeService, inFlightConflictLock, idempotencyService,
                entityVersionGuard, writeCapabilityService, operationRecordService, operationMapper,
                stateMachine, confirmedValueWriter, pendingChangeMapper,
                outboxMapper, platformConnectionMapper, auditLogService,
                new OperationJsonCodec(new ObjectMapper()),
                Mockito.mock(com.adpilot.modules.advertising.operation.alert.WriteBackAlerting.class));

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

        // The Operation never enters a platform-dependent Sync_State (Req 4.12, 53.2)...
        assertThat(result.getSyncState())
                .as("a not-write-capable Operation must never enter a platform Sync_State")
                .isNotIn(FORBIDDEN_PLATFORM_STATES);
        // ...and is kept in a local-tracked state.
        assertThat(result.getSyncState())
                .as("a not-write-capable Operation must stay in a local-tracked Sync_State")
                .isIn(LOCAL_TRACKED_STATES);

        // No platform submission is queued — no Outbox entry is ever written (Req 53.2).
        verify(outboxMapper, never()).insert(any());
    }

    // ---------------------------------------------------------------------------------------------
    // Half 2 — inbound platform callbacks for a not-write-capable Store are not processed.
    // ---------------------------------------------------------------------------------------------

    /**
     * Feature: advertising-workspace-rework, Property 12: Not-write-capable stores never enter
     * platform states.
     *
     * <p>Validates: Requirements 4.12, 53.2.
     *
     * <p>For any inbound platform callback correlated to an Operation whose Store is not
     * write-capable, the callback is not processed: the outcome is
     * {@link CallbackOutcome#IGNORED_NOT_WRITE_CAPABLE} and no Sync_State transition is driven,
     * regardless of the raw platform status the callback reports.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 12: an inbound platform callback for a not-write-capable Store is not processed")
    void inboundCallbackForNotWriteCapableStoreIsNotProcessed(@ForAll("callbacks") CallbackSpec spec) {
        OperationMapper operationMapper = Mockito.mock(OperationMapper.class);
        WriteCapabilityService writeCapabilityService = Mockito.mock(WriteCapabilityService.class);
        PlatformConnectionMapper platformConnectionMapper = Mockito.mock(PlatformConnectionMapper.class);
        OperationService operationService = Mockito.mock(OperationService.class);

        OperationEntity operation = OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(spec.storeId())
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .syncState(OperationMachineValues.toValue(spec.currentState()))
                .submissionIdempotencyKey(spec.submissionIdempotencyKey())
                .platformReference(spec.platformReference())
                .build();

        // The callback correlates to the Operation, but its Store is NOT write-capable.
        when(operationMapper.selectOne(any())).thenReturn(operation);
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(false);

        OperationCallbackServiceImpl callbackService = new OperationCallbackServiceImpl(
                operationMapper, writeCapabilityService, platformConnectionMapper, operationService,
                List.<PlatformWriteConnector>of());

        CallbackOutcome outcome = callbackService.process(new PlatformCallback(
                "amazon",
                spec.submissionIdempotencyKey(),
                spec.platformReference(),
                spec.platformStatus(),
                "callback for a not-write-capable store"));

        // The callback is not processed — it is ignored precisely because the Store is not
        // write-capable (Req 53.2).
        assertThat(outcome)
                .as("a callback for a not-write-capable Store must be ignored, not processed")
                .isEqualTo(CallbackOutcome.IGNORED_NOT_WRITE_CAPABLE);

        // No Sync_State transition is ever driven for the not-write-capable Store (Req 4.12, 53.2).
        verify(operationService, never()).transition(any(), any());
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
     * {@code approvalRequired} flag (so the invariant holds even when approval would otherwise apply).
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

    /**
     * Generates inbound callbacks targeting an Operation in any local-tracked state, carrying a wide
     * range of raw platform statuses — including ones that would otherwise map to {@code effective},
     * {@code failed}, {@code amazon-processing}, {@code cancelled}, or {@code reconciliation_required}
     * — proving the not-write-capable gate dominates the status mapping entirely.
     */
    @Provide
    Arbitrary<CallbackSpec> callbacks() {
        Arbitrary<UUID> storeIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<SyncState> currentStates = Arbitraries.of(LOCAL_TRACKED_STATES.toArray(new SyncState[0]));
        Arbitrary<String> statuses = Arbitraries.of(
                "SUCCESS", "COMPLETED", "EFFECTIVE", "ENABLED",
                "PENDING", "QUEUED", "SUBMITTED",
                "IN_PROGRESS", "PROCESSING",
                "FAILED", "ERROR", "REJECTED",
                "CANCELLED", "ABORTED",
                "WAT", "");
        Arbitrary<String> submissionKeys =
                Arbitraries.strings().alpha().numeric().ofMinLength(8).ofMaxLength(24);
        Arbitrary<String> platformReferences =
                Arbitraries.strings().alpha().numeric().ofMinLength(6).ofMaxLength(20);

        return Combinators.combine(storeIds, currentStates, statuses, submissionKeys, platformReferences)
                .as(CallbackSpec::new);
    }
}
