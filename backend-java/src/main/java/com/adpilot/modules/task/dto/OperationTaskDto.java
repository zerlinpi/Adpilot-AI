package com.adpilot.modules.task.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OperationTaskDto {

    private String storeId;

    @NotBlank(message = "Title is required")
    private String title;

    private String description;

    @NotBlank(message = "Task type is required")
    private String taskType;

    private String sourceType;
    private String relatedEntityType;
    private String relatedEntityId;
    private String priority;
    private String riskLevel;
    private String status;
    private String assignedToUserId;
    private LocalDateTime dueDate;
    private String expectedImpact;
    private String suggestedAction;
    private Boolean approvalRequired;
}
