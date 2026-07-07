package com.adpilot.modules.approval.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.approval.entity.ApprovalDecisionEntity;
import com.adpilot.modules.approval.entity.ApprovalPolicyEntity;
import com.adpilot.modules.approval.entity.ApprovalRequestEntity;
import com.adpilot.modules.approval.mapper.ApprovalDecisionMapper;
import com.adpilot.modules.approval.mapper.ApprovalPolicyMapper;
import com.adpilot.modules.approval.mapper.ApprovalRequestMapper;
import com.adpilot.modules.approval.service.ApprovalActionExecutor;
import com.adpilot.modules.approval.service.ApprovalWorkflowService;
import com.adpilot.modules.audit.service.AuditLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Default {@link ApprovalWorkflowService}.
 *
 * <p><b>Level model.</b> The {@code approval_policies} table stores an ordered
 * {@code approver_user_ids} JSON list and an {@code approval_type}. We derive the
 * ordered approval levels from these:
 * <ul>
 *   <li>{@code multi_level}: one level per entry in {@code approver_user_ids},
 *       in order — the action must be approved by each listed user in sequence
 *       (Req 12.1.5).</li>
 *   <li>any other gated type ({@code role}/{@code direct_manager}/
 *       {@code department}/{@code feishu}): a single level whose authorized
 *       approvers are all listed {@code approver_user_ids}.</li>
 * </ul>
 * The explicit {@code approver_user_ids} list is treated as the authoritative
 * set of approvers the policy "defines" (Req 12.1.2).
 *
 * <p><b>Action execution (Req 12.1.3).</b> Gated actions are resumed through the
 * {@link ApprovalActionExecutor} registry, keyed by {@code module:actionType}.
 * Execution happens inside the same transaction that flips the request from
 * {@code pending} to {@code approved}; because every mutating method asserts the
 * request is still {@code pending} before acting, a request can transition to
 * {@code approved} (and thus execute) at most once.
 */
@Slf4j
@Service
public class ApprovalWorkflowServiceImpl implements ApprovalWorkflowService {

    private final ApprovalRequestMapper approvalRequestMapper;
    private final ApprovalDecisionMapper approvalDecisionMapper;
    private final ApprovalPolicyMapper approvalPolicyMapper;
    private final AuditLogService auditLogService;
    private final Map<String, ApprovalActionExecutor> executors;

    public ApprovalWorkflowServiceImpl(ApprovalRequestMapper approvalRequestMapper,
                                       ApprovalDecisionMapper approvalDecisionMapper,
                                       ApprovalPolicyMapper approvalPolicyMapper,
                                       AuditLogService auditLogService,
                                       List<ApprovalActionExecutor> executorBeans) {
        this.approvalRequestMapper = approvalRequestMapper;
        this.approvalDecisionMapper = approvalDecisionMapper;
        this.approvalPolicyMapper = approvalPolicyMapper;
        this.auditLogService = auditLogService;
        this.executors = new HashMap<>();
        for (ApprovalActionExecutor executor : executorBeans) {
            this.executors.put(executorKey(executor.module(), executor.actionType()), executor);
        }
    }

