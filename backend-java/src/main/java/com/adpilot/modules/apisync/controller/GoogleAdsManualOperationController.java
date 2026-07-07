package com.adpilot.modules.apisync.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.security.RequirePlatform;
import com.adpilot.modules.advertising.operation.OperationResult;
import com.adpilot.modules.apisync.dto.GoogleAdsAdjustRequest;
import com.adpilot.modules.apisync.dto.GoogleAdsCampaignCreateRequest;
import com.adpilot.modules.apisync.service.GoogleAdsManualOperationService;
import com.adpilot.modules.apisync.vo.GoogleAdsOperationVo;
import com.adpilot.modules.rbac.PlatformFamily;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual Google Ads write endpoints (platform-workspace-rbac Req 7).
 *
 * <p>An authorized independent-site operator creates Google Ads campaigns and
 * adjusts bids, budgets, and statuses from AdPilot. Each action is turned into a
 * {@code platform_mutation} Operation routed through the generic
 * {@code OperationService} &rarr; {@code operation_outbox} &rarr;
 * {@code GoogleAdsWriteConnector} pipeline; the platform is never called
 * synchronously (Req 7.1, 7.2). Acceptance advances the Operation through its
 * Sync_States and records the platform response; rejection records the failure
 * reason and leaves the internal record unchanged — both handled by the generic
 * pipeline and Outbox worker (Req 7.3, 7.4).</p>
 *
 * <p>Every endpoint is gated with {@code @RequirePermission} for the
 * independent-site advertising Functional_Permission
 * ({@value com.adpilot.modules.apisync.service.impl.GoogleAdsManualOperationServiceImpl#INDEPENDENT_ADS_PERMISSION}).
 * A caller lacking that permission is rejected by {@code PermissionAspect} with
 * HTTP 403 before the method body runs, so no Operation is created (Req 7.5).
 * Unsettled changes surface through the existing Pending_Overlay (Req 7.6).</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/google-ads/operations")
@RequiredArgsConstructor
public class GoogleAdsManualOperationController {

    private final GoogleAdsManualOperationService manualOperationService;

    /**
     * POST /api/google-ads/operations/campaigns — create a Google Ads campaign as a
     * {@code platform_mutation} Operation with {@code OperationSource.CREATION}
     * routed to the GoogleAdsWriteConnector (Req 7.1).
     */
    @PostMapping("/campaigns")
    @RequirePermission("advertising:independent_site:operate")
    @RequirePlatform(PlatformFamily.INDEPENDENT_SITE)
    public ApiResponse<GoogleAdsOperationVo> createCampaign(
            @Valid @RequestBody GoogleAdsCampaignCreateRequest request) {
        OperationResult result = manualOperationService.createCampaign(request);
        return ApiResponse.ok(GoogleAdsOperationVo.from(result));
    }

    /**
     * POST /api/google-ads/operations/adjust — submit a bid, budget, or status
     * change as a {@code platform_mutation} Operation with
     * {@code OperationSource.MANUAL} routed to the GoogleAdsWriteConnector (Req 7.2).
     */
    @PostMapping("/adjust")
    @RequirePermission("advertising:independent_site:operate")
    @RequirePlatform(PlatformFamily.INDEPENDENT_SITE)
    public ApiResponse<GoogleAdsOperationVo> adjust(
            @RequestBody GoogleAdsAdjustRequest request) {
        OperationResult result = manualOperationService.adjust(request);
        return ApiResponse.ok(GoogleAdsOperationVo.from(result));
    }
}
