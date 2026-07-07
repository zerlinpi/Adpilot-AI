package com.adpilot.modules.approval.aspect;

import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.approval.annotation.RequiresApproval;
import com.adpilot.modules.approval.entity.ApprovalPolicyEntity;
import com.adpilot.modules.approval.entity.ApprovalRequestEntity;
import com.adpilot.modules.approval.mapper.ApprovalPolicyMapper;
import com.adpilot.modules.approval.mapper.ApprovalRequestMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Enforces {@link RequiresApproval} on annotated methods (Req 12.1.1).
 *
 * <p>Mirrors {@code PermissionAspect}: an around advice that, before the method
 * runs, looks for an enabled {@code approval_policies} row for the caller's
 * organization matching the action's module/action type and requiring approval
 * (i.e. its threshold is met). When such a policy exists, the aspect persists an
 * {@code approval_request} in the {@code pending} state and short-circuits the
 * method, returning {@code null} instead of executing it. When no governing
 * policy applies, the method executes immediately.
 *
 * <p>Routing the pending request to approvers, level sequencing, self-approval
 * prohibition, and expiration are the responsibility of the ApprovalService
 * (task 24.2); this aspect only performs the gate-and-persist step.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ApprovalAspect {

    private final ApprovalPolicyMapper approvalPolicyMapper;
    private final ApprovalRequestMapper approvalRequestMapper;
    private final ObjectMapper objectMapper;

    @Around("@annotation(requiresApproval)")
    public Object gate(ProceedingJoinPoint joinPoint, RequiresApproval requiresApproval)
            throws Throwable {

        String module = requiresApproval.module();
        String action = requiresApproval.action();

        // Without an authenticated user we cannot attribute or route an approval,
        // so the action proceeds (authentication is enforced earlier by the filter chain).
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        if (userId == null) {
            return joinPoint.proceed();
        }

        ApprovalPolicyEntity policy = findGoverningPolicy(module, action);
        if (policy == null) {
            // No enabled policy / threshold not met -> execute immediately (Req 12.1.1).
            return joinPoint.proceed();
        }

        // Policy threshold met -> place the action in a pending-approval state
        // instead of executing it (Req 12.1.1).
        ApprovalRequestEntity request = ApprovalRequestEntity.builder()
                .policyId(policy.getId())
                .module(module)
                .actionType(action)
                .payload(serializeArgs(joinPoint.getArgs()))
                .initiatedBy(UUID.fromString(userId))
                .status("pending")
                .currentLevel(1)
                .build();
        approvalRequestMapper.insert(request);

        log.info("Action '{}/{}' gated by approval policy {}; created pending approval_request {}",
                module, action, policy.getId(), request.getId());

        // Short-circuit: the action is not executed until the approval completes.
        return null;
    }

    /**
     * Finds the enabled approval policy that governs this action for the current
     * organization, or {@code null} when none applies (no gating). A policy
     * governs the action when it is enabled, matches the org/module/action type,
     * and actually requires approval (its {@code approval_type} is not
     * {@code "none"}).
     */
    private ApprovalPolicyEntity findGoverningPolicy(String module, String action) {
        String orgId = SecurityUtils.getCurrentOrgId();
        if (orgId == null) {
            return null;
        }
        LambdaQueryWrapper<ApprovalPolicyEntity> query = new LambdaQueryWrapper<ApprovalPolicyEntity>()
                .eq(ApprovalPolicyEntity::getOrgId, UUID.fromString(orgId))
                .eq(ApprovalPolicyEntity::getModule, module)
                .eq(ApprovalPolicyEntity::getActionType, action)
                .eq(ApprovalPolicyEntity::getEnabled, true)
                .ne(ApprovalPolicyEntity::getApprovalType, "none")
                .last("LIMIT 1");
        return approvalPolicyMapper.selectOne(query);
    }

    /** Serializes the intercepted method arguments to JSON for the request payload. */
    private String serializeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            log.warn("Failed to serialize approval payload; storing null", e);
            return null;
        }
    }
}
