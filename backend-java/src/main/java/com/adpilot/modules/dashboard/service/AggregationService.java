package com.adpilot.modules.dashboard.service;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.modules.dashboard.vo.AggregatedMetricsVo;
import com.adpilot.modules.dashboard.vo.DateRange;

/**
 * Cross-store aggregation for the all-stores view (Req 5.1). Aggregates
 * dashboard/profit/order metrics across exactly the stores a user is permitted
 * to access, returning both overall totals and a per-store breakdown, and
 * normalizing currencies to a single reporting currency where a rate exists.
 */
public interface AggregationService {

    /**
     * Aggregate metrics across the stores {@code user} may access over
     * {@code dateRange}.
     *
     * <p>Only accessible stores contribute to the result; inaccessible stores are
     * excluded from both the totals and the per-store breakdown (Req 5.1.1,
     * 5.1.2, 5.1.3). Amounts are converted to the reporting currency through
     * {@code CurrencyService} where a rate exists (Req 5.1.4); where no rate is
     * available the amount is presented as a per-currency subtotal flagged
     * {@code unconverted} rather than being force-converted, so aggregation is
     * not blocked (Req 5.1.5).</p>
     *
     * @param user      the requesting user whose data scope bounds the stores
     * @param dateRange the inclusive date range to aggregate over
     * @return the aggregated totals, per-store breakdown, and per-currency subtotals
     */
    AggregatedMetricsVo aggregate(CurrentUser user, DateRange dateRange);
}
