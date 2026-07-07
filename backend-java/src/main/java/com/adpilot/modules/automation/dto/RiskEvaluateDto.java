package com.adpilot.modules.automation.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RiskEvaluateDto {

    @NotBlank(message = "storeId is required")
    private String storeId;

    @NotBlank(message = "actionType is required")
    private String actionType;

    private String entityType;

    private String entityId;

    private String proposedChanges;
}
