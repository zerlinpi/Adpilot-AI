package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/**
 * One AI Notification work item rendered in a category's pending(待处理) or
 * closed(已结束) list (Req 23.2). Carries the category, title, optional
 * structured detail, subject, lifecycle state, and resolution.
 */
@Data
@Builder
public class AiNotificationVo {

    private String id;
    private String storeId;

    /** Category key: core_ops | one_click_optimize | high_potential | target_correction. */
    private String category;

    private String title;

    /** Structured payload (proposed change, affected object, metrics); may be {@code null}. */
    private JsonNode detail;

    /** Campaign / target id the item concerns; may be {@code null}. */
    private String subjectId;

    /** Lifecycle state: pending | closed. */
    private String state;

    /** Disposition once closed: applied | confirmed | rejected | dismissed; {@code null} while pending. */
    private String resolution;

    private String createdAt;
    private String closedAt;
}
