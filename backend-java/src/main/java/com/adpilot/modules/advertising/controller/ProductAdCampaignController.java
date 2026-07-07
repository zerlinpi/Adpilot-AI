package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.security.RequirePlatform;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.ProductAdCampaignRequest;
import com.adpilot.modules.advertising.service.ProductAdCampaignService;
import com.adpilot.modules.advertising.vo.ProductAdCampaignResultVo;
import com.adpilot.modules.rbac.PlatformFamily;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Orchestration controller for the per-product AI ad creation modal (Req 1).
 *
 * <p>Exposes the single write endpoint {@code POST /api/product-ads/campaign}
 * that drives the per-product keyword-ad creation flow. It is intentionally a
 * separate {@code @RestController} from {@link ProductAdController} (which owns
 * the read-only {@code GET} endpoints under {@code /api/product-ads}) and uses a
 * fully-qualified method-level path with no class-level {@code @RequestMapping},
 * so the two controllers coexist without a request-mapping collision.
 *
 * <p>Access is gated by {@link RequirePermission} ({@code advertising:manage})
 * and {@link RequirePlatform} ({@link PlatformFamily#AMAZON}) — per-product
 * keyword ads are currently limited to the Amazon family (Req 1.1). The
 * additional store-scope check (Req 1.8) is enforced inside
 * {@link ProductAdCampaignService} via {@code DataScopeService.assertCanWrite}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ProductAdCampaignController {

    private final ProductAdCampaignService productAdCampaignService;

    /**
     * POST /api/product-ads/campaign - Create a keyword ad campaign for a single
     * product in one transaction: validate inputs (Req 1.4), create the campaign
     * via {@code CampaignService}, link it to the product (Req 1.5), optionally
     * persist Hosting_Config and Safety_Boundary (Req 1.6), and enqueue the
     * external write via the Operation-Outbox (Req 1.7).
     *
     * <p>Errors propagate to {@code GlobalExceptionHandler}, which serializes a
     * JSON error envelope with a 4xx/5xx status.
     *
     * @param request the validated per-product ad modal payload
     * @return the created campaign id, linked product/ASIN and resolved
     *     Execution_Mode wrapped in an {@link ApiResponse} (Req 1.10)
     */
    @PostMapping("/api/product-ads/campaign")
    @RequirePermission("advertising:manage")
    @RequirePlatform(PlatformFamily.AMAZON)
    public ApiResponse<ProductAdCampaignResultVo> createProductAdCampaign(
            @Valid @RequestBody ProductAdCampaignRequest request) {
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        ProductAdCampaignResultVo result = productAdCampaignService.createProductAd(request, userId);
        return ApiResponse.ok(result);
    }
}
