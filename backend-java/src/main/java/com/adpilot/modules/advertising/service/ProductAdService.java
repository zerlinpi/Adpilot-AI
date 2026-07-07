package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.vo.ProductAdVo;
import com.adpilot.modules.advertising.vo.ProductCampaignVo;

import java.util.List;

public interface ProductAdService {

    /**
     * List promoted products (advertised products) for the active store,
     * aggregated by campaign / ad group / SKU / ASIN, optionally narrowed to a
     * single campaign.
     */
    List<ProductAdVo> listPromotedProducts(String storeId, String campaignId);

    /**
     * List products that customers actually purchased through the store's ads
     * (advertised-product groups with at least one order), optionally narrowed
     * to a single campaign.
     */
    List<ProductAdVo> listPurchasedProducts(String storeId, String campaignId);

    /**
     * List the advertising campaigns associated with a single product within a
     * store, viewed product-first (Req 2.1, 2.2, 2.3).
     *
     * <p>Association is resolved through {@code campaign_product_links} by parent
     * ASIN and/or local product id (the campaign's resolved
     * {@code parentAsin}); at least one of {@code parentAsin} / {@code productId}
     * must be supplied. Each returned {@link ProductCampaignVo} carries the five
     * headline metrics required by Req 2.2 — spend, clicks, orders, sales and
     * ACoS — aggregated from {@code performance_daily}, and faithfully passes
     * through the source {@code data_status} ({@code preliminary} /
     * {@code finalized}, Req 2.3).</p>
     *
     * <p>Results are restricted to the requester's data scope (Req 2.7); an empty
     * list is returned when the store is blank or no association exists.</p>
     */
    List<ProductCampaignVo> listProductCampaigns(String storeId, String parentAsin, String productId);
}
