package com.adpilot.modules.apisync.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.security.RequirePlatform;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.apisync.dto.IndependentSiteConnectRequest;
import com.adpilot.modules.apisync.dto.IndependentSiteGoogleAdsBindRequest;
import com.adpilot.modules.apisync.service.IndependentSiteConnectionService;
import com.adpilot.modules.apisync.vo.PlatformConnectionVo;
import com.adpilot.modules.rbac.PlatformFamily;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * Independent_Site_Connection_Wizard backend (platform-workspace-rbac Req 5).
 *
 * <p>A single entry point per independent-site block: connect a Shopify /
 * WooCommerce / TikTok store in one flow, and bind Google Ads to an existing
 * independent-site store. Replaces the prior two-step "API 连接 then store
 * connection" flow (Req 5.6).</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/independent-site/connect")
@RequiredArgsConstructor
public class IndependentSiteConnectionController {

    private final IndependentSiteConnectionService connectionService;

    /**
     * POST /api/independent-site/connect/store — create a Shopify / WooCommerce /
     * TikTok store and its platform connection in one flow, assigning the store to
     * the independent-site Store_Group system (Req 5.2, 5.4).
     */
    @PostMapping("/store")
    @RequirePermission("store:manage")
    public ApiResponse<PlatformConnectionVo> connectStore(
            @RequestBody IndependentSiteConnectRequest request,
            @RequestParam(name = "platform", required = false) String platformScope) {
        // The connection entry's ?platform= scope declares the Nav_Block platform
        // family; carry it into the request so the service can reject cross-family
        // connections (Req 6.2).
        if (platformScope != null && !platformScope.isBlank()
                && (request.getBlockFamily() == null || request.getBlockFamily().isBlank())) {
            request.setBlockFamily(platformScope);
        }
        return ApiResponse.ok(connectionService.connectStore(request, SecurityUtils.getCurrentUserIdOrNull()));
    }

    /**
     * POST /api/independent-site/connect/google-ads — bind a Google Ads account to
     * a selected independent-site store rather than create a standalone ad-only
     * connection (Req 5.5).
     */
    @PostMapping("/google-ads")
    @RequirePermission("store:manage")
    @RequirePlatform(PlatformFamily.INDEPENDENT_SITE)
    public ApiResponse<PlatformConnectionVo> bindGoogleAds(
            @RequestBody IndependentSiteGoogleAdsBindRequest request) {
        return ApiResponse.ok(connectionService.bindGoogleAds(request, SecurityUtils.getCurrentUserIdOrNull()));
    }
}
