package com.adpilot.modules.review.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ListingQualityCheckVo {

    private String id;
    private String storeId;
    private String asin;
    private String sku;
    private Double overallScore;
    private Double titleScore;
    private Double bulletScore;
    private Double descriptionScore;
    private Double imageScore;
    private Double keywordScore;
    private String issues;
    private String recommendations;
    private String checkedAt;
    private String createdAt;
}
