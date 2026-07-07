package com.adpilot.modules.apisync.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.security.RequirePlatform;
import com.adpilot.modules.advertising.hosting.GoogleAdsHostingService;
import com.adpilot.modules.apisync.service.GoogleAdsReadService;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.apisync.vo.GoogleAdsCampaignVo;
import com.adpilot.modules.apisync.vo.GoogleAdsPerformanceReportVo;
import com.adpilot.modules.apisync.vo.GoogleAdsReadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read + hosting-trigger surface for the Google Ads module
 * (platform-workspace-rbac Req 6, 8).
 *
 * <p>Exposes the {@link GoogleAdsReadService} over HTTP so the 列表 / 报告 views
 * can render campaigns and date-ranged performance, and surfaces the
 * three read states (OK / CONNECT_PROMPT / ERROR) through
 * {@link GoogleAdsReadResponse} so the frontend can show the connect prompt
 * (Req 6.6) or an error + retry control (Req 6.5) without conflating either with
 * a transport failure.</p>
 *
 * <p>The AI托管 view triggers a hosting pass via
 * {@link GoogleAdsHostingService#optimizeStore(UUID)}; that path is the
 * write-capable hosting control, so it requires the independent-site advertising
 * operate permission, whereas the read endpoints only require view access.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/google-ads")
@RequiredArgsConstructor
public class GoogleAdsReadController {

    private final GoogleAdsReadService googleAdsReadService;
    private final GoogleAdsHostingService googleAdsHostingService;

    /**
     * GET /api/google-ads/campaigns?storeId= — campaigns for the Store's active
     * Google Ads connection, or a connect-prompt / error state (Req 6.3, 6.5, 6.6).
     */
    @GetMapping("/campaigns")
    @RequirePermission("advertising:view")
    @RequirePlatform(PlatformFamily.INDEPENDENT_SITE)
    public ApiResponse<GoogleAdsReadResponse<List<GoogleAdsCampaignVo>>> getCampaigns(
            @RequestParam String storeId) {
        return ApiResponse.ok(GoogleAdsReadResponse.from(
                googleAdsReadService.getCampaigns(UUID.fromString(storeId))));
    }

    /**
     * GET /api/google-ads/reports?storeId=&from=&to= — date-ranged performance
     * report, or a connect-prompt / error state (Req 6.4, 6.5, 6.6). The date
     * range is optional; the service defaults it when omitted.
     */
    @GetMapping("/reports")
    @RequirePermission("advertising:view")
    @RequirePlatform(PlatformFamily.INDEPENDENT_SITE)
    public ApiResponse<GoogleAdsReadResponse<GoogleAdsPerformanceReportVo>> getReports(
            @RequestParam String storeId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(GoogleAdsReadResponse.from(
                googleAdsReadService.getPerformanceReport(UUID.fromString(storeId), from, to)));
    }

    /**
     * POST /api/google-ads/hosting/optimize?storeId= — run one Google Ads AI
     * hosting pass for the Store, returning a summary of processed / skipped /
     * failed campaigns and the Operations created. The pass routes accepted
     * decisions through the platform-generic pipeline (Req 8).
     */
    @PostMapping("/hosting/optimize")
    @RequirePermission("advertising:independent_site:operate")
    @RequirePlatform(PlatformFamily.INDEPENDENT_SITE)
    public ApiResponse<GoogleAdsHostingService.HostingRunSummary> optimize(
            @RequestParam String storeId) {
        return ApiResponse.ok(googleAdsHostingService.optimizeStore(UUID.fromString(storeId)));
    }
}
