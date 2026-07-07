package com.adpilot.modules.dashboard.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Aggregated metric totals over a set of stores. Monetary {@code revenue} is
 * expressed in the reporting currency and includes only the amounts that could
 * be converted; currency-independent counts ({@code orderCount},
 * {@code unitsSold}) always reflect every accessible store (Req 5.1.1,
 * 5.1.4, 5.1.5).
 */
@Data
@Builder
public class MetricTotalsVo {

    /** Total revenue expressed in the reporting currency (convertible amounts only). */
    @Builder.Default
    private BigDecimal revenue = BigDecimal.ZERO;

    /** Number of distinct orders across the aggregated stores. */
    @Builder.Default
    private long orderCount = 0L;

    /** Total units sold across the aggregated stores. */
    @Builder.Default
    private long unitsSold = 0L;
}
