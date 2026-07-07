package com.adpilot.modules.apisync.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.security.RequirePlatform;
import com.adpilot.modules.apisync.service.TikTokAdsReadService;
import com.adpilot.modules.apisync.vo.TikTokAdsCampaignVo;
import com.adpilot.modules.apisync.vo.TikTokAdsPerformanceReportVo;
import com.adpilot.modules.apisync.vo.TikTokAdsReadResponse;
import com.adpilot.modules.rbac.PlatformFamily;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read surface for the TikTok Ads module, symmetric to
 * {@link GoogleAdsReadController}.
 *
 * <p>Exposes the {@link TikTokAdsReadService} over HTTP so the 列表 / 报告 views
 * can render campaigns and date-ranged performance, surfacing the three read
 * states (OK / CONNECT_PROMPT / ERROR) through {@link TikTokAdsReadResponse} so
 * the frontend can show the connect prompt or an error + retry control without
 * conflating either with a transport failure. The endpoints are read-only and
 * require the same independent-site advertising view access as the Google Ads
 * read endpoints.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/tiktok-ads")
@RequiredArgsConstructor
public class TikTokAdsReadController {

    private final TikTokAdsReadService tikTokAdsReadService;

    /**
     * GET /api/tiktok-ads/campaigns?storeId= — campaigns for the Store's active
     * TikTok Ads connection, or a connect-prompt / error state.
     */
    @GetMapping("/campaigns")
    @RequirePermission("advertising:view")
    @RequirePlatform(PlatformFamily.TIKTOK)
    public ApiResponse<TikTokAdsReadResponse<List<TikTokAdsCampaignVo>>> getCampaigns(
            @RequestParam String storeId) {
        return ApiResponse.ok(TikTokAdsReadResponse.from(
                tikTokAdsReadService.getCampaigns(UUID.fromString(storeId))));
    }

    /**
     * GET /api/tiktok-ads/report?storeId=&from=&to= — date-ranged performance
     * report, or a connect-prompt / error state. The date range is optional; the
     * service defaults it when omitted.
     */
    @GetMapping("/report")
    @RequirePermission("advertising:view")
    @RequirePlatform(PlatformFamily.TIKTOK)
    public ApiResponse<TikTokAdsReadResponse<TikTokAdsPerformanceReportVo>> getReport(
            @RequestParam String storeId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(TikTokAdsReadResponse.from(
                tikTokAdsReadService.getPerformanceReport(UUID.fromString(storeId), from, to)));
    }
}
