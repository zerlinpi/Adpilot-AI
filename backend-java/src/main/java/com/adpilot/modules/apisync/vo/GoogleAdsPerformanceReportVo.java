package com.adpilot.modules.apisync.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A date-ranged Google Ads performance report: per-day rows plus the range
 * totals, shown in the Google Ads 报告 view (platform-workspace-rbac Req 6.4).
 */
@Data
@Builder
public class GoogleAdsPerformanceReportVo {

    private LocalDate from;
    private LocalDate to;

    /** Per-day metrics ordered ascending by date. */
    private List<GoogleAdsPerformanceRowVo> rows;

    private long totalImpressions;
    private long totalClicks;
    private BigDecimal totalCost;
    private double totalConversions;
    private BigDecimal totalConversionValue;
}
