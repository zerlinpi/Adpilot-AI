package com.adpilot.modules.approval.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.approval.dto.ApprovalRequestDto;
import com.adpilot.modules.approval.service.ApprovalService;
import com.adpilot.modules.approval.vo.ApprovalRequestVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    /**
     * GET /api/approvals - List approval requests with optional filters.
     */
    @GetMapping
    public ApiResponse<PageResponse<ApprovalRequestVo>> listApprovalRequests(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<ApprovalRequestVo> result = approvalService.listApprovalRequests(storeId, status, riskLevel, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/approvals/{id} - Get approval request by ID.
     */
    @GetMapping("/{id}")
    public ApiResponse<ApprovalRequestVo> getApprovalRequest(@PathVariable String id) {
        ApprovalRequestVo approval = approvalService.getApprovalRequest(id);
        return ApiResponse.ok(approval);
    }

    /**
     * POST /api/approvals - Create a new approval request.
     */
    @PostMapping
    public ApiResponse<ApprovalRequestVo> createApprovalRequest(@Valid @RequestBody ApprovalRequestDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        ApprovalRequestVo approval = approvalService.createApprovalRequest(dto, userId);
        return ApiResponse.ok(approval);
    }

    /**
     * POST /api/approvals/{id}/approve - Approve a pending request.
     */
    @PostMapping("/{id}/approve")
    @RequirePermission("approval:approve_low")
    public ApiResponse<ApprovalRequestVo> approveRequest(@PathVariable String id) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        ApprovalRequestVo approval = approvalService.approveRequest(id, userId);
        return ApiResponse.ok(approval);
    }

    /**
     * POST /api/approvals/{id}/reject - Reject a pending request.
     */
    @PostMapping("/{id}/reject")
    @RequirePermission("approval:approve_low")
    public ApiResponse<ApprovalRequestVo> rejectRequest(
            @PathVariable String id,
            @RequestParam(required = false) String reason) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        ApprovalRequestVo approval = approvalService.rejectRequest(id, userId, reason);
        return ApiResponse.ok(approval);
    }

    /**
     * POST /api/approvals/{id}/watch - Watch an approval request.
     */
    @PostMapping("/{id}/watch")
    public ApiResponse<Void> watchRequest(@PathVariable String id) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        approvalService.watchRequest(id, userId);
        return ApiResponse.ok(null);
    }
}
