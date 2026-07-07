package com.adpilot.modules.approval.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ApprovalRequestDto {

    @NotBlank(message = "storeId is required")
    private String storeId;

    @NotBlank(message = "requestType is required")
    private String requestType;

    private String relatedEntityType;

    private String relatedEntityId;

    @NotBlank(message = "title is required")
    private String title;

    private String description;

    private String payload;

    private String riskLevel;
}
