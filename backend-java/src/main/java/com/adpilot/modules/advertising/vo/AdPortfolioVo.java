package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Ad Portfolio row for the All Ad Portfolios page (Req 20.1). Carries the
 * portfolio's own attributes plus the rollup metrics aggregated across its
 * member campaigns: targeting status (投放状态), store (店铺), start/end dates,
 * budget type (预算类型), budget (预算), campaign count (广告活动数量),
 * impressions (曝光量), clicks (点击量), click-through rate (点击率),
 * spend (花费), CPC (点击成本), orders (订单数), and sales (销售额).
 */
@Data
@Builder
public class AdPortfolioVo {

    private String id;
    private String storeId;
    private String name;

    /** Targeting status (投放状态). */
    private String state;

    /** Budget type (预算类型): {@code none} => 无预算上限 | {@code recurring} | {@code date_range}. */
    private String budgetType;

    /** Budget cap (预算); {@code null} when the portfolio has no budget cap (无预算上限). */
    private Double budget;

    private String startDate;
    private String endDate;
    private String externalId;

    // ----- Rollup metrics across member campaigns (Req 20.1) -----

    /** Number of campaigns in the portfolio (广告活动数量). */
    private int campaignCount;

    /** Impressions (曝光量). */
    private long impressions;

    /** Clicks (点击量). */
    private int clicks;

    /** Click-through rate (点击率) = clicks / impressions, as a percentage. */
    private double ctr;

    /** Spend (花费). */
    private double spend;

    /** Cost per click (点击成本) = spend / clicks. */
    private double cpc;

    /** Orders (订单数). */
    private int orders;

    /** Sales (销售额). */
    private double sales;

    private String createdAt;
    private String updatedAt;
}
