package com.adpilot.modules.advertising.operation;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link OperationServiceImpl#createOperation} pipeline (task 6.1).
 *
 * <p>These exercise the ordered pipeline and the single-transaction routing decisions against mocked
 * building blocks: a write-capable {@code platform_mutation} with no approval lands in
 * {@code pending} and writes an Outbox entry; a not-write-capable {@code platform_mutation} resolves
 * to the terminal {@code local-only} state with NO Outbox; a {@code local_configuration} carries an
 * {@code executionStatus} of {@code applied}, no Sync_State, no pending-change, and no Outbox; a
 * repeated activation coalesces; and a genuinely conflicting in-flight Operation is rejected.
 *
 * <p>The {@link SecurityContextHolder} is left empty so the acting user resolves to the reserved
 * system actor and per-user data-scope validation is skipped — keeping the focus on the pipeline's
 * routing and persistence wiring.
 *
 * <p>Validates: Requirements 2.4, 3.2, 3.7, 6.1, 6.2, 5.3, 5.6, 9.4, 53.3.
 */
class OperationServiceImplTest {

    private PermissionChecker permissionChecker;
    private DataScopeService dataScopeService;
    private InFlightConflictLock inFlightConflictLock;
    private IdempotencyService idempotencyService;
    private EntityVersionGuard entityVersionGuard;
    private WriteCapabilityService writeCapabilityService;
    private OperationRecordService operationRecordService;
    private OperationMapper operationMapper;
    private OperationStateMachine stateMachine;
    private ConfirmedValueWriter confirmedValueWriter;
    private OperationPendingChangeMapper pendingChangeMapper;
    private OperationOutboxMapper outboxMapper;
    private PlatformConnectionMapper platformConnectionMapper;
    private AuditLogService auditLogService;

    private OperationServiceImpl service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        permissionChecker = mock(PermissionChecker.class);
        dataScopeService = mock(DataScopeService.class);
        inFlightConflictLock = mock(InFlightConflictLock.class);
        idempotencyService = mock(IdempotencyService.class);
        entityVersionGuard = mock(EntityVersionGuard.class);
        writeCapabilityService = mock(WriteCapabilityService.class);
        operationRecordService = mock(OperationRecordService.class);
        operationMapper = mock(OperationMapper.class);
        stateMachine = new OperationStateMachine();
        confirmedValueWriter = mock(ConfirmedValueWriter.class);
        pendingChangeMapper = mock(OperationPendingChangeMapper.class);
        outboxMapper = mock(OperationOutboxMapper.class);
        platformConnectionMapper = mock(PlatformConnectionMapper.class);
        auditLogService = mock(AuditLogService.class);

        service = new OperationServiceImpl(
                permissionChecker, dataScopeService, inFlightConflictLock, idempotencyService,
                entityVersionGuard, writeCapabilityService, operationRecordService, operationMapper,
                stateMachine, confirmedValueWriter, pendingChangeMapper,
                outboxMapper, platformConnectionMapper, auditLogService, new OperationJsonCodec(new ObjectMapper()),
                org.mockito.Mockito.mock(com.adpilot.modules.advertising.operation.alert.WriteBackAlerting.class));

