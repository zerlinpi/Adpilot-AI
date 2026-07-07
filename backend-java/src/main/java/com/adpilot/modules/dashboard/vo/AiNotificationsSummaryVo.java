package com.adpilot.modules.dashboard.vo;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AI Notifications summary panel (Req 18.5): the four AI_Notification
 * categories, each with its pending count.
 *
 * <p>Counts are read live from the {@code ai_notifications} table.
 * {@link #available} is {@code true} whenever the table is queryable (the
 * normal case); it degrades to {@code false} only if the query itself fails,
 * in which case every category reports a pending count of 0 so the endpoint
 * never breaks the rest of the dashboard.
 */
@Data
@Builder
public class AiNotificationsSummaryVo {

    /**
     * {@code true} when the {@code ai_notifications} counts are real;
     * {@code false} only when the backing query failed (counts are all 0).
     */
    @Builder.Default
    private boolean available = false;

    /** The four notification categories with their pending counts. */
    @Builder.Default
    private List<AiNotificationCategoryVo> categories = new ArrayList<>();
}
