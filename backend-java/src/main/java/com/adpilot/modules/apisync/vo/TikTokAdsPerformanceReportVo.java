package com.adpilot.modules.apisync.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A date-ranged TikTok Ads performance report: per-day rows plus the range
 * totals, shown in the TikTok Ads 报告 view. Mirrors
 * {@link GoogleAdsPerformanceReportVo}.
 */
@Data
@Builder
public class TikTokAdsPerformanceReportVo {

    private LocalDate from;
    private LocalDate to;

    /** Per-day metrics ordered ascending by date. */
    private List<TikTokAdsPerformanceRowVo> rows;

    private long totalImpressions;
    private long totalClicks;
    private BigDecimal totalCost;
    private double totalConversions;
    private BigDecimal totalConversionValue;
}
