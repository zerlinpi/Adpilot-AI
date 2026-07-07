package com.adpilot.modules.task.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OperationTaskVo {

    private String id;
    private String storeId;
    private String title;
    private String description;
    private String taskType;
    private String sourceType;
    private String relatedEntityType;
    private String relatedEntityId;
    private String priority;
    private String riskLevel;
    private String status;
    private String assignedToUserId;
    private String dueDate;
    private String expectedImpact;
    private String suggestedAction;
    private Boolean approvalRequired;
    private String completedAt;
    private String createdAt;
}
