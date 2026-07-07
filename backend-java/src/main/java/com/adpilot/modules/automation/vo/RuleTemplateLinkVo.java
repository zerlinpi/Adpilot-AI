package com.adpilot.modules.automation.vo;

import lombok.Builder;
import lombok.Data;

/**
 * One linked object of an automation rule template (Req 25.3) for the
 * "应用到 / 关联对象" panel on the Automation Rules page. Carries the link row id
 * (so it can be unlinked), the object type / id, and a human-readable display
 * name resolved from the underlying campaign / target / keyword.
 */
@Data
@Builder
public class RuleTemplateLinkVo {

    /** Identifier of the {@code automation_rule_template_links} row. */
    private String id;

    /** Linked object type: {@code campaign} | {@code target} | {@code keyword}. */
    private String objectType;

    /** Identifier of the linked object. */
    private String objectId;

    /** Resolved display name (campaign name / keyword text / target value), best-effort. */
    private String objectName;

    private String createdAt;
}
