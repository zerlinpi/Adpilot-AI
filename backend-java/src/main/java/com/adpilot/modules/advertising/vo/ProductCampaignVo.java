package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Per-product view of an advertising campaign associated with a single product
 * (ASIN / local product id), assembled for the product-centric ad data surface
 * (Req 2.1, 2.2, 2.3).
 *
 * <p>The campaign is associated with the product through
 * {@code campaign_product_links} (and the campaign's resolved
 * {@code parentAsin}); its performance metrics are aggregated from
 * {@code performance_daily}. The VO always carries the five headline metrics
 * required by Req 2.2 — spend, clicks, orders, sales and ACoS — and faithfully
 * passes through the source {@code data_status} (Req 2.3): {@code preliminary}
 * (recent / attribution-pending) or {@code finalized}. {@code dataStatus} is
 * {@code null} only when no performance row exists for the campaign yet.</p>
 */
@Data
@Builder
public class ProductCampaignVo {

    /** The associated campaign's id. */
    private String campaignId;

    /** The associated campaign's name. */
    private String campaignName;

    /** Parent ASIN the campaign is linked to (from {@code campaign_product_links}); may be null. */
    private String parentAsin;

    /** Local product id the campaign is linked to (from {@code campaign_product_links}); may be null. */
    private String productId;

    /** Campaign status (e.g. {@code enabled} / {@code paused}). */
    private String status;

    /** Whether the campaign is under AI hosting. */
    private boolean hostingEnabled;

    /** Total ad spend aggregated from {@code performance_daily} (Req 2.2). */
    private double spend;

    /** Total clicks aggregated from {@code performance_daily} (Req 2.2). */
    private int clicks;

    /** Total orders aggregated from {@code performance_daily} (Req 2.2). */
    private int orders;

    /** Total ad-attributed sales aggregated from {@code performance_daily} (Req 2.2). */
    private double sales;

    /** ACoS as a percentage (spend / sales * 100), 0 when there are no sales (Req 2.2). */
    private double acos;

    /**
     * Faithful pass-through of the source {@code data_status} (Req 2.3):
     * {@code finalized} only when every contributing daily row is finalized,
     * otherwise {@code preliminary}; {@code null} when no performance row exists.
     */
    private String dataStatus;
}
