package com.adpilot.modules.advertising.operation;

import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.OperationOutboxEntity;
import com.adpilot.modules.advertising.hosting.PreSubmissionRevalidator;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.apisync.connector.AmazonAdsRateLimiter;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.PlatformWriteResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OutboxWorker} — the single retry owner that drains the Outbox and submits
 * each pending {@code platform_mutation} Operation through the Store's write connector.
 *
 * <p>These tests drive the worker through its public entry point {@link OutboxWorker#drain()} with
 * mocked mappers, connector, rate limiter, and revalidator, and assert the branch matrix of the
 * submission outcome:
 * <ul>
 *   <li><b>accepted</b> → Operation advances {@code submitting → submitted} (PLATFORM_ACCEPTED),
 *       Outbox marked submitted, submitted counter incremented;</li>
 *   <li><b>retryable reject</b> → Operation returns {@code submitting → pending} (RETRYABLE_REJECT),
 *       attempt_count incremented + next_attempt_at set on the Outbox row, and the Operation is
 *       NEVER advanced to submitted;</li>
 *   <li><b>permanent reject</b> → Operation advances {@code submitting → failed} (PERMANENT_REJECT),
 *       Outbox marked failed, failed counter incremented;</li>
 *   <li><b>rate-limiter open</b> (amazon_ads, no token) → claim held, row returned to pending,
 *       no submit and no transition;</li>
 *   <li><b>not write-capable</b> → row held, no submit;</li>
 *   <li><b>Operation no longer pending</b> → idempotent no-submit;</li>
 *   <li><b>pre-submission revalidation fails</b> → no submit;</li>
 *   <li><b>lost claim race</b> → row skipped, Operation never loaded.</li>
 * </ul>
 *
 * <p>The core invariant asserted across the matrix: an Operation reaches {@code submitted} ONLY on a
 * confirmed platform acceptance (Req 16.7, 30.1, 30.7).
 *
 * <p>Validates: Requirements 6.3, 16.7, 30.1, 30.7, 53.2.
 */
@DisplayName("OutboxWorker submission branch-matrix unit tests")
class OutboxWorkerBranchMatrixTest {

    private OperationOutboxMapper outboxMapper;
    private OperationMapper operationMapper;
    private OperationRecordService operationRecordService;
    private OperationService operationService;
    private IdempotencyService idempotencyService;
    private WriteCapabilityService writeCapabilityService;
    private com.adpilot.modules.apisync.mapper.PlatformConnectionMapper platformConnectionMapper;
    private AmazonAdsRateLimiter rateLimiter;
    private PreSubmissionRevalidator preSubmissionRevalidator;
    private PlatformWriteConnector writeConnector;
    private OutboxWorker worker;

    private static final String PLATFORM = "amazon_ads";
    private static final String PROFILE_ID = "profile-1";
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID OPERATION_ID = UUID.randomUUID();
    private static final UUID OUTBOX_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outboxMapper = mock(OperationOutboxMapper.class);
        operationMapper = mock(OperationMapper.class);
        operationRecordService = mock(OperationRecordService.class);
        operationService = mock(OperationService.class);
        idempotencyService = mock(IdempotencyService.class);
        writeCapabilityService = mock(WriteCapabilityService.class);
        platformConnectionMapper = mock(com.adpilot.modules.apisync.mapper.PlatformConnectionMapper.class);
        rateLimiter = mock(AmazonAdsRateLimiter.class);
        preSubmissionRevalidator = mock(PreSubmissionRevalidator.class);
        writeConnector = mock(PlatformWriteConnector.class);

        ObjectMapper objectMapper = new ObjectMapper();
        OperationJsonCodec jsonCodec = new OperationJsonCodec(objectMapper);
        CryptoUtil cryptoUtil = mock(CryptoUtil.class);

        // The connector map is built in the constructor from platform(), so stub it first.
        when(writeConnector.platform()).thenReturn(PLATFORM);

        // Claim + all outbox updates succeed (claim relies on affected-rows == 1).
        when(outboxMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);

        // Store is write-capable and its active connection resolves to a registered connector.
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(true);
        PlatformConnectionEntity connection = PlatformConnectionEntity.builder()
                .id(CONNECTION_ID)
                .storeId(STORE_ID)
                .platform(PLATFORM)
                .status("connected")
                .profileId(PROFILE_ID)
                .configEncrypted(null)
                .build();
        when(platformConnectionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(connection));

        // Rate limiter grants a token; revalidation passes; a fresh submission key is minted.
        when(rateLimiter.tryAcquire(any())).thenReturn(true);
        when(preSubmissionRevalidator.revalidate(any()))
                .thenReturn(PreSubmissionRevalidator.RevalidationResult.pass());
        when(idempotencyService.newSubmissionIdempotencyKey()).thenReturn("sub-key-1");

        worker = new OutboxWorker(
                outboxMapper,
                operationMapper,
                operationRecordService,
                operationService,
                idempotencyService,
                writeCapabilityService,
                platformConnectionMapper,
                jsonCodec,
                objectMapper,
                cryptoUtil,
                rateLimiter,
                preSubmissionRevalidator,
                List.of(writeConnector),
                50);
    }

    @Test
    @DisplayName("Accepted: Operation advances submitting → submitted and submitted counter increments")
    void acceptedAdvancesToSubmitted() {
        givenClaimablePendingRow();
        when(writeConnector.submit(any(), any()))
                .thenReturn(PlatformWriteResult.acceptedAmazon("amzn-req-1", "ext-1", "ok"));

        worker.drain();

        // submitting → submitted only via PLATFORM_ACCEPTED (Req 16.7).
        verify(operationService).transition(OPERATION_ID, TransitionEvent.SUBMIT);
        verify(operationService).transition(OPERATION_ID, TransitionEvent.PLATFORM_ACCEPTED);
        verify(operationService, never()).transition(eq(OPERATION_ID), eq(TransitionEvent.RETRYABLE_REJECT));
        verify(operationService, never()).transition(eq(OPERATION_ID), eq(TransitionEvent.PERMANENT_REJECT));
        assertThat(worker.getSubmittedCount()).isEqualTo(1L);
        assertThat(worker.getFailedCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Retryable reject: returns submitting → pending, increments attempt_count, never submitted")
    void retryableRejectReturnsToPendingAndIncrementsAttemptCount() {
        givenClaimablePendingRow();
        when(writeConnector.submit(any(), any()))
                .thenReturn(PlatformWriteResult.retryable("429 Too Many Requests", 45L));

        worker.drain();

        // submitting → pending via RETRYABLE_REJECT; never advanced to submitted (acceptance gate).
        verify(operationService).transition(OPERATION_ID, TransitionEvent.SUBMIT);
        verify(operationService).transition(OPERATION_ID, TransitionEvent.RETRYABLE_REJECT);
        verify(operationService, never()).transition(eq(OPERATION_ID), eq(TransitionEvent.PLATFORM_ACCEPTED));
        assertThat(worker.getSubmittedCount()).isEqualTo(0L);

        // The Outbox row is the single retry owner: attempt_count++ and next_attempt_at set.
        String retrySet = captureRetryUpdateSqlSet();
        assertThat(retrySet)
                .as("retryable reject must increment attempt_count and schedule next_attempt_at")
                .contains("attempt_count = attempt_count + 1")
                .contains("next_attempt_at");
    }

    @Test
    @DisplayName("Permanent reject: advances submitting → failed and failed counter increments")
    void permanentRejectAdvancesToFailed() {
        givenClaimablePendingRow();
        when(writeConnector.submit(any(), any()))
                .thenReturn(PlatformWriteResult.permanentReject("INVALID_BID_AMOUNT", "bid too low"));

        worker.drain();

        verify(operationService).transition(OPERATION_ID, TransitionEvent.SUBMIT);
        verify(operationService).transition(OPERATION_ID, TransitionEvent.PERMANENT_REJECT);
        verify(operationService, never()).transition(eq(OPERATION_ID), eq(TransitionEvent.PLATFORM_ACCEPTED));
        assertThat(worker.getFailedCount()).isEqualTo(1L);
        assertThat(worker.getSubmittedCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Connector throws: treated as a retryable rejection, never submitted")
    void connectorExceptionIsRetryable() {
        givenClaimablePendingRow();
        when(writeConnector.submit(any(), any()))
                .thenThrow(new RuntimeException("connection reset"));

        worker.drain();

        verify(operationService).transition(OPERATION_ID, TransitionEvent.RETRYABLE_REJECT);
        verify(operationService, never()).transition(eq(OPERATION_ID), eq(TransitionEvent.PLATFORM_ACCEPTED));
        assertThat(worker.getSubmittedCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Rate limiter open (no token): claim held to pending, no submit, no transition")
    void rateLimiterOpenSkipsSubmission() {
        givenClaimablePendingRow();
        when(rateLimiter.tryAcquire(PROFILE_ID)).thenReturn(false);

        worker.drain();

        verify(writeConnector, never()).submit(any(), any());
        verify(operationService, never()).transition(any(), any());
        assertThat(worker.getSubmittedCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Store not write-capable: row held, no submit and never advanced to a platform state")
    void notWriteCapableHeld() {
        givenClaimablePendingRow();
        when(writeCapabilityService.isWriteCapable(STORE_ID)).thenReturn(false);

        worker.drain();

        verify(writeConnector, never()).submit(any(), any());
        verify(operationService, never()).transition(any(), any());
    }

    @Test
    @DisplayName("Operation already advanced past pending: idempotent, no submit")
    void alreadyAdvancedOperationNotResubmitted() {
        OperationOutboxEntity row = pendingOutboxRow();
        when(outboxMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(row));
        OperationEntity op = pendingOperation();
        op.setSyncState(OperationMachineValues.toValue(SyncState.SUBMITTED));
        when(operationRecordService.findById(OPERATION_ID)).thenReturn(Optional.of(op));

        worker.drain();

        verify(writeConnector, never()).submit(any(), any());
        verify(operationService, never()).transition(any(), any());
    }

    @Test
    @DisplayName("Pre-submission revalidation fails: Operation is not submitted")
    void revalidationFailureSkipsSubmission() {
        givenClaimablePendingRow();
        when(preSubmissionRevalidator.revalidate(OPERATION_ID))
                .thenReturn(PreSubmissionRevalidator.RevalidationResult.expired());

        worker.drain();

        verify(writeConnector, never()).submit(any(), any());
        verify(operationService, never()).transition(eq(OPERATION_ID), eq(TransitionEvent.SUBMIT));
    }

    @Test
    @DisplayName("Lost claim race (0 rows affected): row skipped, Operation never loaded")
    void lostClaimRaceSkipsRow() {
        givenClaimablePendingRow();
        when(outboxMapper.update(any(), any(UpdateWrapper.class))).thenReturn(0);

        worker.drain();

        verify(operationRecordService, never()).findById(any());
        verify(writeConnector, never()).submit(any(), any());
    }

    @Test
    @DisplayName("Orphaned row (missing Operation): marked failed, no submit")
    void orphanedRowMarkedFailed() {
        OperationOutboxEntity row = pendingOutboxRow();
        when(outboxMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(row));
        when(operationRecordService.findById(OPERATION_ID)).thenReturn(Optional.empty());

        worker.drain();

        verify(writeConnector, never()).submit(any(), any());
        verify(operationService, never()).transition(any(), any());
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private void givenClaimablePendingRow() {
        OperationOutboxEntity row = pendingOutboxRow();
        when(outboxMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(row));
        when(operationRecordService.findById(OPERATION_ID)).thenReturn(Optional.of(pendingOperation()));
    }

    private OperationOutboxEntity pendingOutboxRow() {
        return OperationOutboxEntity.builder()
                .id(OUTBOX_ID)
                .operationId(OPERATION_ID)
                .storeId(STORE_ID)
                .platform(PLATFORM)
                .status("pending")
                .attemptCount(0)
                .build();
    }

    private OperationEntity pendingOperation() {
        OperationEntity op = new OperationEntity();
        op.setId(OPERATION_ID);
        op.setStoreId(STORE_ID);
        op.setOperationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION));
        op.setOperationSource(OperationMachineValues.toValue(OperationSource.AI_HOSTING));
        op.setSyncState(OperationMachineValues.toValue(SyncState.PENDING));
        op.setEntityType("keyword");
        op.setEntityId(UUID.randomUUID());
        op.setField("bid");
        op.setBeforeValue(null);
        op.setAfterValue(null);
        return op;
    }

    /**
     * Capture the {@link UpdateWrapper} the worker hands to {@code outboxMapper.update} for the
     * retryable-reject branch (the only update carrying the raw {@code attempt_count} SQL), and
     * return its SET clause for behavioral assertion.
     */
    @SuppressWarnings("unchecked")
    private String captureRetryUpdateSqlSet() {
        ArgumentCaptor<UpdateWrapper<OperationOutboxEntity>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(outboxMapper, org.mockito.Mockito.atLeastOnce()).update(isNull(), captor.capture());
        return captor.getAllValues().stream()
                .map(UpdateWrapper::getSqlSet)
                .filter(s -> s != null && s.contains("attempt_count"))
                .findFirst()
                .orElse("");
    }
}
