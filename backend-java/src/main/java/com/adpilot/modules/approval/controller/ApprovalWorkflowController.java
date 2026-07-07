package com.adpilot.modules.approval.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.approval.entity.ApprovalRequestEntity;
import com.adpilot.modules.approval.service.ApprovalWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Policy-driven approval workflow endpoints (Req 12.1) for governed actions gated
 * by {@code @RequiresApproval} / {@code ApprovalAspect}.
 *
 * <p>Separate from {@link ApprovalController}, which serves the store-centric
 * approval requests. These endpoints let an approver see the requests routed to
 * them (Req 12.1.2) and record per-level approve/reject decisions
 * (Req 12.1.3–12.1.6).
 */
@Slf4j
@RestController
@RequestMapping("/api/approvals/workflow")
@RequiredArgsConstructor
public class ApprovalWorkflowController {

    private final ApprovalWorkflowService approvalWorkflowService;

    /** GET /api/approvals/workflow/inbox - requests awaiting the current user's decision. */
    @GetMapping("/inbox")
    public ApiResponse<List<ApprovalRequestEntity>> inbox() {
        UUID userId = UUID.fromString(SecurityUtils.getCurrentUserId());
        return ApiResponse.ok(approvalWorkflowService.pendingForApprover(userId));
    }

    /** GET /api/approvals/workflow/{id}/approvers - approvers for the request's current level. */
    @GetMapping("/{id}/approvers")
    public ApiResponse<List<UUID>> currentLevelApprovers(@PathVariable String id) {
        return ApiResponse.ok(approvalWorkflowService.currentLevelApprovers(parseId(id)));
    }

    /** POST /api/approvals/workflow/{id}/approve - approve the current level. */
    @PostMapping("/{id}/approve")
    @RequirePermission("approval:approve_low")
    public ApiResponse<ApprovalRequestEntity> approve(
            @PathVariable String id,
            @RequestParam(required = false) String comment) {
        UUID userId = UUID.fromString(SecurityUtils.getCurrentUserId());
        return ApiResponse.ok(approvalWorkflowService.approve(parseId(id), userId, comment));
    }

    /** POST /api/approvals/workflow/{id}/reject - reject the request. */
    @PostMapping("/{id}/reject")
    @RequirePermission("approval:approve_low")
    public ApiResponse<ApprovalRequestEntity> reject(
            @PathVariable String id,
            @RequestParam(required = false) String reason) {
        UUID userId = UUID.fromString(SecurityUtils.getCurrentUserId());
        return ApiResponse.ok(approvalWorkflowService.reject(parseId(id), userId, reason));
    }

    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new com.adpilot.common.exception.BusinessException(
                    "INVALID_APPROVAL_ID", "Invalid approval request id: " + id);
        }
    }
}
