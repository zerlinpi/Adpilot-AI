package com.adpilot.modules.approval.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ApprovalRequestVo {

    private String id;
    private String storeId;
    private String requesterId;
    private String approverId;
    private String requestType;
    private String relatedEntityType;
    private String relatedEntityId;
    private String title;
    private String description;
    private String payload;
    private String riskLevel;
    private String status;
    private String rejectionReason;
    private String resolvedAt;
    private String expiresAt;
    private String createdAt;
    private String updatedAt;
}
