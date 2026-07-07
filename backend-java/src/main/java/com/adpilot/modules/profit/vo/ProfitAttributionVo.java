package com.adpilot.modules.profit.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProfitAttributionVo {

    private String campaignId;
    private String campaignName;
    private String goalName;
    private String sku;
    private String asin;
    private double adSpend;
    private double adSales;
    private double organicSales;
    private double totalSales;
    private double grossProfit;
    private double netProfit;
    private double acos;
    private double tacos;
    private double breakEvenAcos;
    private double profitAfterAds;
    private String aiRecommendation;
}
