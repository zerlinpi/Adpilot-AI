package com.adpilot.modules.automation.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Per-item result of a rule-template bulk operation (Req 25.4).
 */
@Data
@Builder
public class RuleTemplateBulkResultVo {

    private String id;
    private boolean success;
    private String message;
}
