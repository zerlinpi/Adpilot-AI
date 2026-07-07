package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.advertising.service.ProductAdService;
import com.adpilot.modules.advertising.service.ProductAdSyncStatusService;
import com.adpilot.modules.advertising.vo.ProductAdSyncStatusVo;
import com.adpilot.modules.advertising.vo.ProductAdVo;
import com.adpilot.modules.advertising.vo.ProductCampaignVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ProductAdController {

    private final ProductAdService productAdService;
    private final ProductAdSyncStatusService productAdSyncStatusService;

    /**
     * GET /api/product-ads - List promoted products (advertised products) for the
     * active store, aggregated from the imported advertised-product report and
     * optionally narrowed to a single campaign.
     *
     * <p>Errors propagate to {@code GlobalExceptionHandler}, which serializes a JSON
     * error envelope with a 4xx/5xx status.
     */
    @GetMapping("/api/product-ads")
    @RequirePermission("advertising:view")
    public ApiResponse<List<ProductAdVo>> listProductAds(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String campaignId) {
        List<ProductAdVo> result = productAdService.listPromotedProducts(storeId, campaignId);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/other-products - List products customers purchased through the
     * store's ads (advertised-product groups with at least one order), optionally
     * narrowed to a single campaign. Returns an empty list when no purchase data
     * has been imported, which the workspace renders as an empty state.
     */
    @GetMapping("/api/other-products")
    @RequirePermission("advertising:view")
    public ApiResponse<List<ProductAdVo>> listOtherProducts(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String campaignId) {
        List<ProductAdVo> result = productAdService.listPurchasedProducts(storeId, campaignId);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/product-ads/sync-status - Read-only report-sync observability for a
     * store: the most recent run status, last successful completion time, and a
     * readable failure reason per report type (Req 2.5, 2.6).
     *
     * <p>A failed sync is never mapped to a success value; failed runs always carry
     * a non-empty, readable reason rather than a blank or fabricated success.
     */
    @GetMapping("/api/product-ads/sync-status")
    @RequirePermission("advertising:view")
    public ApiResponse<List<ProductAdSyncStatusVo>> getSyncStatus(
            @RequestParam(required = false) String storeId) {
        List<ProductAdSyncStatusVo> result = productAdSyncStatusService.getSyncStatus(storeId);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/product-ads/campaigns - List the advertising campaigns associated
     * with a single product within a store, viewed product-first (Req 2.1, 2.2,
     * 2.3). Association is resolved through {@code campaign_product_links} by
     * parent ASIN and/or local product id; at least one of {@code parentAsin} /
     * {@code productId} must be supplied (otherwise an empty list is returned).
     *
     * <p>Each row carries spend, clicks, orders, sales and ACoS aggregated from
     * {@code performance_daily} (Req 2.2) and faithfully passes through the
     * source {@code data_status} ({@code preliminary} / {@code finalized}, Req
     * 2.3). Results are confined to the requester's data scope (Req 2.7).
     */
    @GetMapping("/api/product-ads/campaigns")
    @RequirePermission("advertising:view")
    public ApiResponse<List<ProductCampaignVo>> listProductCampaigns(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String parentAsin,
            @RequestParam(required = false) String productId) {
        List<ProductCampaignVo> result = productAdService.listProductCampaigns(storeId, parentAsin, productId);
        return ApiResponse.ok(result);
    }
}
