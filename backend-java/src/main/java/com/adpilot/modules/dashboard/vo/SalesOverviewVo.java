package com.adpilot.modules.dashboard.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Sales Overview (销售总览) panel of the AI advertising dashboard (Req 18.1).
 * Each metric is reported as a value with its period-over-period delta, scoped
 * to the active store, marketplace, currency, and selected date range. ACoS and
 * TACoS are computed by the pure {@code AdMetrics} helper.
 */
@Data
@Builder
public class SalesOverviewVo {

    /** Reporting currency the monetary metrics are expressed in. */
    private String currency;

    /** 总销售额 — total sales (orders-derived) with delta. */
    private MetricDeltaVo totalSales;

    /** 广告花费 — ad spend with delta. */
    private MetricDeltaVo adSpend;

    /** 广告销售额 — ad sales with delta. */
    private MetricDeltaVo adSales;

    /** 广告订单数 — ad orders with delta. */
    private MetricDeltaVo adOrders;

    /** TACoS percentage with delta (spend / total sales). */
    private MetricDeltaVo tacos;

    /** ACoS percentage with delta (spend / ad sales). */
    private MetricDeltaVo acos;
}
