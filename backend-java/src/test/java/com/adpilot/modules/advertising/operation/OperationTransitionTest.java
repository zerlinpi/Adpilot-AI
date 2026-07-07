package com.adpilot.modules.advertising.operation;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.PermissionChecker;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.mapper.OperationPendingChangeMapper;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OperationServiceImpl#transition} and the confirmed-value-on-effective
 * invariant (task 7.1).
 *
 * <p>Every Sync_State change routes through the real {@link OperationStateMachine}; the confirmed
 * value is set to the Operation's after value EXACTLY when the Operation becomes {@code effective},
 * and never otherwise (Req 3.4, 3.5, 4.4, 4.7, 4.11, 6.4, 7.4, 7.5, 12.1). The pending-change record
 * is closed when the Operation settles and left open while it remains in an Unsettled_State.</p>
 *
 * <p>Validates: Requirements 4.x, 3.4, 3.5, 4.11, 6.4, 7.4, 7.5, 12.1.</p>
 */
class OperationTransitionTest {

    private OperationRecordService operationRecordService;
    private OperationMapper operationMapper;
    private ConfirmedValueWriter confirmedValueWriter;
    private OperationPendingChangeMapper pendingChangeMapper;

    private OperationServiceImpl service;

    @BeforeEach
    void setUp() {
        operationRecordService = mock(OperationRecordService.class);
        operationMapper = mock(OperationMapper.class);
        confirmedValueWriter = mock(ConfirmedValueWriter.class);
        pendingChangeMapper = mock(OperationPendingChangeMapper.class);

        service = new OperationServiceImpl(
                mock(PermissionChecker.class),
                mock(DataScopeService.class),
                mock(InFlightConflictLock.class),
                mock(IdempotencyService.class),
                mock(EntityVersionGuard.class),
                mock(WriteCapabilityService.class),
                operationRecordService,
                operationMapper,
                new OperationStateMachine(),
                confirmedValueWriter,
                pendingChangeMapper,
                mock(OperationOutboxMapper.class),
                mock(PlatformConnectionMapper.class),
                mock(AuditLogService.class),
                new OperationJsonCodec(new ObjectMapper()),
                org.mockito.Mockito.mock(com.adpilot.modules.advertising.operation.alert.WriteBackAlerting.class));
    }

    @Test
    void becomingEffectiveSetsConfirmedValueAndClosesPendingChange() {
        OperationEntity op = submittedKeywordBidOperation();
        stub(op);

        OperationResult result = service.transition(op.getId(), TransitionEvent.PLATFORM_EFFECTIVE);

        assertThat(result.getSyncState()).isEqualTo(SyncState.EFFECTIVE);
        // The confirmed value is updated to the Operation's after value exactly on effective (Req 7.4).
        verify(confirmedValueWriter, times(1))
                .applyConfirmedValue("keyword", op.getEntityId(), "bid", op.getAfterValue());
        // The new Sync_State is persisted via the state machine's resolution.
        verify(operationMapper, times(1)).update(any(), any());
        // The pending change is closed once the Operation settles effective.
        verify(pendingChangeMapper, times(1)).update(any(), any());
    }

    @Test
    void becomingFailedLeavesConfirmedValueUnchangedAndKeepsPendingOpen() {
        OperationEntity op = submittedKeywordBidOperation();
        stub(op);

        OperationResult result = service.transition(op.getId(), TransitionEvent.PLATFORM_FAILED);

        assertThat(result.getSyncState()).isEqualTo(SyncState.FAILED);
        // Confirmed value must NOT change for any non-effective resulting state (Req 3.5, 7.5).
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
        // failed is retained for retry/discard; the pending change stays open (not closed here).
        verify(pendingChangeMapper, never()).update(any(), any());
    }

    @Test
    void cancellingNotYetSubmittedLeavesConfirmedUnchangedAndClosesPending() {
        OperationEntity op = pendingKeywordBidOperation();
        stub(op);

        OperationResult result = service.transition(op.getId(), TransitionEvent.CANCEL);

        assertThat(result.getSyncState()).isEqualTo(SyncState.CANCELLED);
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
        // cancelled settles never-applied: the pending change is closed (Req 7.5).
        verify(pendingChangeMapper, times(1)).update(any(), any());
    }

    @Test
    void supersedingNotYetSubmittedLeavesConfirmedUnchanged() {
        OperationEntity op = pendingKeywordBidOperation();
        stub(op);

        OperationResult result = service.transition(op.getId(), TransitionEvent.SUPERSEDE);

        assertThat(result.getSyncState()).isEqualTo(SyncState.SUPERSEDED);
        // Confirmed value unchanged on supersession (Req 4.11).
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
        verify(pendingChangeMapper, times(1)).update(any(), any());
    }

    @Test
    void expiringInFlightLeavesConfirmedUnchangedAndKeepsPendingOpen() {
        OperationEntity op = submittedKeywordBidOperation();
        stub(op);

        OperationResult result = service.transition(op.getId(), TransitionEvent.TIMEOUT);

        assertThat(result.getSyncState()).isEqualTo(SyncState.EXPIRED);
        // expired is NOT terminal and may still reach effective: confirmed unchanged, pending stays open.
        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
        verify(pendingChangeMapper, never()).update(any(), any());
    }

    @Test
    void illegalTransitionIsRejectedByTheStateMachine() {
        OperationEntity op = pendingKeywordBidOperation();
        stub(op);

        // PLATFORM_EFFECTIVE is not legal from pending — the state machine authority rejects it.
        assertThatThrownBy(() -> service.transition(op.getId(), TransitionEvent.PLATFORM_EFFECTIVE))
                .isInstanceOf(BusinessException.class);

        verify(confirmedValueWriter, never()).applyConfirmedValue(any(), any(), any(), any());
        verify(operationMapper, never()).update(any(), any());
    }

    @Test
    void localConfigurationOperationHasNoSyncStateToTransition() {
        OperationEntity localConfig = OperationEntity.builder()
                .id(UUID.randomUUID())
                .entityType("ai_personality")
                .entityId(UUID.randomUUID())
                .executionStatus(OperationMachineValues.toValue(ExecutionStatus.APPLIED))
                .syncState(null)
                .build();
        when(operationRecordService.findById(localConfig.getId())).thenReturn(Optional.of(localConfig));

        assertThatThrownBy(() -> service.transition(localConfig.getId(), TransitionEvent.PLATFORM_EFFECTIVE))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void missingOperationIsRejected() {
        UUID missing = UUID.randomUUID();
        when(operationRecordService.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transition(missing, TransitionEvent.PLATFORM_EFFECTIVE))
                .isInstanceOf(BusinessException.class);
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Make findById return the operation both before and after the transition's re-read. */
    private void stub(OperationEntity op) {
        when(operationRecordService.findById(op.getId())).thenReturn(Optional.of(op));
    }

    private static OperationEntity submittedKeywordBidOperation() {
        return keywordBidOperation(SyncState.SUBMITTED);
    }

    private static OperationEntity pendingKeywordBidOperation() {
        return keywordBidOperation(SyncState.PENDING);
    }

    private static OperationEntity keywordBidOperation(SyncState syncState) {
        return OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .operationSource(OperationMachineValues.toValue(OperationSource.MANUAL))
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .entityType("keyword")
                .entityId(UUID.randomUUID())
                .field("bid")
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("key-" + UUID.randomUUID())
                .attemptId(UUID.randomUUID())
                .beforeValue("1.00")
                .afterValue("1.50")
                .syncState(OperationMachineValues.toValue(syncState))
                .build();
    }
}
