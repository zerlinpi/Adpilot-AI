package com.adpilot.modules.advertising.independentsite;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.security.RequirePlatform;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.FulfillmentRequest;
import com.adpilot.modules.advertising.dto.InventoryUpdateRequest;
import com.adpilot.modules.advertising.service.IndependentSiteWriteService;
import com.adpilot.modules.advertising.vo.IndependentSiteConnectionStateVo;
import com.adpilot.modules.advertising.vo.OperationActionVo;
import com.adpilot.modules.rbac.PlatformFamily;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Independent-site inventory / fulfillment write-back endpoints (Req 4).
 *
 * <p>A thin orchestration layer over {@link IndependentSiteWriteService}: every
 * write is gated and, when allowed, routed through the Operation-Outbox so the
 * external Shopify / WooCommerce platform is never called on the request thread
 * (Req 4.2). All write methods enforce both the required functional permission
 * via {@link RequirePermission} and independent-site Platform_Family access via
 * {@link RequirePlatform}; access decisions are made on the backend independent
 * of whether the frontend hides the entry point (Req 4.6).
 *
 * <p>Full paths are declared on each method (rather than a class-level base
 * mapping) so they remain distinct from the existing
 * {@code IndependentSiteConnectionController} at {@code /api/independent-site/connect}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class IndependentSiteWriteController {

    private final IndependentSiteWriteService writeService;

    /**
     * POST /api/independent-site/products/{productId}/inventory — enqueue an
     * inventory update for an independent-site product (Req 4.1, 4.2).
     */
    @PostMapping("/api/independent-site/products/{productId}/inventory")
    @RequirePermission("product:manage")
    @RequirePlatform(PlatformFamily.INDEPENDENT_SITE)
    public ApiResponse<OperationActionVo> updateInventory(
            @PathVariable String productId,
            @Valid @RequestBody InventoryUpdateRequest req) {
        return ApiResponse.ok(
                writeService.updateInventory(productId, req, SecurityUtils.getCurrentUserIdOrNull()));
    }

    /**
     * POST /api/independent-site/orders/{orderId}/fulfillment — enqueue a
     * fulfillment / shipment mark for an independent-site order (Req 4.1, 4.2).
     */
    @PostMapping("/api/independent-site/orders/{orderId}/fulfillment")
    @RequirePermission("order:manage")
    @RequirePlatform(PlatformFamily.INDEPENDENT_SITE)
    public ApiResponse<OperationActionVo> markFulfillment(
            @PathVariable String orderId,
            @Valid @RequestBody FulfillmentRequest req) {
        return ApiResponse.ok(
                writeService.markFulfillment(orderId, req, SecurityUtils.getCurrentUserIdOrNull()));
    }

    /**
     * GET /api/independent-site/connection-state — expose each independent-site
     * store's connection state and write-capability flags (Req 4.4, 4.7).
     */
    @GetMapping("/api/independent-site/connection-state")
    @RequirePermission("store:view")
    @RequirePlatform(PlatformFamily.INDEPENDENT_SITE)
    public ApiResponse<List<IndependentSiteConnectionStateVo>> connectionState(
            @RequestParam(required = false) String storeId) {
        return ApiResponse.ok(writeService.getConnectionStates(storeId));
    }
}
