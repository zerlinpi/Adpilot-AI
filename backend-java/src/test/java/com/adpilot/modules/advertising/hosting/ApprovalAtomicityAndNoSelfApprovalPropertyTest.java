package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.OperationOutboxEntity;
import com.adpilot.modules.advertising.entity.OperationPendingChangeEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.mapper.OperationPendingChangeMapper;
import com.adpilot.modules.advertising.operation.OperationJsonCodec;
import com.adpilot.modules.advertising.operation.OperationMachineValues;
import com.adpilot.modules.advertising.operation.OperationStateMachine;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.approval.entity.ApprovalRequestEntity;
import com.adpilot.modules.approval.mapper.ApprovalRequestMapper;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.jqwik.api.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based test for approval atomicity and no self-approval in
 * {@link HostingApprovalServiceImpl}.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 23: Approval atomicity and no self-approval
 *
 * <p><b>Validates: Requirements 40.2, 40.4</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>When an approval_request is approved, both the approval_request status AND
 *       the Operation state transition happen atomically (both succeed or neither does).</li>
 *   <li>A user cannot approve their own operation (self-approval is rejected) — the
 *       approver cannot be the same as the operation creator/requestor.</li>
 *   <li>Rejection is also atomic (approval_request rejected + Operation → cancelled).</li>
 *   <li>Only valid approval state transitions are allowed (pending → approved/rejected,
 *       not re-approval of already-processed requests).</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 23: Approval atomicity and no self-approval")
class ApprovalAtomicityAndNoSelfApprovalPropertyTest {

    private static final int MIN_ITERATIONS = 150;

