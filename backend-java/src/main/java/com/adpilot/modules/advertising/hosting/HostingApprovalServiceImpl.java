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
import com.adpilot.modules.advertising.operation.TransitionEvent;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.approval.entity.ApprovalRequestEntity;
import com.adpilot.modules.approval.mapper.ApprovalRequestMapper;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Default implementation of {@link HostingApprovalService} integrating the hosting
 * routing pipeline with the existing {@code approval_requests} infrastructure.
 *
 * <p>Key design decisions:</p>
 * <ul>
 *   <li>Reuses the existing {@link ApprovalRequestEntity} and {@link ApprovalRequestMapper}
 *       rather than creating a parallel approval system (Req 40.1).</li>
 *   <li>All approve/reject actions are performed in a single transaction to maintain
 *       atomicity between the approval_requests status and the Operation SyncState
 *       (Req 40.2).</li>
 *   <li>The Operation SyncState is authoritative; the approval_requests record
 *       reflects but never diverges from it (Req 40.3).</li>
 *   <li>Self-approval is rejected before any state mutation (Req 40.4).</li>
 * </ul>
 *
 * <p>Validates: Requirements 24.2, 24.3, 40.1, 40.2, 40.3, 40.4.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HostingApprovalServiceImpl implements HostingApprovalService {

    /** The related_entity_type used for hosting Operation approval requests. */
    static final String RELATED_ENTITY_TYPE = "hosting_operation";

    /** The request_type used for hosting AI decision approvals. */
    static final String REQUEST_TYPE = "ai_hosting_approval";

    /** The connection status treated as a valid active connection. */
    private static final String STATUS_CONNECTED = ConnectionStatus.CONNECTED;

    private final ApprovalRequestMapper approvalRequestMapper;
    private final OperationMapper operationMapper;
    private final OperationOutboxMapper outboxMapper;
    private final OperationPendingChangeMapper pendingChangeMapper;
    private final OperationStateMachine stateMachine;
    private final OperationJsonCodec jsonCodec;
    private final WriteCapabilityService writeCapabilityService;
    private final PlatformConnectionMapper platformConnectionMapper;

    @Override
    @Transactional
    public UUID createApprovalRequest(UUID operationId, UUID storeId, UUID requesterId,
                                      String title, String reason, String riskLevel) {
        if (operationId == null) {
            throw new IllegalArgumentException("operationId must not be null");
        }
        if (storeId == null) {
            throw new IllegalArgumentException("storeId must not be null");
        }

        ApprovalRequestEntity entity = ApprovalRequestEntity.builder()
                .storeId(storeId)
                .requesterId(requesterId != null ? requesterId : storeId) // fallback for system-initiated
                .requestType(REQUEST_TYPE)
                .relatedEntityType(RELATED_ENTITY_TYPE)
                .relatedEntityId(operationId)
                .title(title != null ? title : "AI Hosting Operation Approval")
                .description(reason)
                .riskLevel(riskLevel != null ? riskLevel : "medium")
                .status("pending")
                .build();

        approvalRequestMapper.insert(entity);

        log.info("Created hosting approval request: id={}, operationId={}, reason={}, riskLevel={}",
                entity.getId(), operationId, reason, riskLevel);

        return entity.getId();
    }

    @Override
    @Transactional
    public void approveOperation(UUID operationId, UUID approverId) {
        if (operationId == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "operationId is required");
        }
        if (approverId == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "approverId is required");
        }

        // 1. Find the linked approval request
        ApprovalRequestEntity approvalRequest = findPendingApprovalRequest(operationId);

        // 2. Reject self-approval (Req 40.4): the decision originator cannot approve
        if (approverId.equals(approvalRequest.getRequesterId())) {
            throw new BusinessException(403, "SELF_APPROVAL_FORBIDDEN",
                    "决策发起者不能审批自己的操作（禁止自我审批）");
        }

        // 3. Load and validate the Operation
        OperationEntity operation = loadOperationInState(operationId, SyncState.AWAITING_APPROVAL);

        // 4. Check write capability — approval routes to pending → submission, so the
        // Store must still be write-capable (Req 4.12, 53)
        if (!writeCapabilityService.isWriteCapable(operation.getStoreId())) {
            throw new BusinessException(409, "STORE_NOT_WRITE_CAPABLE",
                    "店铺当前不可写入，无法审批通过并提交该操作");
        }

        // 5. Atomically: update approval_requests status (Req 40.2)
        approvalRequest.setApproverId(approverId);
        approvalRequest.setStatus("approved");
        approvalRequest.setResolvedAt(LocalDateTime.now());
        approvalRequest.setUpdatedAt(LocalDateTime.now());
        approvalRequestMapper.updateById(approvalRequest);

        // 6. Atomically: transition Operation awaiting_approval → pending (Req 7.7, 24.2, 40.2)
        SyncState to = stateMachine.transition(SyncState.AWAITING_APPROVAL, TransitionEvent.APPROVE);
        persistSyncState(operation, to);

        // 7. Write the Outbox entry so OutboxWorker picks up the approved Operation (Req 6.1)
        writeApprovalOutbox(operation);

        log.info("Hosting operation approved: operationId={}, approver={}, approval_request_id={}",
                operationId, approverId, approvalRequest.getId());
    }

    @Override
    @Transactional
    public void rejectOperation(UUID operationId, UUID approverId, String reason) {
        if (operationId == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "operationId is required");
        }
        if (approverId == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "approverId is required");
        }

        // 1. Find the linked approval request
        ApprovalRequestEntity approvalRequest = findPendingApprovalRequest(operationId);

        // 2. Load and validate the Operation
        OperationEntity operation = loadOperationInState(operationId, SyncState.AWAITING_APPROVAL);

        // 3. Atomically: update approval_requests status (Req 40.2)
        approvalRequest.setApproverId(approverId);
        approvalRequest.setStatus("rejected");
        approvalRequest.setRejectionReason(reason);
        approvalRequest.setResolvedAt(LocalDateTime.now());
        approvalRequest.setUpdatedAt(LocalDateTime.now());
        approvalRequestMapper.updateById(approvalRequest);

        // 4. Atomically: transition Operation awaiting_approval → cancelled (Req 24.3, 40.2)
        SyncState to = stateMachine.transition(SyncState.AWAITING_APPROVAL, TransitionEvent.REJECT);
        String statusReason = reason != null && !reason.isBlank()
                ? "审批被拒绝: " + reason
                : "操作员驳回了待审批的操作";
        persistSyncState(operation, to, statusReason);

        // 5. Close pending-change overlay since Operation settles as cancelled
        closePendingChange(operationId);

        log.info("Hosting operation rejected: operationId={}, approver={}, reason={}",
                operationId, approverId, reason);
    }

    @Override
    @Transactional
    public void closeApprovalRequest(UUID operationId, String closeReason) {
        if (operationId == null) {
            return;
        }

        LambdaQueryWrapper<ApprovalRequestEntity> wrapper = new LambdaQueryWrapper<ApprovalRequestEntity>()
                .eq(ApprovalRequestEntity::getRelatedEntityId, operationId)
                .eq(ApprovalRequestEntity::getRelatedEntityType, RELATED_ENTITY_TYPE)
                .eq(ApprovalRequestEntity::getStatus, "pending");

        ApprovalRequestEntity existing = approvalRequestMapper.selectOne(wrapper);
        if (existing == null) {
            // No open approval request — nothing to close
            return;
        }

        existing.setStatus("cancelled");
        existing.setRejectionReason(closeReason != null ? closeReason : "OPERATION_CANCELLED");
        existing.setResolvedAt(LocalDateTime.now());
        existing.setUpdatedAt(LocalDateTime.now());
        approvalRequestMapper.updateById(existing);

        log.info("Closed hosting approval request: id={}, operationId={}, reason={}",
                existing.getId(), operationId, closeReason);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Find the pending approval_requests record linked to the given Operation.
     * Throws if no pending request exists.
     */
    private ApprovalRequestEntity findPendingApprovalRequest(UUID operationId) {
        LambdaQueryWrapper<ApprovalRequestEntity> wrapper = new LambdaQueryWrapper<ApprovalRequestEntity>()
                .eq(ApprovalRequestEntity::getRelatedEntityId, operationId)
                .eq(ApprovalRequestEntity::getRelatedEntityType, RELATED_ENTITY_TYPE)
                .eq(ApprovalRequestEntity::getStatus, "pending");

        ApprovalRequestEntity entity = approvalRequestMapper.selectOne(wrapper);
        if (entity == null) {
            throw new BusinessException(404, "APPROVAL_REQUEST_NOT_FOUND",
                    "未找到该操作对应的待审批记录：" + operationId);
        }
        return entity;
    }

    /**
     * Load an Operation and validate it is in the expected SyncState.
     */
    private OperationEntity loadOperationInState(UUID operationId, SyncState expectedState) {
        OperationEntity operation = operationMapper.selectById(operationId);
        if (operation == null) {
            throw new BusinessException(404, "OPERATION_NOT_FOUND",
                    "未找到要审批的操作记录：" + operationId);
        }
        String syncStateValue = operation.getSyncState();
        if (syncStateValue == null || syncStateValue.isBlank()) {
            throw new BusinessException(409, "NOT_A_PLATFORM_MUTATION",
                    "该操作不是平台变更操作，没有可流转的同步状态");
        }
        SyncState current = OperationMachineValues.toSyncState(syncStateValue);
        if (current != expectedState) {
            throw new BusinessException(409, "ILLEGAL_OPERATION_TRANSITION",
                    "操作当前状态不是 " + OperationMachineValues.toValue(expectedState)
                            + "，当前状态：" + OperationMachineValues.toValue(current));
        }
        return operation;
    }

    /**
     * Persist the new SyncState on the Operation record.
     */
    private void persistSyncState(OperationEntity operation, SyncState to) {
        persistSyncState(operation, to, null);
    }

    private void persistSyncState(OperationEntity operation, SyncState to, String statusReason) {
        UpdateWrapper<OperationEntity> update = new UpdateWrapper<OperationEntity>()
                .set("sync_state", OperationMachineValues.toValue(to))
                .eq("id", operation.getId().toString());
        if (statusReason != null && !statusReason.isBlank()) {
            update.set("status_reason", statusReason);
        }
        operationMapper.update(null, update);
    }

    /**
     * Close the pending-change overlay record when the Operation settles.
     */
    private void closePendingChange(UUID operationId) {
        UpdateWrapper<OperationPendingChangeEntity> update =
                new UpdateWrapper<OperationPendingChangeEntity>()
                        .set("status", "closed")
                        .eq("operation_id", operationId.toString())
                        .eq("status", "open");
        pendingChangeMapper.update(null, update);
    }

    /**
     * Write the Outbox entry for an Operation that was just approved (mirrors the
     * approach in OperationServiceImpl.writeApprovalOutbox).
     */
    private void writeApprovalOutbox(OperationEntity operation) {
        String platform = resolveActivePlatform(operation.getStoreId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operationId", operation.getId() != null ? operation.getId().toString() : null);
        payload.put("entityType", operation.getEntityType());
        payload.put("entityId", operation.getEntityId() != null ? operation.getEntityId().toString() : null);
        payload.put("field", operation.getField());
        payload.put("afterValue", jsonCodec.fromJson(operation.getAfterValue(), Object.class));

        OperationOutboxEntity outbox = OperationOutboxEntity.builder()
                .operationId(operation.getId())
                .storeId(operation.getStoreId())
                .platform(platform)
                .payload(jsonCodec.toJson(payload))
                .submissionIdempotencyKey(null)
                .status("pending")
                .attemptCount(0)
                .build();
        outboxMapper.insert(outbox);
    }

    /**
     * Resolve the platform of the Store's valid active connection for the Outbox row.
     */
    private String resolveActivePlatform(UUID storeId) {
        PlatformConnectionEntity connection = platformConnectionMapper.selectList(
                        new LambdaQueryWrapper<PlatformConnectionEntity>()
                                .eq(PlatformConnectionEntity::getStoreId, storeId)
                                .eq(PlatformConnectionEntity::getStatus, STATUS_CONNECTED)
                                .last("LIMIT 1"))
                .stream()
                .findFirst()
                .orElse(null);
        if (connection == null || connection.getPlatform() == null || connection.getPlatform().isBlank()) {
            throw new BusinessException(409, "STORE_NOT_WRITE_CAPABLE",
                    "无法解析店铺的有效平台连接，无法创建平台提交任务");
        }
        return connection.getPlatform();
    }
}
