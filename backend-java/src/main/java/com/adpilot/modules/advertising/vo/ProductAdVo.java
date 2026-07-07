package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProductAdVo {

    private String asin;
    private String sku;
    private String campaignName;
    private String adGroupName;
    private long impressions;
    private int clicks;
    private double spend;
    private double sales;
    private int orders;
    private double acos;
    private double ctr;
    private double cvr;
}
