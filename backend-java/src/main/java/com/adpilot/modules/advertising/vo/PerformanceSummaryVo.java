package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PerformanceSummaryVo {

    private double spend;
    private double sales;
    private int orders;
    private long impressions;
    private int clicks;
    private double acos;
    private double roas;
    private double conversionRate;
    private double avgCpc;
}
