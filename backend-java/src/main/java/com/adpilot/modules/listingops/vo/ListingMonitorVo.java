package com.adpilot.modules.listingops.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ListingMonitorVo {

    private String id;
    private String storeId;
    private String asin;
    private String sku;
    private BigDecimal overallScore;
    private BigDecimal titleScore;
    private BigDecimal bulletScore;
    private BigDecimal descriptionScore;
    private BigDecimal imageScore;
    private BigDecimal keywordScore;
    private String issues;
    private String recommendations;
    private String checkedAt;
    private String createdAt;
}
