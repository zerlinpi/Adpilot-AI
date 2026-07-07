package com.adpilot.modules.advertising.support;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Aggregated projection of {@code raw_advertised_product_reports} grouped by
 * campaign / ad group / SKU / ASIN. Column aliases map to these fields via
 * MyBatis map-underscore-to-camel-case.
 */
@Data
public class ProductAdAggRow {
    private String campaignName;
    private String adGroupName;
    private String sku;
    private String asin;
    private Long impressions;
    private Long clicks;
    private BigDecimal spend;
    private BigDecimal sales;
    private Long orders;
}
