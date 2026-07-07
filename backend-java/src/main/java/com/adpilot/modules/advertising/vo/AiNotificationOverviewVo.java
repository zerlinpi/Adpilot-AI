package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AI Notifications page overview (Req 23.1, 23.2, 23.6): the four notification
 * categories, each with its pending/closed counts and its pending/closed item
 * lists, so the page can render counts and both lists from a single request.
 */
@Data
@Builder
public class AiNotificationOverviewVo {

    @Builder.Default
    private List<Category> categories = new ArrayList<>();

    /** One AI_Notification category with its counts and item lists. */
    @Data
    @Builder
    public static class Category {

        /** Stable category key, e.g. {@code core_ops}. */
        private String key;

        /** Localized category label, e.g. {@code 广告运营核心关注}. */
        private String label;

        /** Number of pending(待处理) items in this category. */
        private int pendingCount;

        /** Number of closed(已结束) items in this category. */
        private int closedCount;

        /** Pending item list (待处理). */
        @Builder.Default
        private List<AiNotificationVo> pending = new ArrayList<>();

        /** Closed item list (已结束). */
        @Builder.Default
        private List<AiNotificationVo> closed = new ArrayList<>();
    }
}
