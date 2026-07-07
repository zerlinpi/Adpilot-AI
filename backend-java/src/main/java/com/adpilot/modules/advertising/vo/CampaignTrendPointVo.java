package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

/**
 * One bucket of the campaign data-trend panel (Req 19.2), aggregated from
 * {@code performance_daily}. Each point carries the period label plus the
 * trend metrics: 花费 (spend), 销售额 (sales), 订单数 (orders), ACoS,
 * 点击成本 (CPC), and 订单成本 (cost per order). ACoS is computed with the shared
 * {@code AdMetrics} helper so the zero-denominator case never yields NaN.
 */
@Data
@Builder
public class CampaignTrendPointVo {

    /** Period label: {@code yyyy-MM-dd} (day), {@code yyyy-Www} (week), or {@code yyyy-MM} (month). */
    private String period;

    private double spend;
    private double sales;
    private int orders;
    private long impressions;
    private int clicks;

    /** Advertising Cost of Sales as a percentage. */
    private double acos;

    /** Cost per click = spend / clicks. */
    private double cpc;

    /** Cost per order = spend / orders. */
    private double costPerOrder;
}
