package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.advertising.hosting.AiDecisionEntity;
import com.adpilot.modules.advertising.hosting.HostingDashboardService;
import com.adpilot.modules.advertising.hosting.HostingOrgIsolationGuard;
import com.adpilot.modules.advertising.vo.HostingAnalyticsVo;
import com.adpilot.modules.advertising.vo.HostingDashboardSummaryVo;
import com.adpilot.modules.advertising.vo.HostingDecisionDetailVo;
import com.adpilot.modules.advertising.vo.HostingDecisionVo;
import com.adpilot.modules.advertising.vo.HostingHealthVo;
import com.adpilot.modules.store.entity.StoreEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Hosting dashboard, decision, analytics, and health APIs under
 * {@code /api/advertising/hosting} (Requirements 11, 27, 29, 30.5).
 *
 * <p>All read endpoints require {@code advertising:view}. Every inbound id is resolved to a store
 * in the caller's organization by {@link HostingOrgIsolationGuard} BEFORE any data is read, so a
 * cross-organization id yields 403/404 with no data leak (Req 39). Responses use the
 * {@link ApiResponse} envelope and figures derived from effect attribution are always presented as
 * estimates (Req 11.3, 27.3, 29.3).</p>
 *
 * <p>This controller is dedicated to dashboard/analytics reads; config, optimization-trigger, and
 * approve/reject/rollback endpoints live in their own controllers.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/advertising/hosting")
@RequiredArgsConstructor
public class HostingDashboardController {

    private final HostingDashboardService hostingDashboardService;
    private final HostingOrgIsolationGuard orgIsolationGuard;

    /**
     * GET /api/advertising/hosting/dashboard/summary?storeId= — summary cards (Req 11.1, 27).
     */
    @GetMapping("/dashboard/summary")
    @RequirePermission("advertising:view")
    public ApiResponse<HostingDashboardSummaryVo> getSummary(@RequestParam("storeId") String storeId) {
        UUID id = parseUuid(storeId, "storeId");
        StoreEntity store = orgIsolationGuard.resolveStoreInCallerOrg(id);
        return ApiResponse.ok(hostingDashboardService.getSummary(store.getId()));
    }

    /**
     * GET /api/advertising/hosting/decisions?storeId=&limit= — recent AI decisions (Req 11.4).
     */
    @GetMapping("/decisions")
    @RequirePermission("advertising:view")
    public ApiResponse<List<HostingDecisionVo>> listDecisions(
            @RequestParam("storeId") String storeId,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        UUID id = parseUuid(storeId, "storeId");
        StoreEntity store = orgIsolationGuard.resolveStoreInCallerOrg(id);
        return ApiResponse.ok(hostingDashboardService.listDecisions(store.getId(), limit));
    }

    /**
     * GET /api/advertising/hosting/decisions/{id} — decision explanation card (Req 11.5, 13).
     */
    @GetMapping("/decisions/{id}")
    @RequirePermission("advertising:view")
    public ApiResponse<HostingDecisionDetailVo> getDecision(@PathVariable("id") String id) {
        UUID decisionId = parseUuid(id, "id");
        AiDecisionEntity decision = orgIsolationGuard.resolveDecisionInCallerOrg(decisionId);
        return ApiResponse.ok(hostingDashboardService.getDecisionDetail(decision));
    }

    /**
     * GET /api/advertising/hosting/analytics?storeId=&period= — historical analytics (Req 29).
     */
    @GetMapping("/analytics")
    @RequirePermission("advertising:view")
    public ApiResponse<HostingAnalyticsVo> getAnalytics(
            @RequestParam("storeId") String storeId,
            @RequestParam(value = "period", required = false, defaultValue = "7d") String period) {
        UUID id = parseUuid(storeId, "storeId");
        StoreEntity store = orgIsolationGuard.resolveStoreInCallerOrg(id);
        return ApiResponse.ok(hostingDashboardService.getAnalytics(store.getId(), period));
    }

    /**
     * GET /api/advertising/hosting/health — dependency health (Req 30.5).
     */
    @GetMapping("/health")
    @RequirePermission("advertising:view")
    public ApiResponse<HostingHealthVo> getHealth() {
        return ApiResponse.ok(hostingDashboardService.getHealth());
    }

    private static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER", field + " must not be blank");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER",
                    "Invalid " + field + " '" + value + "': not a valid identifier");
        }
    }
}
