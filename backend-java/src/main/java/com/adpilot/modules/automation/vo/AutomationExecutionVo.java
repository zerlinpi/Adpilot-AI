package com.adpilot.modules.automation.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AutomationExecutionVo {

    private String id;
    private String storeId;
    private String source;
    private String entityType;
    private String entityId;
    private String actionType;
    private String beforeSnapshot;
    private String afterSnapshot;
    private String riskLevel;
    private String approvalRequestId;
    private String taskId;
    private String status;
    private String errorMessage;
    private String createdAt;
    private String updatedAt;
}
