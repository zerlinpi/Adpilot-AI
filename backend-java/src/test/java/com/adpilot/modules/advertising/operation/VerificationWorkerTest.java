package com.adpilot.modules.advertising.operation;

import com.adpilot.common.resilience.CircuitBreaker;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformVerifyResult;
import com.adpilot.modules.apisync.model.SubmissionMetadata;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VerificationWorker} — validates the read-after-write verification logic
 * that maps connector verify results to the correct state transitions.
 *
 * <p>Validates: Requirements 20.1, 20.4</p>
 */
@DisplayName("VerificationWorker unit tests")
class VerificationWorkerTest {

    private OperationMapper operationMapper;
    private PlatformConnectionMapper platformConnectionMapper;
    private OperationService operationService;
    private WriteCapabilityService writeCapabilityService;
    private OperationJsonCodec jsonCodec;
    private ObjectMapper objectMapper;
    private CryptoUtil cryptoUtil;
    private PlatformWriteConnector writeConnector;
    private VerificationWorker worker;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID OPERATION_ID = UUID.randomUUID();
    private static final String PLATFORM = "amazon_ads";

    @BeforeEach
    void setUp() {
        operationMapper = mock(OperationMapper.class);
        platformConnectionMapper = mock(PlatformConnectionMapper.class);
        operationService = mock(OperationService.class);
        writeCapabilityService = mock(WriteCapabilityService.class);
        objectMapper = new ObjectMapper();
        jsonCodec = new OperationJsonCodec(objectMapper);
        cryptoUtil = mock(CryptoUtil.class);
        writeConnector = mock(PlatformWriteConnector.class);

        when(writeConnector.platform()).thenReturn(PLATFORM);
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(true);

        // Setup platform connection lookup
        PlatformConnectionEntity connection = new PlatformConnectionEntity();
        connection.setId(CONNECTION_ID);
        connection.setStoreId(STORE_ID);
        connection.setPlatform(PLATFORM);
        connection.setStatus("connected");
        connection.setConfigEncrypted(null);
        when(platformConnectionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(connection));

        worker = new VerificationWorker(
                operationMapper,
                platformConnectionMapper,
                operationService,
                writeCapabilityService,
                jsonCodec,
                objectMapper,
                cryptoUtil,
                new CircuitBreaker(false, 5, 30),
                List.of(writeConnector),
                60,  // verifyDelaySeconds
                5    // maxVerifyAttempts
        );
    }

    @Test
    @DisplayName("Match: live value equals expected → transitions to effective (PLATFORM_EFFECTIVE)")
    void matchTransitionsToEffective() {
        // Given: a submitted Operation with after_value "1.5"
        OperationEntity op = buildSubmittedOperation("\"1.5\"");
        when(operationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(op));

        // And: the connector reads back the matching live value
        when(writeConnector.verify(any(), any(), any()))
                .thenReturn(PlatformVerifyResult.success("keyword", "bid", "1.5"));

        // When: verification runs
        worker.verify();

        // Then: Operation transitions to effective
        verify(operationService).transition(OPERATION_ID, TransitionEvent.PLATFORM_EFFECTIVE);
    }

    @Test
    @DisplayName("Match with numeric normalization: '1.50' matches '1.5'")
    void matchWithNumericNormalization() {
        // Given: expected "1.50", live "1.5" — should match after normalization
        OperationEntity op = buildSubmittedOperation("\"1.50\"");
        when(operationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(op));

        when(writeConnector.verify(any(), any(), any()))
                .thenReturn(PlatformVerifyResult.success("keyword", "bid", "1.5"));

        // When
        worker.verify();

        // Then
        verify(operationService).transition(OPERATION_ID, TransitionEvent.PLATFORM_EFFECTIVE);
    }

    @Test
    @DisplayName("Mismatch: live value differs → transitions through expired to reconciliation_required")
    void mismatchTransitionsToReconciliationRequired() {
        // Given: expected "1.5" but live is "2.0"
        OperationEntity op = buildSubmittedOperation("\"1.5\"");
        when(operationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(op));

        when(writeConnector.verify(any(), any(), any()))
                .thenReturn(PlatformVerifyResult.success("keyword", "bid", "2.0"));

        // When
        worker.verify();

        // Then: transitions through TIMEOUT (submitted→expired) then RECONCILE (expired→reconciliation_required)
        var eventCaptor = ArgumentCaptor.forClass(TransitionEvent.class);
        verify(operationService, times(2)).transition(eq(OPERATION_ID), eventCaptor.capture());
        List<TransitionEvent> events = eventCaptor.getAllValues();
        assertThat(events).containsExactly(TransitionEvent.TIMEOUT, TransitionEvent.RECONCILE);
    }

