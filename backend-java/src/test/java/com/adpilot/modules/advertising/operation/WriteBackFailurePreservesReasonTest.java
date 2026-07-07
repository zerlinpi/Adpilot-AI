package com.adpilot.modules.advertising.operation;

import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformWriteResult;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the write-back failure path of {@link OperationWriteBackImpl} (task 6.6).
 *
 * <p>Validates: Requirements 4.5.
 *
 * <p>Req 4.5: WHEN the platform write-back fails, the Operation is set to {@code failed}
 * and a readable failure reason is preserved so the operator can retry or investigate.
 *
 * <p>These tests simulate a {@link PlatformWriteConnector} rejecting the submitted change —
 * both an explicit {@link PlatformWriteResult#rejected(String) rejected} result and a
 * transport/credential exception (which the contract treats as a rejection carrying the
 * exception message as the reason) — and assert that the generic write-back path:
 * <ol>
 *   <li>records the platform's readable rejection reason on the Operation_Record's
 *       {@code status_reason} BEFORE resolving the lifecycle, and</li>
 *   <li>drives the Operation to {@code failed} through the {@link TransitionEvent#PERMANENT_REJECT}
 *       state-machine event, so the resolved {@link SyncState} is {@link SyncState#FAILED}.</li>
 * </ol>
 *
 * <p>Collaborators are mocked (JUnit5 + Mockito, mirroring {@code HostingOperationControllerTest}):
 * the connector is stubbed to reject so the failure branch is exercised, the active connection is
 * stubbed {@code connected}, and the post-transition reload returns the {@code failed} Operation so
 * the returned {@link OperationResult} reflects the failure. The state machine itself is verified
 * elsewhere; here we assert the write-back invokes it with the failing event and persists the reason.
 */
@ExtendWith(MockitoExtension.class)
class WriteBackFailurePreservesReasonTest {

    private static final String PLATFORM = "shopify";

    @Mock
    private OperationMapper operationMapper;

    @Mock
    private OperationService operationService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private com.adpilot.modules.apisync.mapper.PlatformConnectionMapper platformConnectionMapper;

    @Mock
    private OperationJsonCodec jsonCodec;

    @Mock
    private CryptoUtil cryptoUtil;

    @Mock
    private PlatformWriteConnector shopifyConnector;

    /** A real, dependency-free ObjectMapper — the write-back never parses JSON in this slice. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private OperationWriteBackImpl writeBack;

    /**
     * Register the {@link OperationEntity} table metadata so the production code's
     * {@code LambdaUpdateWrapper.set(OperationEntity::getStatusReason, ...)} can resolve its
     * lambda column without a running Spring/MyBatis context.
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OperationEntity.class);
    }

    @BeforeEach
    void setUp() {
        // The connector is registered under its platform key when the service is constructed.
        when(shopifyConnector.platform()).thenReturn(PLATFORM);
        writeBack = new OperationWriteBackImpl(
                operationMapper,
                operationService,
                idempotencyService,
                platformConnectionMapper,
                jsonCodec,
                objectMapper,
                cryptoUtil,
                List.of(shopifyConnector));
    }

    /**
     * Validates: Requirements 4.5.
     *
     * <p>A platform {@link PlatformWriteResult#rejected(String) rejection} sets the Operation to
     * {@code failed} (via PERMANENT_REJECT) and preserves the platform's readable failure reason on
     * the Operation_Record.
     */
    @Test
    @DisplayName("connector rejection sets the Operation failed and preserves the readable failure reason")
    void connectorRejectionSetsOperationFailedAndPreservesReason() {
        UUID operationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        String reason = "Inventory item not found on Shopify";

        OperationEntity pending = platformMutation(operationId, storeId, "pending");
        OperationEntity failed = platformMutation(operationId, storeId, "failed");
        failed.setStatusReason(reason);
        // Reload after the transition returns the now-failed Operation (Req 4.5).
        when(operationMapper.selectById(operationId)).thenReturn(pending, failed);
        when(platformConnectionMapper.selectList(any()))
                .thenReturn(List.of(connectedConnection(storeId)));
        when(shopifyConnector.submit(any(ConnectionContext.class), any(PlatformChange.class)))
                .thenReturn(PlatformWriteResult.rejected(reason));

        OperationResult result = writeBack.applyOperation(operationId);

        assertFailedWithReason(operationId, result, reason);
    }

    /**
     * Validates: Requirements 4.5.
     *
     * <p>A transport/credential exception thrown by the connector is treated as a rejection carrying
     * the exception message as the reason: the Operation is still set to {@code failed} and the
     * readable reason is preserved (never thrown back to the caller).
     */
    @Test
    @DisplayName("connector transport failure is treated as a rejection: failed with the exception message preserved")
    void connectorTransportFailureSetsOperationFailedWithExceptionMessageAsReason() {
        UUID operationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        String reason = "Connection reset by Shopify";

        OperationEntity pending = platformMutation(operationId, storeId, "pending");
        OperationEntity failed = platformMutation(operationId, storeId, "failed");
        failed.setStatusReason(reason);
        when(operationMapper.selectById(operationId)).thenReturn(pending, failed);
        when(platformConnectionMapper.selectList(any()))
                .thenReturn(List.of(connectedConnection(storeId)));
        when(shopifyConnector.submit(any(ConnectionContext.class), any(PlatformChange.class)))
                .thenThrow(new RuntimeException(reason));

        OperationResult result = writeBack.applyOperation(operationId);

        assertFailedWithReason(operationId, result, reason);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Shared assertions
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Assert the write-back recorded the readable {@code reason} on the Operation_Record and then
     * drove it to {@code failed} via PERMANENT_REJECT, with the returned result reflecting FAILED.
     */
    private void assertFailedWithReason(UUID operationId, OperationResult result, String reason) {
        // The Operation was first advanced to submitting, then permanently rejected → failed (Req 4.5).
        verify(operationService).transition(operationId, TransitionEvent.SUBMIT);
        verify(operationService).transition(operationId, TransitionEvent.PERMANENT_REJECT);
        assertThat(result.getSyncState()).isEqualTo(SyncState.FAILED);

        // The readable failure reason is persisted on the Operation_Record's status_reason.
        ArgumentCaptor<LambdaUpdateWrapper<OperationEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(operationMapper, times(1)).update(isNull(), captor.capture());
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .as("the platform's readable rejection reason must be preserved on the Operation_Record")
                .contains(reason);

        // The reason is recorded BEFORE the failing transition (the confirmed value is never changed
        // on failure; the failure is resolved through the state machine after the reason is stored).
        var ordered = inOrder(operationMapper, operationService);
        ordered.verify(operationMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        ordered.verify(operationService).transition(operationId, TransitionEvent.PERMANENT_REJECT);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Fixtures
    // ──────────────────────────────────────────────────────────────────────

    /**
     * A {@code platform_mutation} Operation in the given sync state. A non-blank
     * {@code submissionIdempotencyKey} is preset so the submission path does not mint a new key
     * (keeping the only {@code operationMapper.update} call the reason-recording one).
     */
    private static OperationEntity platformMutation(UUID id, UUID storeId, String syncState) {
        return OperationEntity.builder()
                .id(id)
                .storeId(storeId)
                .operationSource("manual")
                .operationScope("platform_mutation")
                .entityType("product")
                .entityId(UUID.randomUUID())
                .field("inventory")
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("inventory:" + id)
                .attemptId(UUID.randomUUID())
                .submissionIdempotencyKey("submission-key-1")
                .syncState(syncState)
                .build();
    }

    /** A state-{@code connected} Shopify connection with no stored config (no decryption needed). */
    private static PlatformConnectionEntity connectedConnection(UUID storeId) {
        PlatformConnectionEntity connection = new PlatformConnectionEntity();
        connection.setId(UUID.randomUUID());
        connection.setStoreId(storeId);
        connection.setPlatform(PLATFORM);
        connection.setStatus("connected");
        connection.setConfigEncrypted(null);
        return connection;
    }
}
