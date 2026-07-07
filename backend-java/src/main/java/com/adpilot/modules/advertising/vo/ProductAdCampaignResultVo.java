package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response view for {@code POST /api/product-ads/campaign} (Req 1.10).
 *
 * <p>Surfaces the newly created campaign's id, the product (ASIN / local id) it
 * was linked to, and the campaign's resolved Execution_Mode so the per-product
 * ad modal can confirm the outcome without a follow-up fetch.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductAdCampaignResultVo {

    /** The newly created keyword campaign's id. */
    @JsonProperty("campaign_id")
    private String campaignId;

    /** Local product id the campaign was linked to (may be null if linked by ASIN only). */
    @JsonProperty("product_id")
    private String productId;

    /** Parent ASIN the campaign was linked to (may be null if linked by product id only). */
    @JsonProperty("parent_asin")
    private String parentAsin;

    /** The campaign's resolved Execution_Mode ({@code observe_only} by default, Req 1.3). */
    @JsonProperty("execution_mode")
    private String executionMode;
}
