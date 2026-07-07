package com.adpilot.modules.dashboard.vo;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of the all-stores aggregated view (Req 5.1). Combines the converted
 * {@link #totals} over the stores the user may access, the per-store
 * {@link #byStore} breakdown, and {@link #currencySubtotals} that surface
 * amounts which could not be converted with an {@code unconverted} flag rather
 * than forcing a conversion (Req 5.1.1&ndash;5.1.5).
 */
@Data
@Builder
public class AggregatedMetricsVo {

    /** The reporting currency the totals are expressed in. */
    private String reportingCurrency;

    /** Aggregated totals over the accessible stores, in the reporting currency. */
    private MetricTotalsVo totals;

    /** Per-store metric breakdown (Req 5.1.2). */
    @Builder.Default
    private List<StoreMetricsVo> byStore = new ArrayList<>();

    /** Per-currency subtotals, flagging any that could not be converted (Req 5.1.5). */
    @Builder.Default
    private List<CurrencySubtotalVo> currencySubtotals = new ArrayList<>();
}
