package com.adpilot.modules.finance.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class FinancialSummaryVo {

    private String id;
    private String storeId;
    private String summaryType;
    private String periodStart;
    private String periodEnd;
    private BigDecimal totalRevenue;
    private BigDecimal totalCost;
    private BigDecimal totalFees;
    private BigDecimal grossProfit;
    private BigDecimal netProfit;
    private String currency;
    private String details;
    private String createdAt;
    private String updatedAt;
}
