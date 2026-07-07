package com.adpilot.modules.dashboard.vo;

import lombok.Builder;
import lombok.Data;

/**
 * One of the four AI_Notification categories in the notifications summary panel
 * (Req 18.5), with its pending (待处理) count.
 */
@Data
@Builder
public class AiNotificationCategoryVo {

    /** Stable category key, e.g. {@code core_ops_attention}. */
    private String key;

    /** Human-readable Chinese label, e.g. 广告运营核心关注. */
    private String label;

    /** Number of pending notifications in this category. */
    @Builder.Default
    private long pendingCount = 0L;
}
