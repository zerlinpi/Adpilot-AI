package com.adpilot.modules.profit.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ProfitDashboardVo {

    private double totalSales;
    private double adSales;
    private double organicSales;
    private double adSpend;
    private double grossProfit;
    private double netProfit;
    private double netMargin;
    private double acos;
    private double tacos;
    private double roas;
    private List<ProductProfitVo> topProfitableSkus;
    private List<ProductProfitVo> profitLosingSkus;
    private String aiSummary;
}