        // record() echoes a persisted entity reflecting the resolved command.
        when(operationRecordService.record(any())).thenAnswer(inv -> persist(inv.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void writeCapablePlatformMutationLandsPendingAndWritesOutbox() {
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(true);
        when(platformConnectionMapper.selectList(any())).thenReturn(List.of(connectedConnection("amazon")));

        OperationResult result = service.createOperation(platformMutation().build());

        assertThat(result.getSyncState()).isEqualTo(SyncState.PENDING);
        assertThat(result.getExecutionStatus()).isNull();
        assertThat(result.isCoalesced()).isFalse();
        verify(outboxMapper, times(1)).insert(any());
        verify(pendingChangeMapper, times(1)).insert(any());
        verify(auditLogService, times(1)).createLog(any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void writeCapableWithApprovalLandsAwaitingApprovalWithoutOutbox() {
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(true);

        OperationResult result = service.createOperation(
                platformMutation().approvalRequired(true).build());

        assertThat(result.getSyncState()).isEqualTo(SyncState.AWAITING_APPROVAL);
        verify(outboxMapper, never()).insert(any());
        verify(pendingChangeMapper, times(1)).insert(any());
    }

    @Test
    void notWriteCapablePlatformMutationResolvesLocalOnlyWithoutOutbox() {
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(false);

        OperationResult result = service.createOperation(platformMutation().build());

        assertThat(result.getSyncState()).isEqualTo(SyncState.LOCAL_ONLY);
        verify(outboxMapper, never()).insert(any());
        verify(pendingChangeMapper, times(1)).insert(any());
    }

    @Test
    void localConfigurationCarriesExecutionStatusAndNeverWritesOutbox() {
        OperationResult result = service.createOperation(
                CreateOperationCommand.builder()
                        .storeId(UUID.randomUUID())
                        .operationSource(OperationSource.MANUAL)
                        .operationScope(OperationScope.LOCAL_CONFIGURATION)
                        .entityType("ai_personality")
                        .entityId(UUID.randomUUID())
                        .field("personality")
                        .afterValue("aggressive")
                        .build());

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.APPLIED);
        assertThat(result.getSyncState()).isNull();
        verify(outboxMapper, never()).insert(any());
        verify(pendingChangeMapper, never()).insert(any());
        verify(writeCapabilityService, never()).isWriteCapable(any());
    }

    @Test
    void repeatedActivationCoalescesToExistingLogicalOperation() {
        UUID storeId = UUID.randomUUID();
        OperationEntity existing = OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .syncState(OperationMachineValues.toValue(SyncState.PENDING))
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("dupe-key")
                .entityType("campaign")
                .entityId(UUID.randomUUID())
                .build();
        when(idempotencyService.findLogicalOperation(storeId, "dupe-key")).thenReturn(Optional.of(existing));

        OperationResult result = service.createOperation(
                platformMutation().storeId(storeId).logicalIdempotencyKey("dupe-key").build());

        assertThat(result.isCoalesced()).isTrue();
        assertThat(result.getOperationId()).isEqualTo(existing.getId());
        verify(operationRecordService, never()).record(any());
        verify(outboxMapper, never()).insert(any());
    }

    @Test
    void conflictingInFlightOperationIsRejected() {
        OperationEntity inFlight = OperationEntity.builder()
                .id(UUID.randomUUID())
                .syncState(OperationMachineValues.toValue(SyncState.SUBMITTED))
                .logicalIdempotencyKey("other-key")
                .build();
        when(inFlightConflictLock.findInFlightOperation(anyString(), any(), any()))
                .thenReturn(Optional.of(inFlight));

        assertThatThrownBy(() -> service.createOperation(
                platformMutation().logicalIdempotencyKey("my-key").build()))
                .isInstanceOf(InFlightConflictException.class);

        verify(operationRecordService, never()).record(any());
    }

    // --- cancel() routing by submission state (task 7.2) -----------------------------------------

    @Test
    void cancelOfPendingOperationTransitionsDirectlyToCancelled() {
        OperationEntity op = platformMutationEntity(SyncState.PENDING);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        OperationResult result = service.cancel(op.getId());

        assertThat(result.getSyncState()).isEqualTo(SyncState.CANCELLED);
        // Confirmed value is never touched on cancel (Req 4.7).
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
    }

    @Test
    void cancelOfAwaitingApprovalOperationTransitionsDirectlyToCancelled() {
        OperationEntity op = platformMutationEntity(SyncState.AWAITING_APPROVAL);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        OperationResult result = service.cancel(op.getId());

        assertThat(result.getSyncState()).isEqualTo(SyncState.CANCELLED);
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
    }

    @Test
    void cancelOfSubmittedOperationRoutesToCancelRequestedNeverDirectlyCancelled() {
        OperationEntity op = platformMutationEntity(SyncState.SUBMITTED);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        OperationResult result = service.cancel(op.getId());

        assertThat(result.getSyncState()).isEqualTo(SyncState.CANCEL_REQUESTED);
        assertThat(result.getSyncState()).isNotEqualTo(SyncState.CANCELLED);
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
    }

    @Test
    void cancelOfAmazonProcessingOperationRoutesToCancelRequested() {
        OperationEntity op = platformMutationEntity(SyncState.AMAZON_PROCESSING);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        OperationResult result = service.cancel(op.getId());

        assertThat(result.getSyncState()).isEqualTo(SyncState.CANCEL_REQUESTED);
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
    }

    @Test
    void cancelOfSettledOperationIsRejected() {
        OperationEntity op = platformMutationEntity(SyncState.EFFECTIVE);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        assertThatThrownBy(() -> service.cancel(op.getId()))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("不支持取消");
    }

    @Test
    void cancelOfMissingOperationIsRejected() {
        UUID missing = UUID.randomUUID();
        when(operationRecordService.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(missing))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("未找到");
    }

    // --- supersede() routing by submission state (task 7.5) --------------------------------------

    @Test
    void supersedeOfPendingOperationTransitionsDirectlyToSuperseded() {
        OperationEntity op = platformMutationEntity(SyncState.PENDING);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        OperationResult result = service.supersede(op.getId());

        assertThat(result.getSyncState()).isEqualTo(SyncState.SUPERSEDED);
        // Confirmed value is never touched on supersession (Req 4.11).
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
    }

    @Test
    void supersedeOfAwaitingApprovalOperationTransitionsDirectlyToSuperseded() {
        OperationEntity op = platformMutationEntity(SyncState.AWAITING_APPROVAL);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        OperationResult result = service.supersede(op.getId());

        assertThat(result.getSyncState()).isEqualTo(SyncState.SUPERSEDED);
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
    }

    @Test
    void supersedeOfSubmittedOperationRoutesToCancelRequestedNeverDirectlySuperseded() {
        OperationEntity op = platformMutationEntity(SyncState.SUBMITTED);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        OperationResult result = service.supersede(op.getId());

        assertThat(result.getSyncState()).isEqualTo(SyncState.CANCEL_REQUESTED);
        assertThat(result.getSyncState()).isNotEqualTo(SyncState.SUPERSEDED);
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
    }

    @Test
    void supersedeOfAmazonProcessingOperationRoutesToCancelRequested() {
        OperationEntity op = platformMutationEntity(SyncState.AMAZON_PROCESSING);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        OperationResult result = service.supersede(op.getId());

        assertThat(result.getSyncState()).isEqualTo(SyncState.CANCEL_REQUESTED);
        assertThat(result.getSyncState()).isNotEqualTo(SyncState.SUPERSEDED);
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
    }

    @Test
    void supersedeOfSettledOperationIsRejected() {
        OperationEntity op = platformMutationEntity(SyncState.EFFECTIVE);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        assertThatThrownBy(() -> service.supersede(op.getId()))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("不支持替代");
    }

    @Test
    void supersedeOfMissingOperationIsRejected() {
        UUID missing = UUID.randomUUID();
        when(operationRecordService.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.supersede(missing))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("未找到");
    }

    // --- retry() creates a fresh attempt without mutating the original (task 7.3) ----------------

    @Test
    void retryOfFailedOperationCreatesFreshPendingAttemptUnderSameLogicalIds() {
        when(idempotencyService.newSubmissionIdempotencyKey()).thenReturn("new-submission-key");
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(true);
        when(platformConnectionMapper.selectList(any())).thenReturn(List.of(connectedConnection("amazon")));

        UUID logicalOperationId = UUID.randomUUID();
        OperationEntity failed = OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .operationSource(OperationMachineValues.toValue(OperationSource.MANUAL))
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .syncState(OperationMachineValues.toValue(SyncState.FAILED))
                .entityType("campaign")
                .entityId(UUID.randomUUID())
                .field("status")
                .logicalOperationId(logicalOperationId)
                .logicalIdempotencyKey("logical-key")
                .attemptId(UUID.randomUUID())
                .submissionIdempotencyKey("old-submission-key")
                .attemptNumber(1)
                .beforeValue("\"enabled\"")
                .afterValue("\"paused\"")
                .reversible(true)
                .affectedCount(1)
                .statusReason("platform rejected")
                .build();
        when(operationRecordService.findById(failed.getId())).thenReturn(Optional.of(failed));

        ArgumentCaptor<OperationRecordCommand> commandCaptor =
                ArgumentCaptor.forClass(OperationRecordCommand.class);

        OperationResult result = service.retry(failed.getId());

        // A new pending attempt was created.
        assertThat(result.getSyncState()).isEqualTo(SyncState.PENDING);
        assertThat(result.isCoalesced()).isFalse();

        verify(operationRecordService).record(commandCaptor.capture());
        OperationRecordCommand cmd = commandCaptor.getValue();

        // Same logical identity, fresh attempt identity (Req 4.2).
        assertThat(cmd.getLogicalOperationId()).isEqualTo(logicalOperationId);
        assertThat(cmd.getLogicalIdempotencyKey()).isEqualTo("logical-key");
        assertThat(cmd.getAttemptId()).isNotNull().isNotEqualTo(failed.getAttemptId());
        assertThat(cmd.getSubmissionIdempotencyKey()).isEqualTo("new-submission-key");
        assertThat(cmd.getAttemptNumber()).isEqualTo(2);
        assertThat(cmd.getSyncState()).isEqualTo(SyncState.PENDING);
        assertThat(cmd.getExecutionStatus()).isNull();

        // Copied entity/field/before/after/scope/source (Req 7.8).
        assertThat(cmd.getStoreId()).isEqualTo(failed.getStoreId());
        assertThat(cmd.getOperationScope()).isEqualTo(OperationScope.PLATFORM_MUTATION);
        assertThat(cmd.getOperationSource()).isEqualTo(OperationSource.MANUAL);
        assertThat(cmd.getEntityType()).isEqualTo("campaign");
        assertThat(cmd.getEntityId()).isEqualTo(failed.getEntityId());
        assertThat(cmd.getField()).isEqualTo("status");
        assertThat(cmd.getBeforeValue()).isEqualTo("enabled");
        assertThat(cmd.getAfterValue()).isEqualTo("paused");

        // The new pending attempt drives re-submission via a fresh Outbox entry and overlay.
        verify(outboxMapper, times(1)).insert(any());
        verify(pendingChangeMapper, times(1)).insert(any());

        // The original failed record is left UNCHANGED (no sync_state update issued against it).
        verify(operationMapper, never()).update(any(), any());
    }

    @Test
    void retryOfNonFailedOperationIsRejected() {
        OperationEntity op = platformMutationEntity(SyncState.PENDING);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        assertThatThrownBy(() -> service.retry(op.getId()))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("failed");

        verify(operationRecordService, never()).record(any());
    }

    @Test
    void retryOfMissingOperationIsRejected() {
        UUID missing = UUID.randomUUID();
        when(operationRecordService.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retry(missing))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("未找到");
    }

    // --- approve() / reject() of an awaiting_approval Operation (task 7.6) -----------------------

    @Test
    void approveOfAwaitingApprovalTransitionsToPendingAndWritesOutbox() {
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(true);
        when(platformConnectionMapper.selectList(any())).thenReturn(List.of(connectedConnection("amazon")));

        OperationEntity op = platformMutationEntity(SyncState.AWAITING_APPROVAL);
        op.setAfterValue("\"paused\"");
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        OperationResult result = service.approve(op.getId());

        // awaiting_approval -> pending (Req 7.7, 24.2).
        assertThat(result.getSyncState()).isEqualTo(SyncState.PENDING);
        // The now-approved Operation becomes submittable: its Outbox entry is written so the worker
        // can submit it to the platform.
        verify(outboxMapper, times(1)).insert(any());
        // Submission never touches the confirmed value.
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
    }

    // --- A8: state-transition forensic audit (additive, best-effort) ----------------------------

    @Test
    void approveOfAwaitingApprovalWritesOperationTransitionAudit() {
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(true);
        when(platformConnectionMapper.selectList(any())).thenReturn(List.of(connectedConnection("amazon")));

        OperationEntity op = platformMutationEntity(SyncState.AWAITING_APPROVAL);
        op.setAfterValue("\"paused\"");
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        service.approve(op.getId());

        // The shared applyTransition chokepoint writes exactly one forensic OPERATION_TRANSITION
        // audit for the awaiting_approval -> pending approve, keyed to the "operation" entity.
        verify(auditLogService, times(1)).createLog(
                any(), any(), eq("OPERATION_TRANSITION"), eq("operation"), eq(op.getId()), any());
    }

    @Test
    void approveIsRejectedWhenStoreNoLongerWriteCapable() {
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(false);

        OperationEntity op = platformMutationEntity(SyncState.AWAITING_APPROVAL);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        assertThatThrownBy(() -> service.approve(op.getId()))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("不可写入");

        // No transition into pending and no Outbox entry (Req 4.12).
        verify(outboxMapper, never()).insert(any());
        verify(operationMapper, never()).update(any(), any());
    }

    @Test
    void approveOfNonAwaitingApprovalOperationIsRejected() {
        OperationEntity op = platformMutationEntity(SyncState.PENDING);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        assertThatThrownBy(() -> service.approve(op.getId()))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("awaiting_approval");

        verify(outboxMapper, never()).insert(any());
    }

    @Test
    void approveOfMissingOperationIsRejected() {
        UUID missing = UUID.randomUUID();
        when(operationRecordService.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(missing))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("未找到");
    }

    @Test
    void rejectOfAwaitingApprovalTransitionsToCancelledWithoutOutbox() {
        OperationEntity op = platformMutationEntity(SyncState.AWAITING_APPROVAL);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        OperationResult result = service.reject(op.getId());

        // awaiting_approval -> cancelled (Req 4.1, 22.8). No platform submission.
        assertThat(result.getSyncState()).isEqualTo(SyncState.CANCELLED);
        verify(outboxMapper, never()).insert(any());
        // The confirmed value is never touched on rejection.
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
    }

    @Test
    void rejectOfNonAwaitingApprovalOperationIsRejected() {
        OperationEntity op = platformMutationEntity(SyncState.SUBMITTED);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        assertThatThrownBy(() -> service.reject(op.getId()))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("awaiting_approval");
    }

    @Test
    void rejectOfMissingOperationIsRejected() {
        UUID missing = UUID.randomUUID();
        when(operationRecordService.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reject(missing))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("未找到");
    }

    // --- reconcile() routes the RECONCILE edge through the state machine (Req 4.10, 56.5) --------

    @Test
    void reconcileOfCancelRequestedTransitionsToReconciliationRequired() {
        OperationEntity op = platformMutationEntity(SyncState.CANCEL_REQUESTED);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        OperationResult result = service.reconcile(op.getId());

        // cancel_requested -> reconciliation_required via the RECONCILE edge (Req 4.10).
        assertThat(result.getSyncState()).isEqualTo(SyncState.RECONCILIATION_REQUIRED);
        // reconciliation_required is NOT effective, so the confirmed value is never touched (Req 3.4/3.5).
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
    }

    @Test
    void reconcileOfExpiredTransitionsToReconciliationRequired() {
        OperationEntity op = platformMutationEntity(SyncState.EXPIRED);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        OperationResult result = service.reconcile(op.getId());

        // expired -> reconciliation_required via the RECONCILE edge (Req 4.10).
        assertThat(result.getSyncState()).isEqualTo(SyncState.RECONCILIATION_REQUIRED);
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
    }

    @Test
    void reconcileOfStateWithoutReconcileEdgeIsRejected() {
        // pending has no RECONCILE edge, so reconcile must be rejected rather than silently no-op'd.
        OperationEntity op = platformMutationEntity(SyncState.PENDING);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        assertThatThrownBy(() -> service.reconcile(op.getId()))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("cancel_requested");

        // No transition was persisted and no confirmed value was touched.
        verify(operationMapper, never()).update(any(), any());
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
    }

    @Test
    void reconcileOfMissingOperationIsRejected() {
        UUID missing = UUID.randomUUID();
        when(operationRecordService.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reconcile(missing))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("未找到");
    }

    // --- undo() records a compensating Operation without mutating the original (task 7.4) --------

    @Test
    void undoOfEffectiveReversibleOperationRecordsCompensatingOperationWithSwappedValues() {
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(true);
        when(platformConnectionMapper.selectList(any())).thenReturn(List.of(connectedConnection("amazon")));
        // No newer effective Operation has changed the field — the before value is still valid.
        when(operationMapper.selectOne(any())).thenReturn(null);

        OperationEntity effective = effectiveReversibleEntity();
        when(operationRecordService.findById(effective.getId())).thenReturn(Optional.of(effective));

        ArgumentCaptor<OperationRecordCommand> commandCaptor =
                ArgumentCaptor.forClass(OperationRecordCommand.class);

        OperationResult result = service.undo(effective.getId());

        // A brand-new compensating platform_mutation lands in pending with its own lifecycle/Outbox.
        assertThat(result.getSyncState()).isEqualTo(SyncState.PENDING);
        assertThat(result.isCoalesced()).isFalse();
        assertThat(result.getOperationId()).isNotEqualTo(effective.getId());

        verify(operationRecordService).record(commandCaptor.capture());
        OperationRecordCommand cmd = commandCaptor.getValue();

        // Compensating Operation rolls the field back: before/after are SWAPPED (Req 8.5).
        assertThat(cmd.getOperationScope()).isEqualTo(OperationScope.PLATFORM_MUTATION);
        assertThat(cmd.getEntityType()).isEqualTo("campaign");
        assertThat(cmd.getEntityId()).isEqualTo(effective.getEntityId());
        assertThat(cmd.getField()).isEqualTo("status");
        assertThat(cmd.getBeforeValue()).isEqualTo("paused");   // = original after
        assertThat(cmd.getAfterValue()).isEqualTo("enabled");   // = original before
        assertThat(cmd.getReversible()).isTrue();
        // Fresh logical identity — not coalesced into the original change.
        assertThat(cmd.getLogicalOperationId()).isNotEqualTo(effective.getLogicalOperationId());

        // Its own Outbox + pending overlay are written; the original record is never mutated (Req 8.5).
        verify(outboxMapper, times(1)).insert(any());
        verify(pendingChangeMapper, times(1)).insert(any());
        verify(auditLogService, times(1)).createLog(any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void undoOfNonEffectiveOperationIsRejected() {
        OperationEntity op = platformMutationEntity(SyncState.PENDING);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        assertThatThrownBy(() -> service.undo(op.getId()))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("effective");

        verify(operationRecordService, never()).record(any());
    }

    @Test
    void undoOfNonReversibleEffectiveOperationIsRejected() {
        OperationEntity effective = effectiveReversibleEntity();
        effective.setReversible(false);
        when(operationRecordService.findById(effective.getId())).thenReturn(Optional.of(effective));

        assertThatThrownBy(() -> service.undo(effective.getId()))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("不可撤销");

        verify(operationRecordService, never()).record(any());
    }

    @Test
    void undoIsRejectedWhenBeforeValueNoLongerValid() {
        // A newer effective Operation has since changed the same field.
        OperationEntity newerEffective = platformMutationEntity(SyncState.EFFECTIVE);
        when(operationMapper.selectOne(any())).thenReturn(newerEffective);

        OperationEntity effective = effectiveReversibleEntity();
        when(operationRecordService.findById(effective.getId())).thenReturn(Optional.of(effective));

        assertThatThrownBy(() -> service.undo(effective.getId()))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("已失效");

        verify(operationRecordService, never()).record(any());
    }

    @Test
    void undoOfMissingOperationIsRejected() {
        UUID missing = UUID.randomUUID();
        when(operationRecordService.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.undo(missing))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("未找到");
    }

    // --- publishLocalDraft() creates a new linked Operation without mutating the draft (task 7.7) ---

    @Test
    void publishOfLocalOnlyDraftCreatesNewPendingOperationLinkedByParentWithoutTransitioningDraft() {
        // The Store is now write-capable, so the publish lands pending with an Outbox entry.
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(true);
        when(platformConnectionMapper.selectList(any())).thenReturn(List.of(connectedConnection("amazon")));

        OperationEntity draft = localOnlyDraftEntity();
        when(operationRecordService.findById(draft.getId())).thenReturn(Optional.of(draft));

        ArgumentCaptor<OperationRecordCommand> commandCaptor =
                ArgumentCaptor.forClass(OperationRecordCommand.class);

        OperationResult result = service.publishLocalDraft(draft.getId());

        // A brand-new platform_mutation lands in pending with its own lifecycle/Outbox.
        assertThat(result.getSyncState()).isEqualTo(SyncState.PENDING);
        assertThat(result.isCoalesced()).isFalse();
        assertThat(result.getOperationId()).isNotEqualTo(draft.getId());

        verify(operationRecordService).record(commandCaptor.capture());
        OperationRecordCommand cmd = commandCaptor.getValue();

        // The publish copies the draft's change facts, NOT swapped (Req 12.10).
        assertThat(cmd.getOperationScope()).isEqualTo(OperationScope.PLATFORM_MUTATION);
        assertThat(cmd.getOperationSource()).isEqualTo(OperationSource.MANUAL);
        assertThat(cmd.getEntityType()).isEqualTo("campaign");
        assertThat(cmd.getEntityId()).isEqualTo(draft.getEntityId());
        assertThat(cmd.getField()).isEqualTo("status");
        assertThat(cmd.getBeforeValue()).isEqualTo("enabled");
        assertThat(cmd.getAfterValue()).isEqualTo("paused");

        // Its OWN new logicalOperationId, and a parentOperationId referencing the draft (Req 12.10).
        assertThat(cmd.getLogicalOperationId()).isNotNull();
        assertThat(cmd.getLogicalOperationId()).isNotEqualTo(draft.getLogicalOperationId());
        assertThat(cmd.getParentOperationId()).isEqualTo(draft.getId());

        // Its own Outbox + pending overlay are written.
        verify(outboxMapper, times(1)).insert(any());
        verify(pendingChangeMapper, times(1)).insert(any());
        verify(auditLogService, times(1)).createLog(any(), any(), anyString(), anyString(), any(), any());

        // The original local-only draft is NEVER transitioned/mutated (Req 12.10): no sync_state
        // update is issued against it.
        verify(operationMapper, never()).update(any(), any());
    }

    @Test
    void publishWhenStoreStillNotWriteCapableResolvesLocalOnlyAgainWithoutOutbox() {
        // createOperation re-resolves write-capability: still not write-capable -> local-only, no Outbox.
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(false);

        OperationEntity draft = localOnlyDraftEntity();
        when(operationRecordService.findById(draft.getId())).thenReturn(Optional.of(draft));

        OperationResult result = service.publishLocalDraft(draft.getId());

        // A new Operation is still created (with parent link), but resolves local-only again.
        assertThat(result.getSyncState()).isEqualTo(SyncState.LOCAL_ONLY);
        assertThat(result.getOperationId()).isNotEqualTo(draft.getId());

        ArgumentCaptor<OperationRecordCommand> commandCaptor =
                ArgumentCaptor.forClass(OperationRecordCommand.class);
        verify(operationRecordService).record(commandCaptor.capture());
        assertThat(commandCaptor.getValue().getParentOperationId()).isEqualTo(draft.getId());

        // No Outbox for a local-only resolution; the original draft is not transitioned.
        verify(outboxMapper, never()).insert(any());
        verify(operationMapper, never()).update(any(), any());
    }

    @Test
    void publishOfNonLocalOnlyOperationIsRejected() {
        OperationEntity op = platformMutationEntity(SyncState.EFFECTIVE);
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));

        assertThatThrownBy(() -> service.publishLocalDraft(op.getId()))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("local-only");

        verify(operationRecordService, never()).record(any());
    }

    @Test
    void publishOfMissingOperationIsRejected() {
        UUID missing = UUID.randomUUID();
        when(operationRecordService.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.publishLocalDraft(missing))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("未找到");
    }

    // --- effective-with-external-version-change reconciliation (Req 5.8, task 7.8) ---------------

    @Test
    void effectiveWithoutConcurrentLocalChangeAppliesConfirmedValueNormally() {
        // No concurrent local change detected: the effective transition proceeds and the
        // platform-confirmed value is applied (last-writer-wins is fine when nothing else changed).
        when(confirmedValueWriter.confirmedValueChangedSince(any(), any(), any(), any()))
                .thenReturn(false);

        OperationEntity inFlight = inFlightWithValues(SyncState.SUBMITTED, "budget", "10.00", "15.00");
        when(operationRecordService.findById(inFlight.getId())).thenReturn(Optional.of(inFlight));

        OperationResult result = service.transition(inFlight.getId(), TransitionEvent.PLATFORM_EFFECTIVE);

        assertThat(result.getSyncState()).isEqualTo(SyncState.EFFECTIVE);
        // The confirmed value is updated to the Operation's after value (Req 7.4).
        verify(confirmedValueWriter, times(1))
                .applyConfirmedValue("campaign", inFlight.getEntityId(), "budget", "15.00");
        // The pending-change record is closed as the Operation settles effective.
        verify(pendingChangeMapper, times(1)).update(any(), any());
    }

    @Test
    void effectiveWithConcurrentLocalChangeRoutesToReconciliationRequiredWithoutOverwriting() {
        // A concurrent local change is detected: the local confirmed value diverged from the
        // Operation's recorded before value while it was in flight (Req 5.8).
        when(confirmedValueWriter.confirmedValueChangedSince(
                eq("campaign"), any(), eq("budget"), eq("10.00")))
                .thenReturn(true);
        // The conflicting current local value another writer set during execution.
        when(confirmedValueWriter.readConfirmedValue(eq("campaign"), any(), eq("budget")))
                .thenReturn("20.00");

        OperationEntity inFlight = inFlightWithValues(SyncState.AMAZON_PROCESSING, "budget", "10.00", "15.00");
        when(operationRecordService.findById(inFlight.getId())).thenReturn(Optional.of(inFlight));

        OperationResult result = service.transition(inFlight.getId(), TransitionEvent.PLATFORM_EFFECTIVE);

        // No last-writer-wins: the Operation routes to reconciliation_required instead of effective.
        assertThat(result.getSyncState()).isEqualTo(SyncState.RECONCILIATION_REQUIRED);
        // The local confirmed value is NEVER overwritten (Req 5.8, Property 5).
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
        // BOTH values are recorded on the Operation for explicit operator resolution.
        assertThat(inFlight.getPlatformResult())
                .contains("EFFECTIVE_WITH_EXTERNAL_VERSION_CHANGE")
                .contains("platformConfirmedValue")
                .contains("15.0")
                .contains("conflictingLocalValue")
                .contains("20.00");
        // reconciliation_required is an Unsettled_State, so the pending-change overlay stays open.
        verify(pendingChangeMapper, never()).update(any(), any());
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static OperationEntity inFlightWithValues(SyncState syncState, String field,
                                                      String beforeJson, String afterJson) {
        return OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .operationSource(OperationMachineValues.toValue(OperationSource.MANUAL))
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .syncState(OperationMachineValues.toValue(syncState))
                .entityType("campaign")
                .entityId(UUID.randomUUID())
                .field(field)
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("logical-key")
                .attemptId(UUID.randomUUID())
                .attemptNumber(1)
                .beforeValue(beforeJson)
                .afterValue(afterJson)
                .reversible(true)
                .affectedCount(1)
                .createdAt(java.time.LocalDateTime.now())
                .build();
    }

    private static OperationEntity localOnlyDraftEntity() {
        return OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .operationSource(OperationMachineValues.toValue(OperationSource.MANUAL))
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .syncState(OperationMachineValues.toValue(SyncState.LOCAL_ONLY))
                .entityType("campaign")
                .entityId(UUID.randomUUID())
                .field("status")
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("draft-logical-key")
                .attemptId(UUID.randomUUID())
                .attemptNumber(1)
                .beforeValue("\"enabled\"")
                .afterValue("\"paused\"")
                .reversible(true)
                .affectedCount(1)
                .createdAt(java.time.LocalDateTime.now())
                .build();
    }

    private static OperationEntity effectiveReversibleEntity() {
        return OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .operationSource(OperationMachineValues.toValue(OperationSource.MANUAL))
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .syncState(OperationMachineValues.toValue(SyncState.EFFECTIVE))
                .entityType("campaign")
                .entityId(UUID.randomUUID())
                .field("status")
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("logical-key")
                .attemptId(UUID.randomUUID())
                .attemptNumber(1)
                .beforeValue("\"enabled\"")
                .afterValue("\"paused\"")
                .reversible(true)
                .affectedCount(1)
                .createdAt(java.time.LocalDateTime.now())
                .build();
    }

    private static OperationEntity platformMutationEntity(SyncState syncState) {
        return OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .syncState(OperationMachineValues.toValue(syncState))
                .entityType("campaign")
                .entityId(UUID.randomUUID())
                .field("status")
                .build();
    }

    private static CreateOperationCommand.CreateOperationCommandBuilder platformMutation() {
        return CreateOperationCommand.builder()
                .storeId(UUID.randomUUID())
                .operationSource(OperationSource.MANUAL)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType("campaign")
                .entityId(UUID.randomUUID())
                .field("status")
                .beforeValue("enabled")
                .afterValue("paused")
                .logicalIdempotencyKey(UUID.randomUUID().toString());
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