    static {
        // Register entity metadata so MyBatis-Plus can resolve lambda columns
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ApprovalRequestEntity.class);
        TableInfoHelper.initTableInfo(assistant, OperationEntity.class);
        TableInfoHelper.initTableInfo(assistant, OperationOutboxEntity.class);
        TableInfoHelper.initTableInfo(assistant, OperationPendingChangeEntity.class);
        TableInfoHelper.initTableInfo(assistant, PlatformConnectionEntity.class);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Build a {@link HostingApprovalServiceImpl} with mocked dependencies,
     * pre-configured for a valid approval scenario.
     */
    @SuppressWarnings("unchecked")
    private HostingApprovalServiceImpl buildService(
            UUID operationId,
            UUID storeId,
            UUID requesterId,
            boolean writeCapable,
            String operationSyncState) {

        ApprovalRequestMapper approvalRequestMapper = mock(ApprovalRequestMapper.class);
        OperationMapper operationMapper = mock(OperationMapper.class);
        OperationOutboxMapper outboxMapper = mock(OperationOutboxMapper.class);
        OperationPendingChangeMapper pendingChangeMapper = mock(OperationPendingChangeMapper.class);
        OperationJsonCodec jsonCodec = mock(OperationJsonCodec.class);
        WriteCapabilityService writeCapabilityService = mock(WriteCapabilityService.class);
        PlatformConnectionMapper platformConnectionMapper = mock(PlatformConnectionMapper.class);

        // Real state machine — we want to validate real transitions
        OperationStateMachine stateMachine = new OperationStateMachine();

        // Configure approval request (pending)
        ApprovalRequestEntity approvalRequest = ApprovalRequestEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .requesterId(requesterId)
                .relatedEntityId(operationId)
                .relatedEntityType("hosting_operation")
                .status("pending")
                .requestType("ai_hosting_approval")
                .title("Test Approval")
                .build();
        when(approvalRequestMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(approvalRequest);
        when(approvalRequestMapper.updateById(any())).thenReturn(1);

        // Configure operation
        OperationEntity operation = OperationEntity.builder()
                .id(operationId)
                .storeId(storeId)
                .syncState(operationSyncState)
                .entityType("keyword")
                .entityId(UUID.randomUUID())
                .field("bid")
                .afterValue("\"1.50\"")
                .operationSource("ai_hosting")
                .operationScope("platform_mutation")
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("test-key")
                .attemptId(UUID.randomUUID())
                .build();
        when(operationMapper.selectById(operationId)).thenReturn(operation);
        when(operationMapper.update(any(), any())).thenReturn(1);

        // Configure write capability
        when(writeCapabilityService.isWriteCapable(storeId)).thenReturn(writeCapable);

        // Configure platform connection for outbox writing
        PlatformConnectionEntity connection = new PlatformConnectionEntity();
        connection.setStoreId(storeId);
        connection.setPlatform("amazon_ads");
        connection.setStatus("connected");
        when(platformConnectionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(connection));

        // Configure outbox insert
        when(outboxMapper.insert(any())).thenReturn(1);

        // Configure pending change update
        when(pendingChangeMapper.update(any(), any())).thenReturn(1);

        // Configure json codec
        when(jsonCodec.toJson(any())).thenReturn("{}");
        when(jsonCodec.fromJson(anyString(), any())).thenReturn("1.50");

        return new HostingApprovalServiceImpl(
                approvalRequestMapper,
                operationMapper,
                outboxMapper,
                pendingChangeMapper,
                stateMachine,
                jsonCodec,
                writeCapabilityService,
                platformConnectionMapper);
    }

    /**
     * Build a service configured with a non-pending approval request (already processed).
     */
    @SuppressWarnings("unchecked")
    private HostingApprovalServiceImpl buildServiceWithNoApprovalRequest(
            UUID operationId, UUID storeId) {

        ApprovalRequestMapper approvalRequestMapper = mock(ApprovalRequestMapper.class);
        OperationMapper operationMapper = mock(OperationMapper.class);
        OperationOutboxMapper outboxMapper = mock(OperationOutboxMapper.class);
        OperationPendingChangeMapper pendingChangeMapper = mock(OperationPendingChangeMapper.class);
        OperationJsonCodec jsonCodec = mock(OperationJsonCodec.class);
        WriteCapabilityService writeCapabilityService = mock(WriteCapabilityService.class);
        PlatformConnectionMapper platformConnectionMapper = mock(PlatformConnectionMapper.class);
        OperationStateMachine stateMachine = new OperationStateMachine();

        // Return null — simulates no pending approval request found
        when(approvalRequestMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(null);

        return new HostingApprovalServiceImpl(
                approvalRequestMapper,
                operationMapper,
                outboxMapper,
                pendingChangeMapper,
                stateMachine,
                jsonCodec,
                writeCapabilityService,
                platformConnectionMapper);
    }

    // ── Arbitraries ─────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<UUID> randomUUID() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<UUID[]> distinctUserPair() {
        return Arbitraries.create(() -> {
            UUID requester = UUID.randomUUID();
            UUID approver;
            do {
                approver = UUID.randomUUID();
            } while (approver.equals(requester));
            return new UUID[]{requester, approver};
        });
    }

    // ================================================================================
    // Property 1: Approval is atomic — both approval_request status AND Operation
    //             state transition happen together
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 23: Approval atomicity and no self-approval.
     *
     * <p><b>Validates: Requirements 40.2</b>
     *
     * <p>When an operation is approved by a different user, both the approval_request
     * status must be set to "approved" and the Operation must transition from
     * awaiting_approval → pending in the same transactional call. We verify this by
     * checking that the approve call updates both the approval request AND the
     * operation state without throwing exceptions.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Approval atomically updates approval_request status and Operation state")
    @SuppressWarnings("unchecked")
    void approvalAtomicallyUpdatesBothRecords(
            @ForAll("distinctUserPair") UUID[] users) {

        UUID requesterId = users[0];
        UUID approverId = users[1];
        UUID operationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        // Build service with real state machine, operation in awaiting_approval state
        ApprovalRequestMapper approvalRequestMapper = mock(ApprovalRequestMapper.class);
        OperationMapper operationMapper = mock(OperationMapper.class);
        OperationOutboxMapper outboxMapper = mock(OperationOutboxMapper.class);
        OperationPendingChangeMapper pendingChangeMapper = mock(OperationPendingChangeMapper.class);
        OperationJsonCodec jsonCodec = mock(OperationJsonCodec.class);
        WriteCapabilityService writeCapabilityService = mock(WriteCapabilityService.class);
        PlatformConnectionMapper platformConnectionMapper = mock(PlatformConnectionMapper.class);
        OperationStateMachine stateMachine = new OperationStateMachine();

        // Configure approval request (pending)
        ApprovalRequestEntity approvalRequest = ApprovalRequestEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .requesterId(requesterId)
                .relatedEntityId(operationId)
                .relatedEntityType("hosting_operation")
                .status("pending")
                .requestType("ai_hosting_approval")
                .title("Test Approval")
                .build();
        when(approvalRequestMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(approvalRequest);
        when(approvalRequestMapper.updateById(any())).thenReturn(1);

        // Configure operation in awaiting_approval state
        OperationEntity operation = OperationEntity.builder()
                .id(operationId)
                .storeId(storeId)
                .syncState(OperationMachineValues.toValue(SyncState.AWAITING_APPROVAL))
                .entityType("keyword")
                .entityId(UUID.randomUUID())
                .field("bid")
                .afterValue("\"1.50\"")
                .operationSource("ai_hosting")
                .operationScope("platform_mutation")
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("test-key-" + operationId)
                .attemptId(UUID.randomUUID())
                .build();
        when(operationMapper.selectById(operationId)).thenReturn(operation);
        when(operationMapper.update(any(), any())).thenReturn(1);
        when(writeCapabilityService.isWriteCapable(storeId)).thenReturn(true);

        PlatformConnectionEntity connection = new PlatformConnectionEntity();
        connection.setStoreId(storeId);
        connection.setPlatform("amazon_ads");
        connection.setStatus("connected");
        when(platformConnectionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(connection));
        when(outboxMapper.insert(any())).thenReturn(1);
        when(jsonCodec.toJson(any())).thenReturn("{}");
        when(jsonCodec.fromJson(anyString(), any())).thenReturn("1.50");

        HostingApprovalServiceImpl service = new HostingApprovalServiceImpl(
                approvalRequestMapper, operationMapper, outboxMapper,
                pendingChangeMapper, stateMachine, jsonCodec,
                writeCapabilityService, platformConnectionMapper);

        // Execute: approve the operation
        service.approveOperation(operationId, approverId);

        // Verify atomicity: BOTH records were updated
        // 1. Approval request was updated to "approved"
        ArgumentCaptor<ApprovalRequestEntity> approvalCaptor =
                ArgumentCaptor.forClass(ApprovalRequestEntity.class);
        verify(approvalRequestMapper).updateById(approvalCaptor.capture());
        assertThat(approvalCaptor.getValue().getStatus())
                .as("Approval request status must be 'approved' (Req 40.2)")
                .isEqualTo("approved");
        assertThat(approvalCaptor.getValue().getApproverId())
                .as("Approver ID must be recorded")
                .isEqualTo(approverId);

        // 2. Operation was updated to 'pending' state
        verify(operationMapper).update(any(), any());

        // 3. Outbox entry was written (so OutboxWorker can pick up the approved Operation)
        verify(outboxMapper).insert(any());
    }

    // ================================================================================
    // Property 2: Self-approval is rejected — the approver cannot be the requester
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 23: Approval atomicity and no self-approval.
     *
     * <p><b>Validates: Requirements 40.4</b>
     *
     * <p>For any user ID, if the same user is both the requester (decision originator)
     * and the approver, the system must reject the approval with a clear error and
     * must NOT mutate either the approval_request or the Operation state.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Self-approval is always rejected before any state mutation")
    @SuppressWarnings("unchecked")
    void selfApprovalIsAlwaysRejected(@ForAll("randomUUID") UUID userId) {

        UUID operationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        ApprovalRequestMapper approvalRequestMapper = mock(ApprovalRequestMapper.class);
        OperationMapper operationMapper = mock(OperationMapper.class);
        OperationOutboxMapper outboxMapper = mock(OperationOutboxMapper.class);
        OperationPendingChangeMapper pendingChangeMapper = mock(OperationPendingChangeMapper.class);
        OperationJsonCodec jsonCodec = mock(OperationJsonCodec.class);
        WriteCapabilityService writeCapabilityService = mock(WriteCapabilityService.class);
        PlatformConnectionMapper platformConnectionMapper = mock(PlatformConnectionMapper.class);
        OperationStateMachine stateMachine = new OperationStateMachine();

        // The requester is the same as the approver
        ApprovalRequestEntity approvalRequest = ApprovalRequestEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .requesterId(userId) // same user
                .relatedEntityId(operationId)
                .relatedEntityType("hosting_operation")
                .status("pending")
                .requestType("ai_hosting_approval")
                .title("Test Approval")
                .build();
        when(approvalRequestMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(approvalRequest);

        HostingApprovalServiceImpl service = new HostingApprovalServiceImpl(
                approvalRequestMapper, operationMapper, outboxMapper,
                pendingChangeMapper, stateMachine, jsonCodec,
                writeCapabilityService, platformConnectionMapper);

        // Execute: attempt self-approval
        assertThatThrownBy(() -> service.approveOperation(operationId, userId))
                .as("Self-approval must be rejected (Req 40.4)")
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException bex = (BusinessException) ex;
                    assertThat(bex.getStatus()).isEqualTo(403);
                    assertThat(bex.getCode()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
                });

        // Verify NO state mutations occurred
        verify(approvalRequestMapper, never()).updateById(any());
        verify(operationMapper, never()).selectById(any());
        verify(operationMapper, never()).update(any(), any());
        verify(outboxMapper, never()).insert(any());
    }

    // ================================================================================
    // Property 3: Rejection is also atomic — approval_request rejected + Operation → cancelled
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 23: Approval atomicity and no self-approval.
     *
     * <p><b>Validates: Requirements 40.2</b>
     *
     * <p>When an operation is rejected, both the approval_request status must be set to
     * "rejected" and the Operation must transition from awaiting_approval → cancelled
     * in the same transactional call.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Rejection atomically updates approval_request status and Operation to cancelled")
    @SuppressWarnings("unchecked")
    void rejectionAtomicallyUpdatesBothRecords(
            @ForAll("distinctUserPair") UUID[] users,
            @ForAll("rejectionReasons") String reason) {

        UUID requesterId = users[0];
        UUID approverId = users[1];
        UUID operationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        ApprovalRequestMapper approvalRequestMapper = mock(ApprovalRequestMapper.class);
        OperationMapper operationMapper = mock(OperationMapper.class);
        OperationOutboxMapper outboxMapper = mock(OperationOutboxMapper.class);
        OperationPendingChangeMapper pendingChangeMapper = mock(OperationPendingChangeMapper.class);
        OperationJsonCodec jsonCodec = mock(OperationJsonCodec.class);
        WriteCapabilityService writeCapabilityService = mock(WriteCapabilityService.class);
        PlatformConnectionMapper platformConnectionMapper = mock(PlatformConnectionMapper.class);
        OperationStateMachine stateMachine = new OperationStateMachine();

        // Configure approval request (pending)
        ApprovalRequestEntity approvalRequest = ApprovalRequestEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .requesterId(requesterId)
                .relatedEntityId(operationId)
                .relatedEntityType("hosting_operation")
                .status("pending")
                .requestType("ai_hosting_approval")
                .title("Test Rejection")
                .build();
        when(approvalRequestMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(approvalRequest);
        when(approvalRequestMapper.updateById(any())).thenReturn(1);

        // Configure operation in awaiting_approval state
        OperationEntity operation = OperationEntity.builder()
                .id(operationId)
                .storeId(storeId)
                .syncState(OperationMachineValues.toValue(SyncState.AWAITING_APPROVAL))
                .entityType("keyword")
                .entityId(UUID.randomUUID())
                .field("bid")
                .afterValue("\"1.50\"")
                .operationSource("ai_hosting")
                .operationScope("platform_mutation")
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("test-key-" + operationId)
                .attemptId(UUID.randomUUID())
                .build();
        when(operationMapper.selectById(operationId)).thenReturn(operation);
        when(operationMapper.update(any(), any())).thenReturn(1);
        when(pendingChangeMapper.update(any(), any())).thenReturn(1);

        HostingApprovalServiceImpl service = new HostingApprovalServiceImpl(
                approvalRequestMapper, operationMapper, outboxMapper,
                pendingChangeMapper, stateMachine, jsonCodec,
                writeCapabilityService, platformConnectionMapper);

        // Execute: reject the operation
        service.rejectOperation(operationId, approverId, reason);

        // Verify atomicity: BOTH records were updated
        // 1. Approval request was updated to "rejected"
        ArgumentCaptor<ApprovalRequestEntity> approvalCaptor =
                ArgumentCaptor.forClass(ApprovalRequestEntity.class);
        verify(approvalRequestMapper).updateById(approvalCaptor.capture());
        assertThat(approvalCaptor.getValue().getStatus())
                .as("Approval request status must be 'rejected' (Req 40.2)")
                .isEqualTo("rejected");
        assertThat(approvalCaptor.getValue().getRejectionReason())
                .as("Rejection reason must be recorded")
                .isEqualTo(reason);

        // 2. Operation was updated (transitioned to cancelled)
        verify(operationMapper).update(any(), any());

        // 3. Pending change was closed (since operation settles as cancelled)
        verify(pendingChangeMapper).update(any(), any());

        // 4. NO outbox entry written (rejected operations don't submit)
        verify(outboxMapper, never()).insert(any());
    }

    // ================================================================================
    // Property 4: Only valid approval state transitions — no re-approval of already
    //             processed requests (no pending request found → error)
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 23: Approval atomicity and no self-approval.
     *
     * <p><b>Validates: Requirements 40.2, 40.4</b>
     *
     * <p>Attempting to approve an operation that has no pending approval_request
     * (already approved, rejected, or never existed) must be rejected with a clear error.
     * This prevents re-approval of already-processed requests.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Approval of already-processed or missing request is rejected")
    void alreadyProcessedRequestCannotBeReApproved(@ForAll("randomUUID") UUID operationId) {

        UUID storeId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();

        HostingApprovalServiceImpl service = buildServiceWithNoApprovalRequest(operationId, storeId);

        // Execute: attempt to approve — no pending request exists
        assertThatThrownBy(() -> service.approveOperation(operationId, approverId))
                .as("Approving a non-pending (already processed) request must fail")
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException bex = (BusinessException) ex;
                    assertThat(bex.getStatus()).isEqualTo(404);
                    assertThat(bex.getCode()).isEqualTo("APPROVAL_REQUEST_NOT_FOUND");
                });
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 23: Approval atomicity and no self-approval.
     *
     * <p><b>Validates: Requirements 40.2</b>
     *
     * <p>Attempting to reject an operation that has no pending approval_request
     * (already approved, rejected, or never existed) must be rejected with a clear error.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Rejection of already-processed or missing request is rejected")
    void alreadyProcessedRequestCannotBeReRejected(@ForAll("randomUUID") UUID operationId) {

        UUID storeId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();

        HostingApprovalServiceImpl service = buildServiceWithNoApprovalRequest(operationId, storeId);

        // Execute: attempt to reject — no pending request exists
        assertThatThrownBy(() -> service.rejectOperation(operationId, approverId, "too risky"))
                .as("Rejecting a non-pending (already processed) request must fail")
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException bex = (BusinessException) ex;
                    assertThat(bex.getStatus()).isEqualTo(404);
                    assertThat(bex.getCode()).isEqualTo("APPROVAL_REQUEST_NOT_FOUND");
                });
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 23: Approval atomicity and no self-approval.
     *
     * <p><b>Validates: Requirements 40.2</b>
     *
     * <p>Approving an Operation that is NOT in the awaiting_approval state must be
     * rejected, even if an approval_request record exists. Only Operations in
     * awaiting_approval can be approved.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Approval only works for Operations in awaiting_approval state")
    @SuppressWarnings("unchecked")
    void approvalOnlyWorksForAwaitingApprovalState(
            @ForAll("nonAwaitingApprovalStates") SyncState wrongState,
            @ForAll("distinctUserPair") UUID[] users) {

        UUID requesterId = users[0];
        UUID approverId = users[1];
        UUID operationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        ApprovalRequestMapper approvalRequestMapper = mock(ApprovalRequestMapper.class);
        OperationMapper operationMapper = mock(OperationMapper.class);
        OperationOutboxMapper outboxMapper = mock(OperationOutboxMapper.class);
        OperationPendingChangeMapper pendingChangeMapper = mock(OperationPendingChangeMapper.class);
        OperationJsonCodec jsonCodec = mock(OperationJsonCodec.class);
        WriteCapabilityService writeCapabilityService = mock(WriteCapabilityService.class);
        PlatformConnectionMapper platformConnectionMapper = mock(PlatformConnectionMapper.class);
        OperationStateMachine stateMachine = new OperationStateMachine();

        // Configure approval request (pending)
        ApprovalRequestEntity approvalRequest = ApprovalRequestEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .requesterId(requesterId)
                .relatedEntityId(operationId)
                .relatedEntityType("hosting_operation")
                .status("pending")
                .requestType("ai_hosting_approval")
                .title("Test Approval")
                .build();
        when(approvalRequestMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(approvalRequest);
        when(writeCapabilityService.isWriteCapable(storeId)).thenReturn(true);

        // Configure operation in the wrong state (not awaiting_approval)
        OperationEntity operation = OperationEntity.builder()
                .id(operationId)
                .storeId(storeId)
                .syncState(OperationMachineValues.toValue(wrongState))
                .entityType("keyword")
                .entityId(UUID.randomUUID())
                .field("bid")
                .afterValue("\"1.50\"")
                .operationSource("ai_hosting")
                .operationScope("platform_mutation")
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("test-key-" + operationId)
                .attemptId(UUID.randomUUID())
                .build();
        when(operationMapper.selectById(operationId)).thenReturn(operation);

        HostingApprovalServiceImpl service = new HostingApprovalServiceImpl(
                approvalRequestMapper, operationMapper, outboxMapper,
                pendingChangeMapper, stateMachine, jsonCodec,
                writeCapabilityService, platformConnectionMapper);

        // Execute: attempt to approve operation in wrong state
        assertThatThrownBy(() -> service.approveOperation(operationId, approverId))
                .as("Approving an Operation in %s state must fail (only awaiting_approval is valid)",
                        wrongState)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException bex = (BusinessException) ex;
                    assertThat(bex.getStatus()).isEqualTo(409);
                    assertThat(bex.getCode()).isEqualTo("ILLEGAL_OPERATION_TRANSITION");
                });

        // Verify no state mutations occurred
        verify(approvalRequestMapper, never()).updateById(any());
        verify(operationMapper, never()).update(any(), any());
        verify(outboxMapper, never()).insert(any());
    }

    // ── Arbitraries ─────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<String> rejectionReasons() {
        return Arbitraries.of(
                "Too risky",
                "Budget concerns",
                "Insufficient data",
                "Requires further review",
                "Keyword not relevant",
                "Boundary violation"
        );
    }

    @Provide
    Arbitrary<SyncState> nonAwaitingApprovalStates() {
        return Arbitraries.of(
                SyncState.PENDING,
                SyncState.SUBMITTING,
                SyncState.SUBMITTED,
                SyncState.EFFECTIVE,
                SyncState.FAILED,
                SyncState.CANCELLED,
                SyncState.SUPERSEDED,
                SyncState.EXPIRED,
                SyncState.RECONCILIATION_REQUIRED,
                SyncState.LOCAL_ONLY,
                SyncState.AMAZON_PROCESSING,
                SyncState.CANCEL_REQUESTED
        );
    }
}
