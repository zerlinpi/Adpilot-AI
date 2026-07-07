package com.adpilot.modules.automation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Payload for {@code POST /api/automation/rule-templates/bulk} — applies a bulk
 * operation to the selected templates and returns a per-item result (Req 25.4).
 */
@Data
public class RuleTemplateBulkDto {

    @NotEmpty(message = "ids must not be empty")
    private List<String> ids;

    /** enable | disable | delete */
    @NotBlank(message = "operation is required")
    private String operation;
}
