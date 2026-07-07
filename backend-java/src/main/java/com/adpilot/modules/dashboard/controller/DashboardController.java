package com.adpilot.modules.dashboard.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.modules.dashboard.service.AiDashboardService;
import com.adpilot.modules.dashboard.service.DashboardService;
import com.adpilot.modules.dashboard.vo.AiActionsVo;
import com.adpilot.modules.dashboard.vo.AiNotificationsSummaryVo;
import com.adpilot.modules.dashboard.vo.AiUsageVo;
import com.adpilot.modules.dashboard.vo.DashboardSummaryVo;
import com.adpilot.modules.dashboard.vo.SalesOverviewVo;
import com.adpilot.modules.dashboard.vo.SalesTrendVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final AiDashboardService aiDashboardService;

    /**
     * GET /api/dashboard/summary - Get dashboard summary.
     */
    @GetMapping("/dashboard/summary")
    public ApiResponse<DashboardSummaryVo> getDashboardSummary() {
        DashboardSummaryVo summary = dashboardService.getSummary();
        return ApiResponse.ok(summary);
    }

    /**
     * GET /api/command-center - Alias for dashboard summary.
     */
    @GetMapping("/command-center")
    public ApiResponse<DashboardSummaryVo> getCommandCenter() {
        DashboardSummaryVo summary = dashboardService.getSummary();
        return ApiResponse.ok(summary);
    }

    /**
     * GET /api/dashboard/sales-overview - Sales Overview panel (Req 18.1).
     * Store/marketplace/currency/date-range scoped.
     */
    @GetMapping("/dashboard/sales-overview")
    public ApiResponse<SalesOverviewVo> getSalesOverview(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String marketplace,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(
                aiDashboardService.getSalesOverview(storeId, marketplace, currency, startDate, endDate));
    }

    /**
     * GET /api/dashboard/sales-trend - Sales trend chart (Req 18.2).
     * Supports a day/week/month granularity toggle.
     */
    @GetMapping("/dashboard/sales-trend")
    public ApiResponse<SalesTrendVo> getSalesTrend(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String marketplace,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false, defaultValue = "day") String granularity) {
        return ApiResponse.ok(
                aiDashboardService.getSalesTrend(storeId, marketplace, currency, startDate, endDate, granularity));
    }

    /**
     * GET /api/dashboard/ai-actions - AI Actions panel (Req 18.3).
     */
    @GetMapping("/dashboard/ai-actions")
    public ApiResponse<AiActionsVo> getAiActions(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String marketplace,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(
                aiDashboardService.getAiActions(storeId, marketplace, currency, startDate, endDate));
    }

    /**
     * GET /api/dashboard/ai-usage - AI Usage panel (Req 18.4).
     */
    @GetMapping("/dashboard/ai-usage")
    public ApiResponse<AiUsageVo> getAiUsage(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String marketplace,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(
                aiDashboardService.getAiUsage(storeId, marketplace, currency, startDate, endDate));
    }

    /**
     * GET /api/dashboard/ai-notifications-summary - AI Notifications summary (Req 18.5).
     * Returns the four categories with live pending counts from the
     * ai_notifications table, scoped by store/marketplace and the date window.
     */
    @GetMapping("/dashboard/ai-notifications-summary")
    public ApiResponse<AiNotificationsSummaryVo> getAiNotificationsSummary(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String marketplace,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(
                aiDashboardService.getAiNotificationsSummary(storeId, marketplace, startDate, endDate));
    }
}