    @Test
    @DisplayName("Read failure below max retries: stays submitted, records attempt")
    void readFailureBelowMaxRetriesStaysSubmitted() {
        // Given: an Operation with 0 verification attempts so far
        OperationEntity op = buildSubmittedOperation("\"1.5\"");
        when(operationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(op));

        when(writeConnector.verify(any(), any(), any()))
                .thenReturn(PlatformVerifyResult.readFailed("Connection timeout"));

        // When
        worker.verify();

        // Then: no transition applied — stays in submitted
        verify(operationService, never()).transition(any(), any());
        // But the attempt is recorded via platform_result update
        verify(operationMapper).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    @DisplayName("Read failure at max retries: transitions to expired with VERIFY_TIMEOUT")
    void readFailureAtMaxRetriesTransitionsToExpired() {
        // Given: an Operation that has already had 4 verification attempts (at max-1)
        OperationEntity op = buildSubmittedOperation("\"1.5\"");
        op.setPlatformResult("{\"verificationAttempts\":4}");
        when(operationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(op));

        when(writeConnector.verify(any(), any(), any()))
                .thenReturn(PlatformVerifyResult.readFailed("Connection timeout"));

        // When
        worker.verify();

        // Then: transitions to expired via TIMEOUT with VERIFY_TIMEOUT reason
        verify(operationService).transition(OPERATION_ID, TransitionEvent.TIMEOUT);
    }

    @Test
    @DisplayName("Expired is non-terminal: can still reach effective/failed/reconciliation_required")
    void expiredIsNonTerminal() {
        // This validates Req 20.4: expired is non-terminal.
        // Verified via the OperationStateMachine directly.
        OperationStateMachine sm = new OperationStateMachine();

        // expired → effective via PLATFORM_EFFECTIVE
        assertThat(sm.transition(SyncState.EXPIRED, TransitionEvent.PLATFORM_EFFECTIVE))
                .isEqualTo(SyncState.EFFECTIVE);

        // expired → failed via PLATFORM_FAILED
        assertThat(sm.transition(SyncState.EXPIRED, TransitionEvent.PLATFORM_FAILED))
                .isEqualTo(SyncState.FAILED);

        // expired → reconciliation_required via RECONCILE
        assertThat(sm.transition(SyncState.EXPIRED, TransitionEvent.RECONCILE))
                .isEqualTo(SyncState.RECONCILIATION_REQUIRED);
    }

    @Test
    @DisplayName("Connector throws exception: treated as read failure")
    void connectorExceptionTreatedAsReadFailure() {
        // Given
        OperationEntity op = buildSubmittedOperation("\"1.5\"");
        when(operationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(op));

        when(writeConnector.verify(any(), any(), any()))
                .thenThrow(new RuntimeException("Network error"));

        // When
        worker.verify();

        // Then: no terminal transition (attempt count is below max)
        verify(operationService, never()).transition(any(), any());
    }

    @Test
    @DisplayName("Operations not past verification delay are not polled")
    void operationsNotPastDelayAreNotPolled() {
        // Given: no operations returned (the query filters by updatedAt cutoff)
        when(operationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        // When
        worker.verify();

        // Then: no connector calls
        verify(writeConnector, never()).verify(any(), any(), any());
    }

    @Test
    @DisplayName("Store not write-capable: operation skipped")
    void storeNotWriteCapableSkipped() {
        // Given: store is not write-capable
        when(writeCapabilityService.isWriteCapable(STORE_ID)).thenReturn(false);
        OperationEntity op = buildSubmittedOperation("\"1.5\"");
        when(operationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(op));

        // When
        worker.verify();

        // Then: no connector calls
        verify(writeConnector, never()).verify(any(), any(), any());
    }

    @Test
    @DisplayName("H3: OPEN circuit skips the verify call and preserves the Operation for retry")
    void openCircuitSkipsVerifyAndPreservesOperation() {
        // Given: a submitted Operation past the verification delay
        OperationEntity op = buildSubmittedOperation("\"1.5\"");
        when(operationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(op));

        // And: a circuit breaker that is OPEN for this dependency (allow -> false)
        CircuitBreaker openBreaker = mock(CircuitBreaker.class);
        when(openBreaker.allow(any())).thenReturn(false);

        VerificationWorker guardedWorker = new VerificationWorker(
                operationMapper, platformConnectionMapper, operationService, writeCapabilityService,
                jsonCodec, objectMapper, cryptoUtil, openBreaker, List.of(writeConnector), 60, 5);

        // When
        guardedWorker.verify();

        // Then: the external verify call is never made...
        verify(writeConnector, never()).verify(any(), any(), any());
        // ...no state transition is applied (the Operation stays submitted for the next tick)...
        verify(operationService, never()).transition(any(), any());
        // ...and no verification attempt is recorded (nothing is mutated this tick).
        verify(operationMapper, never()).update(any(), any());
        // The breaker outcome is neither recorded as success nor failure (the call never happened).
        verify(openBreaker, never()).recordSuccess(any());
        verify(openBreaker, never()).recordFailure(any());
    }

    // ---------------------------------------------------------------------------------------------
    // Helper methods
    // ---------------------------------------------------------------------------------------------

    private OperationEntity buildSubmittedOperation(String afterValue) {
        OperationEntity op = new OperationEntity();
        op.setId(OPERATION_ID);
        op.setStoreId(STORE_ID);
        op.setOperationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION));
        op.setSyncState(OperationMachineValues.toValue(SyncState.SUBMITTED));
        op.setEntityType("keyword");
        op.setEntityId(UUID.randomUUID());
        op.setField("bid");
        op.setAfterValue(afterValue);
        op.setBeforeValue("\"1.0\"");
        op.setOperationSource("ai_hosting");
        op.setPlatformReference("amzn-req-123");
        op.setSubmissionIdempotencyKey("idempkey-001");
        // updatedAt must be in the past for the verification delay to have elapsed
        op.setUpdatedAt(LocalDateTime.now().minusSeconds(120));
        return op;
    }
}
