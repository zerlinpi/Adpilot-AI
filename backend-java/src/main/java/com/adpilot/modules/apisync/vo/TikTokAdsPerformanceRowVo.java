package com.adpilot.modules.apisync.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One day's aggregated TikTok Ads performance metrics within a requested date
 * range. Mirrors {@link GoogleAdsPerformanceRowVo}.
 */
@Data
@Builder
public class TikTokAdsPerformanceRowVo {

    private LocalDate date;
    private long impressions;
    private long clicks;
    private BigDecimal cost;
    private double conversions;
    private BigDecimal conversionValue;
}
