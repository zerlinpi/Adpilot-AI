package com.adpilot.modules.apisync.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One day's aggregated Google Ads performance metrics within a requested date
 * range (platform-workspace-rbac Req 6.2, 6.4).
 */
@Data
@Builder
public class GoogleAdsPerformanceRowVo {

    private LocalDate date;
    private long impressions;
    private long clicks;
    private BigDecimal cost;
    private double conversions;
    private BigDecimal conversionValue;
}
