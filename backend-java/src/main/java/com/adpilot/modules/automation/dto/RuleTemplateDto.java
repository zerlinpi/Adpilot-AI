package com.adpilot.modules.automation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Create/update payload for an automation rule template (Req 25.2). The
 * condition and action are received as structured JSON and validated/serialized
 * by the service. {@code storeId} is required on create; {@code status} is
 * optional (defaults to {@code enabled}).
 */
@Data
public class RuleTemplateDto {

    @NotBlank(message = "storeId is required")
    private String storeId;

    @NotBlank(message = "name is required")
    private String name;

    @NotBlank(message = "templateType is required")
    private String templateType;

    @NotNull(message = "condition is required")
    private JsonNode condition;

    @NotNull(message = "action is required")
    private JsonNode action;

    private String status;
}
