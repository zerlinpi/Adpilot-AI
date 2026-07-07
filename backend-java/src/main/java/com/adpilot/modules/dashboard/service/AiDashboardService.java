package com.adpilot.modules.dashboard.service;

import com.adpilot.modules.dashboard.vo.AiActionsVo;
import com.adpilot.modules.dashboard.vo.AiNotificationsSummaryVo;
import com.adpilot.modules.dashboard.vo.AiUsageVo;
import com.adpilot.modules.dashboard.vo.SalesOverviewVo;
import com.adpilot.modules.dashboard.vo.SalesTrendVo;

import java.time.LocalDate;

/**
 * Aggregation service backing the AI advertising dashboard panels (Req 18).
 *
 * <p>Every query is scoped to the requesting user's accessible stores and may
 * be further narrowed by {@code storeId} and {@code marketplace}; monetary
 * metrics are normalized to the requested reporting {@code currency} and bounded
 * by the {@code [start, end]} date range. Efficiency metrics (ACoS / TACoS / AI
 * coverage) are computed by the pure {@code AdMetrics} helper. Aggregation draws
 * from {@code performance_daily}, {@code orders}, {@code campaigns}, and
 * {@code automation_executions}.</p>
 */
public interface AiDashboardService {

    /** Sales Overview metrics with period-over-period deltas (Req 18.1). */
    SalesOverviewVo getSalesOverview(String storeId, String marketplace, String currency,
                                     LocalDate start, LocalDate end);

    /** Sales trend buckets at the given granularity (day/week/month) (Req 18.2). */
    SalesTrendVo getSalesTrend(String storeId, String marketplace, String currency,
                               LocalDate start, LocalDate end, String granularity);

    /** AI Actions panel: standard SP action set with counts and impact (Req 18.3). */
    AiActionsVo getAiActions(String storeId, String marketplace, String currency,
                             LocalDate start, LocalDate end);

    /** AI Usage panel: coverage %, AI ad spend, AI ad sales (Req 18.4). */
    AiUsageVo getAiUsage(String storeId, String marketplace, String currency,
                         LocalDate start, LocalDate end);

    /** AI Notifications summary: four categories with pending counts (Req 18.5). */
    AiNotificationsSummaryVo getAiNotificationsSummary(String storeId, String marketplace,
                                                       LocalDate start, LocalDate end);
}
