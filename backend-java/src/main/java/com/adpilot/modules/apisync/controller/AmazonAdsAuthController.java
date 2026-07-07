package com.adpilot.modules.apisync.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.oauth.AmazonAdsOAuthService;
import com.adpilot.modules.apisync.oauth.dto.AmazonAdsAuthUrlVo;
import com.adpilot.modules.apisync.oauth.dto.AmazonAdsBindRequest;
import com.adpilot.modules.apisync.oauth.dto.AmazonAdsCallbackVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Amazon Ads OAuth (Login with Amazon) store-connection flow.
 *
 * <p>Lives under {@code /api/platform-connections/amazon-ads/*} so it inherits
 * the same authentication as the other platform-connection endpoints. The
 * callback is invoked by the frontend landing page (which holds the user's
 * session) after Amazon redirects back to the registered redirect URI with
 * {@code ?code&state}.</p>
 *
 * <p>When credentials are not configured, the service throws a 400-level
 * {@code BusinessException} with a clear message — never a 500/NPE.</p>
 *
 * <p>NOTE (SP-API): this OAuth flow authorizes the ADVERTISING (ad) account
 * only. Product upload via the Amazon Selling Partner API (SP-API) requires a
 * separate Selling Partner authorization and is intentionally not implemented
 * in this advertising flow. It must not block ad-account connection.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/platform-connections/amazon-ads")
@RequiredArgsConstructor
public class AmazonAdsAuthController {

    private final AmazonAdsOAuthService oauthService;

    /**
     * Step 2a — build the Amazon authorize URL the user is sent to. Persists a
     * short-lived CSRF state mapped to {storeId, region}.
     */
    @GetMapping("/authorize-url")
    @RequirePermission("import:manage")
    public ApiResponse<AmazonAdsAuthUrlVo> authorizeUrl(
            @RequestParam(defaultValue = "NA") String region,
            @RequestParam String storeId) {
        return ApiResponse.ok(oauthService.buildAuthorizeUrl(region, storeId));
    }

    /**
     * Step 2b — handle the OAuth redirect. Validates state, exchanges the code
     * for tokens, stores the refresh token encrypted, and returns the list of
     * advertising profiles/accounts to choose from. Exposed as both GET and POST.
     */
    @GetMapping("/callback")
    @RequirePermission("import:manage")
    public ApiResponse<AmazonAdsCallbackVo> callbackGet(
            @RequestParam String code,
            @RequestParam String state) {
        return ApiResponse.ok(oauthService.handleCallback(code, state));
    }

    @PostMapping("/callback")
    @RequirePermission("import:manage")
    public ApiResponse<AmazonAdsCallbackVo> callbackPost(@RequestBody Map<String, String> body) {
        String code = body != null ? body.get("code") : null;
        String state = body != null ? body.get("state") : null;
        return ApiResponse.ok(oauthService.handleCallback(code, state));
    }

    /**
     * Step 3 — bind a selected advertising profile to a store. The refresh token
     * is resolved server-side; no secret is accepted from the client.
     */
    @PostMapping("/bind")
    @RequirePermission("import:manage")
    public ApiResponse<Map<String, Object>> bind(@RequestBody AmazonAdsBindRequest request) {
        PlatformConnectionEntity entity = oauthService.bindProfile(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entity.getId() != null ? entity.getId().toString() : null);
        result.put("storeId", entity.getStoreId() != null ? entity.getStoreId().toString() : null);
        result.put("platform", entity.getPlatform());
        result.put("status", entity.getStatus());
        result.put("region", entity.getRegion());
        result.put("profileId", entity.getProfileId());
        result.put("marketplaceId", entity.getMarketplaceId());
        result.put("sellerAccountId", entity.getSellerAccountId());
        result.put("connectionName", entity.getConnectionName());
        return ApiResponse.ok(result);
    }
}
