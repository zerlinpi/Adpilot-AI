package com.adpilot.modules.advertising.tiktok;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.security.RequirePlatform;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.FulfillmentRequest;
import com.adpilot.modules.advertising.dto.InventoryUpdateRequest;
import com.adpilot.modules.advertising.service.TikTokWriteService;
import com.adpilot.modules.advertising.vo.IndependentSiteConnectionStateVo;
import com.adpilot.modules.advertising.vo.OperationActionVo;
import com.adpilot.modules.rbac.PlatformFamily;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * TikTok Shop inventory / fulfillment write-back endpoints (Req 4, TikTok family).
 *
 * <p>The exact TikTok analogue of {@code IndependentSiteWriteController}: a thin
 * orchestration layer over {@link TikTokWriteService}. Every write is gated and,
 * when allowed, routed through the Operation-Outbox so the external TikTok Shop
 * platform is never called on the request thread (Req 4.2). All write methods
 * enforce both the required functional permission via {@link RequirePermission}
 * and TikTok Platform_Family access via {@link RequirePlatform}; access decisions
 * are made on the backend independent of whether the frontend hides the entry
 * point (Req 4.6).
 *
 * <p>This controller deliberately lives in {@code com.adpilot.modules.advertising.tiktok}
 * (parallel to {@code advertising.independentsite}) rather than in
 * {@code advertising.controller}, so its non-advertising permission codes
 * ({@code product:manage} / {@code order:manage} / {@code store:view}) are not
 * swept up by the advertising permission-matrix enforcement test.
 *
 * <p>Full paths are declared on each method (rather than a class-level base
 * mapping) so they remain distinct from any other {@code /api/tiktok/*} mappings.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class TikTokWriteController {

    private final TikTokWriteService writeService;

    /**
     * POST /api/tiktok/products/{productId}/inventory — enqueue an inventory
     * update for a TikTok Shop product (Req 4.1, 4.2).
     */
    @PostMapping("/api/tiktok/products/{productId}/inventory")
    @RequirePermission("product:manage")
    @RequirePlatform(PlatformFamily.TIKTOK)
    public ApiResponse<OperationActionVo> updateInventory(
            @PathVariable String productId,
            @Valid @RequestBody InventoryUpdateRequest req) {
        return ApiResponse.ok(
                writeService.updateInventory(productId, req, SecurityUtils.getCurrentUserIdOrNull()));
    }

    /**
     * POST /api/tiktok/orders/{orderId}/fulfillment — enqueue a fulfillment /
     * shipment mark for a TikTok Shop order (Req 4.1, 4.2).
     */
    @PostMapping("/api/tiktok/orders/{orderId}/fulfillment")
    @RequirePermission("order:manage")
    @RequirePlatform(PlatformFamily.TIKTOK)
    public ApiResponse<OperationActionVo> markFulfillment(
            @PathVariable String orderId,
            @Valid @RequestBody FulfillmentRequest req) {
        return ApiResponse.ok(
                writeService.markFulfillment(orderId, req, SecurityUtils.getCurrentUserIdOrNull()));
    }

    /**
     * GET /api/tiktok/connection-state — expose each TikTok Shop store's
     * connection state and write-capability flags (Req 4.4, 4.7).
     */
    @GetMapping("/api/tiktok/connection-state")
    @RequirePermission("store:view")
    @RequirePlatform(PlatformFamily.TIKTOK)
    public ApiResponse<List<IndependentSiteConnectionStateVo>> connectionState(
            @RequestParam(required = false) String storeId) {
        return ApiResponse.ok(writeService.getConnectionStates(storeId));
    }
}
