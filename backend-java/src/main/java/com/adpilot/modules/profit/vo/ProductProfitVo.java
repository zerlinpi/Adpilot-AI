package com.adpilot.modules.profit.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProductProfitVo {

    private String id;
    private String sku;
    private String asin;
    private String productName;
    private int unitsSold;
    private double grossSales;
    private double organicSales;
    private double adSales;
    private double adSpend;
    private double amazonFees;
    private double fbaFees;
    private double cogs;
    private double inboundCost;
    private double refundCost;
    private double grossProfit;
    private double netProfit;
    private double netMargin;
    private double acos;
    private double tacos;
    private double breakEvenAcos;
}