    @Override
    public List<UUID> currentLevelApprovers(UUID requestId) {
        ApprovalRequestEntity request = approvalRequestMapper.selectById(requestId);
        if (request == null || !"pending".equals(request.getStatus())) {
            return Collections.emptyList();
        }
        List<List<UUID>> levels = resolveLevels(request);
        int idx = currentLevelIndex(request, levels);
        if (idx < 0 || idx >= levels.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(levels.get(idx));
    }

    @Override
    public List<ApprovalRequestEntity> pendingForApprover(UUID approverId) {
        LambdaQueryWrapper<ApprovalRequestEntity> query = new LambdaQueryWrapper<ApprovalRequestEntity>()
                .eq(ApprovalRequestEntity::getStatus, "pending")
                .isNotNull(ApprovalRequestEntity::getPolicyId);
        List<ApprovalRequestEntity> pending = approvalRequestMapper.selectList(query);
        List<ApprovalRequestEntity> awaiting = new ArrayList<>();
        for (ApprovalRequestEntity request : pending) {
            // The initiator can never be an approver of their own action (Req 12.1.6).
            if (approverId.equals(request.getInitiatedBy())) {
                continue;
            }
            List<List<UUID>> levels = resolveLevels(request);
            int idx = currentLevelIndex(request, levels);
            if (idx >= 0 && idx < levels.size() && levels.get(idx).contains(approverId)) {
                awaiting.add(request);
            }
        }
        return awaiting;
    }

    @Override
    @Transactional
    public ApprovalRequestEntity approve(UUID requestId, UUID approverId, String comment) {
        ApprovalRequestEntity request = loadPending(requestId);
        List<List<UUID>> levels = resolveLevels(request);
        int idx = authorizeApprover(request, approverId, levels);
        int level = idx + 1;

        // Record the level decision (Req 12.1.5).
        recordDecision(request.getId(), level, approverId, "approved", comment);

        int totalLevels = levels.size();
        if (level < totalLevels) {
            // More levels remain: advance to the next level in order, stay pending.
            request.setCurrentLevel(level + 1);
            request.setUpdatedAt(LocalDateTime.now());
            approvalRequestMapper.updateById(request);
            log.info("Approval request {} approved at level {}/{} by {}; advancing to level {}",
                    requestId, level, totalLevels, approverId, level + 1);
        } else {
            // Final level approved: complete and execute the gated action exactly once (Req 12.1.3).
            request.setStatus("approved");
            request.setResolvedAt(LocalDateTime.now());
            request.setUpdatedAt(LocalDateTime.now());
            approvalRequestMapper.updateById(request);
            log.info("Approval request {} fully approved at level {}/{} by {}; executing action {}:{}",
                    requestId, level, totalLevels, approverId, request.getModule(), request.getActionType());
            executeAction(request);
        }

        auditLogService.createLog(approverId, null, "APPROVE", "approval_request", request.getId(),
                Map.of("level", level, "comment", comment == null ? "" : comment));
        return request;
    }

    @Override
    @Transactional
    public ApprovalRequestEntity reject(UUID requestId, UUID approverId, String reason) {
        ApprovalRequestEntity request = loadPending(requestId);
        List<List<UUID>> levels = resolveLevels(request);
        int idx = authorizeApprover(request, approverId, levels);
        int level = idx + 1;

        // Record the rejection (Req 12.1.4).
        recordDecision(request.getId(), level, approverId, "rejected", reason);

        // Cancel the action: a rejected request is terminal and never executes (Req 12.1.4).
        request.setStatus("rejected");
        request.setRejectionReason(reason);
        request.setResolvedAt(LocalDateTime.now());
        request.setUpdatedAt(LocalDateTime.now());
        approvalRequestMapper.updateById(request);

        log.info("Approval request {} rejected at level {} by {}: {}", requestId, level, approverId, reason);
        auditLogService.createLog(approverId, null, "REJECT", "approval_request", request.getId(), reason);
        return request;
    }

    @Override
    @Transactional
    public int expireOverdue() {
        LambdaQueryWrapper<ApprovalRequestEntity> query = new LambdaQueryWrapper<ApprovalRequestEntity>()
                .eq(ApprovalRequestEntity::getStatus, "pending")
                .isNotNull(ApprovalRequestEntity::getExpiresAt)
                .lt(ApprovalRequestEntity::getExpiresAt, LocalDateTime.now());
        List<ApprovalRequestEntity> overdue = approvalRequestMapper.selectList(query);
        for (ApprovalRequestEntity request : overdue) {
            // Mark expired and cancel the action (Req 12.1.7).
            request.setStatus("expired");
            request.setResolvedAt(LocalDateTime.now());
            request.setUpdatedAt(LocalDateTime.now());
            approvalRequestMapper.updateById(request);
            auditLogService.createLog(request.getInitiatedBy(), null, "EXPIRE", "approval_request",
                    request.getId(), "Approval expired before all levels approved");
            log.info("Approval request {} expired (was at level {})", request.getId(), request.getCurrentLevel());
        }
        if (!overdue.isEmpty()) {
            log.info("Approval expiration sweep cancelled {} overdue request(s)", overdue.size());
        }
        return overdue.size();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private ApprovalRequestEntity loadPending(UUID requestId) {
        ApprovalRequestEntity request = approvalRequestMapper.selectById(requestId);
        if (request == null) {
            throw new BusinessException("APPROVAL_NOT_FOUND", "审批请求不存在: " + requestId);
        }
        // Guards exactly-once completion: only a still-pending request may transition.
        if (!"pending".equals(request.getStatus())) {
            throw new BusinessException("APPROVAL_INVALID_STATUS",
                    "审批请求当前状态不允许操作: " + request.getStatus());
        }
        // A pending request past its expiration cannot be acted on (Req 12.1.7).
        if (request.getExpiresAt() != null && request.getExpiresAt().isBefore(LocalDateTime.now())) {
            request.setStatus("expired");
            request.setResolvedAt(LocalDateTime.now());
            request.setUpdatedAt(LocalDateTime.now());
            approvalRequestMapper.updateById(request);
            throw new BusinessException("APPROVAL_EXPIRED", "审批请求已过期");
        }
        return request;
    }

    /**
     * Validates that {@code approverId} may decide on the request's current level
     * and is not the initiator, returning the zero-based current level index.
     */
    private int authorizeApprover(ApprovalRequestEntity request, UUID approverId, List<List<UUID>> levels) {
        // Self-approval prohibition (Req 12.1.6).
        if (approverId.equals(request.getInitiatedBy())) {
            throw new BusinessException("APPROVAL_SELF_FORBIDDEN",
                    "发起人不能审批自己发起的操作");
        }
        int idx = currentLevelIndex(request, levels);
        if (idx < 0 || idx >= levels.size()) {
            throw new BusinessException("APPROVAL_LEVEL_INVALID", "审批级别无效");
        }
        // Routing/sequencing enforcement: only an approver defined for the current
        // level may act, and only on the current level (Req 12.1.2, 12.1.5).
        if (!levels.get(idx).contains(approverId)) {
            throw new BusinessException("APPROVAL_NOT_AUTHORIZED",
                    "当前用户不是该审批级别的指定审批人");
        }
        return idx;
    }

    private void recordDecision(UUID requestId, int level, UUID approverId, String decision, String comment) {
        ApprovalDecisionEntity entity = ApprovalDecisionEntity.builder()
                .requestId(requestId)
                .level(level)
                .approverId(approverId)
                .decision(decision)
                .comment(comment)
                .build();
        approvalDecisionMapper.insert(entity);
    }

    /** The zero-based index of the request's current level (1-based {@code current_level}). */
    private int currentLevelIndex(ApprovalRequestEntity request, List<List<UUID>> levels) {
        int level = request.getCurrentLevel() == null ? 1 : request.getCurrentLevel();
        return level - 1;
    }

    /**
     * Resolves the ordered approval levels for a request from its governing
     * policy. Each element is the set of approver user ids authorized for that
     * level, in the policy's defined order (Req 12.1.5).
     */
    private List<List<UUID>> resolveLevels(ApprovalRequestEntity request) {
        if (request.getPolicyId() == null) {
            return Collections.emptyList();
        }
        ApprovalPolicyEntity policy = approvalPolicyMapper.selectById(request.getPolicyId());
        if (policy == null) {
            return Collections.emptyList();
        }
        List<UUID> approvers = parseUserIds(policy.getApproverUserIds());
        if (approvers.isEmpty()) {
            return Collections.emptyList();
        }
        if ("multi_level".equals(policy.getApprovalType())) {
            // One level per listed approver, in order.
            List<List<UUID>> levels = new ArrayList<>(approvers.size());
            for (UUID approver : approvers) {
                levels.add(List.of(approver));
            }
            return levels;
        }
        // Single level: any of the defined approvers may approve.
        return List.of(new ArrayList<>(approvers));
    }

    private List<UUID> parseUserIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<UUID> ids = new ArrayList<>(raw.size());
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                ids.add(UUID.fromString(value.trim()));
            } catch (IllegalArgumentException ex) {
                log.warn("Skipping non-UUID approver id '{}' in policy approver_user_ids", value);
            }
        }
        return ids;
    }

    /** Resumes the gated action via its registered executor, if any (Req 12.1.3). */
    private void executeAction(ApprovalRequestEntity request) {
        String key = executorKey(request.getModule(), request.getActionType());
        ApprovalActionExecutor executor = executors.get(key);
        if (executor == null) {
            log.info("No ApprovalActionExecutor registered for '{}'; approval {} recorded as completed without resume",
                    key, request.getId());
            return;
        }
        executor.execute(request);
    }

    private static String executorKey(String module, String actionType) {
        return module + ":" + actionType;
    }
}
