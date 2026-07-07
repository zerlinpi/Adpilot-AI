package com.adpilot.modules.automation.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Payload for {@code POST /api/automation/rule-templates/{id}/links} — links one
 * or more objects (campaigns / targets / keywords) to a template (Req 25.3).
 */
@Data
public class RuleTemplateLinkDto {

    @NotEmpty(message = "at least one link is required")
    private List<Link> links;

    @Data
    public static class Link {
        /** campaign | target | keyword */
        private String objectType;
        private String objectId;
    }
}
