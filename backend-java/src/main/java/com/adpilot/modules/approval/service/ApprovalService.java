package com.adpilot.modules.approval.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.approval.dto.ApprovalRequestDto;
import com.adpilot.modules.approval.vo.ApprovalRequestVo;

public interface ApprovalService {

    /**
     * List approval requests with optional filters and pagination.
     */
    PageResponse<ApprovalRequestVo> listApprovalRequests(String storeId, String status, String riskLevel, int page, int pageSize);

    /**
     * Get a single approval request by ID.
     */
    ApprovalRequestVo getApprovalRequest(String id);

    /**
     * Create a new approval request.
     */
    ApprovalRequestVo createApprovalRequest(ApprovalRequestDto dto, String userId);

    /**
     * Approve a pending approval request.
     */
    ApprovalRequestVo approveRequest(String id, String userId);

    /**
     * Reject a pending approval request with a reason.
     */
    ApprovalRequestVo rejectRequest(String id, String userId, String reason);

    /**
     * Add a user as a watcher on an approval request.
     */
    void watchRequest(String id, String userId);
}
