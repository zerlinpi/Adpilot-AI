package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdGroupVo {

    private String id;
    private String campaignId;
    private String campaignName;
    private String storeId;
    private String name;
    private String status;
    private double defaultBid;
    private int keywordCount;
    private double spend;
    private double sales;
    private int orders;
    private double acos;
    private String externalId;
    private String createdAt;
    private String updatedAt;
}
