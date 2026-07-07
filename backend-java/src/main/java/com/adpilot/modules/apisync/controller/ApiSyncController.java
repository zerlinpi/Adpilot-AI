package com.adpilot.modules.apisync.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.apisync.connector.PlatformConnector;
import com.adpilot.modules.apisync.dto.PlatformConnectionDto;
import com.adpilot.modules.apisync.dto.StoreSyncRequest;
import com.adpilot.modules.apisync.service.ApiSyncService;
import com.adpilot.modules.apisync.vo.ApiSyncJobVo;
import com.adpilot.modules.apisync.vo.ApiSyncLogVo;
import com.adpilot.modules.apisync.vo.PlatformConnectionVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiSyncController {

    private final ApiSyncService apiSyncService;
    private final PlatformConnector platformConnector;

    /**
     * GET /api/platform-connections - List platform connections with pagination.
     */
    @GetMapping("/platform-connections")
    @RequirePermission("import:view")
    public ApiResponse<PageResponse<PlatformConnectionVo>> listPlatformConnections(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<PlatformConnectionVo> result = apiSyncService.listPlatformConnections(page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/platform-connections - Create a new platform connection.
     */
    @PostMapping("/platform-connections")
    @RequirePermission("import:manage")
    public ApiResponse<PlatformConnectionVo> createPlatformConnection(@Valid @RequestBody PlatformConnectionDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        PlatformConnectionVo result = apiSyncService.createPlatformConnection(dto, userId);
        return ApiResponse.ok(result);
    }

    /**
     * PUT /api/platform-connections/{id} - Update an existing platform connection.
     */
    @PutMapping("/platform-connections/{id}")
    @RequirePermission("import:manage")
    public ApiResponse<PlatformConnectionVo> updatePlatformConnection(
            @PathVariable String id,
            @Valid @RequestBody PlatformConnectionDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        PlatformConnectionVo result = apiSyncService.updatePlatformConnection(id, dto, userId);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/platform-connections/{platform}/connect - Connect a platform by key.
     */
    @PostMapping("/platform-connections/{platform}/connect")
    @RequirePermission("import:manage")
    public ApiResponse<PlatformConnectionVo> connectPlatform(@PathVariable String platform) {
        return ApiResponse.ok(apiSyncService.connectPlatform(platform, SecurityUtils.getCurrentUserIdOrNull()));
    }

    /**
     * GET /api/platform-connections/{platform}/fields - Credential field schema for the config form.
     */
    @GetMapping("/platform-connections/{platform}/fields")
    @RequirePermission("import:manage")
    public ApiResponse<List<PlatformConnector.FieldSpec>> getFields(@PathVariable String platform) {
        return ApiResponse.ok(platformConnector.fields(platform));
    }

    /**
     * GET /api/platform-connections/{platform}/config - Current connection with masked credentials.
     */
    @GetMapping("/platform-connections/{platform}/config")
    @RequirePermission("import:manage")
    public ApiResponse<PlatformConnectionVo> getConfig(@PathVariable String platform) {
        return ApiResponse.ok(apiSyncService.getConfig(platform));
    }

    /**
     * PUT /api/platform-connections/{platform}/config - Save (encrypted) credentials for a platform.
     */
    @PutMapping("/platform-connections/{platform}/config")
    @RequirePermission("import:manage")
    public ApiResponse<PlatformConnectionVo> saveConfig(
            @PathVariable String platform,
            @Valid @RequestBody PlatformConnectionDto dto) {
        return ApiResponse.ok(apiSyncService.saveConfig(platform, dto, SecurityUtils.getCurrentUserIdOrNull()));
    }

    /**
     * POST /api/platform-connections/{platform}/disconnect - Disconnect a platform by key.
     */
    @PostMapping("/platform-connections/{platform}/disconnect")
    @RequirePermission("import:manage")
    public ApiResponse<PlatformConnectionVo> disconnectPlatform(@PathVariable String platform) {
        return ApiResponse.ok(apiSyncService.disconnectPlatform(platform));
    }

    /**
     * POST /api/platform-connections/{id}/test - Test a platform connection.
     * Accepts either a connection UUID or a platform key (e.g. "amazon_ads").
     * Returns the structured connectivity result (ok + message) so the frontend
     * can render the outcome.
     */
    @PostMapping("/platform-connections/{id}/test")
    @RequirePermission("import:manage")
    public ApiResponse<PlatformConnector.TestResult> testPlatformConnection(@PathVariable String id) {
        return ApiResponse.ok(apiSyncService.testPlatformConnection(id));
    }

    /**
     * GET /api/platform-connections/configured - platform keys with admin-configured credentials.
     */
    @GetMapping("/platform-connections/configured")
    @RequirePermission("import:manage")
    public ApiResponse<List<String>> getConfiguredPlatforms() {
        return ApiResponse.ok(apiSyncService.getConfiguredPlatforms());
    }

    /**
     * POST /api/stores/connect - One-step "connect a store": create a store and
     * its platform connection together. Body: { platform, storeName?, config }.
     *
     * <p>This is the generic, multi-family connect entry: it serves amazon,
     * independent_site, and tiktok connections, selected at call time by the
     * {@code ?platform=} scope. Because the platform family is not fixed per
     * route, it intentionally carries no static {@code @RequirePlatform(...)}
     * (a single static family would break the other families). TikTok has no
     * dedicated controller yet (multistore-ai-ads-operations Req 5.6 — TikTok
     * ad-management actions are not provided); TikTok connections flow through
     * here with {@code ?platform=tiktok}. Cross-family enforcement (rejecting a
     * platform key that does not belong to the declared block family, Req 6.2,
     * 5.5) is applied at the service layer via the declared {@code blockFamily}
     * scope — see {@code ApiSyncServiceImpl.connectStore}. Per-route static
     * {@code @RequirePlatform} gating is reserved for family-fixed routes such as
     * the Google Ads endpoints ({@code @RequirePlatform(INDEPENDENT_SITE)}).</p>
     */
    @PostMapping("/stores/connect")
    @RequirePermission("store:manage")
    public ApiResponse<PlatformConnectionVo> connectStore(
            @RequestBody com.adpilot.modules.apisync.dto.ConnectStoreRequest request,
            @RequestParam(name = "platform", required = false) String platformScope) {
        // The connection entry's ?platform= scope (amazon / independent_site /
        // tiktok) declares the Nav_Block platform family; carry it into the
        // request so the service can reject cross-family connections (Req 6.2).
        if (platformScope != null && !platformScope.isBlank()
                && (request.getBlockFamily() == null || request.getBlockFamily().isBlank())) {
            request.setBlockFamily(platformScope);
        }
        return ApiResponse.ok(apiSyncService.connectStore(request, SecurityUtils.getCurrentUserIdOrNull()));
    }

    /**
     * POST /api/stores/{storeId}/bind - One-click bind a store to a platform,
     * reusing admin-configured credentials. Body: { "platform": "amazon_ads" }.
     */
    @PostMapping("/stores/{storeId}/bind")
    @RequirePermission("import:manage")
    public ApiResponse<PlatformConnectionVo> bindStore(
            @PathVariable String storeId,
            @Valid @RequestBody com.adpilot.modules.apisync.dto.BindStoreRequest body) {
        return ApiResponse.ok(apiSyncService.bindStore(storeId, body.getPlatform(), SecurityUtils.getCurrentUserIdOrNull()));
    }

    /**
     * GET /api/api-sync/jobs - List API sync jobs with pagination.
     */
    @GetMapping("/api-sync/jobs")
    @RequirePermission("import:view")
    public ApiResponse<PageResponse<ApiSyncJobVo>> listApiSyncJobs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String syncType) {
        PageResponse<ApiSyncJobVo> result = apiSyncService.listApiSyncJobs(page, pageSize, platform, status, syncType);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/api-sync/jobs - Create and start a new API sync job.
     */
    @PostMapping("/api-sync/jobs")
    @RequirePermission("import:manage")
    public ApiResponse<ApiSyncJobVo> createApiSyncJob(
            @RequestParam String connectionId,
            @RequestParam String syncType) {
        ApiSyncJobVo result = apiSyncService.createApiSyncJob(connectionId, syncType);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/api-sync/jobs/{id}/retry - Start a new sync using the original
     * job's connection, entity type, and sync mode.
     */
    @PostMapping("/api-sync/jobs/{id}/retry")
    @RequirePermission("import:manage")
    public ApiResponse<ApiSyncJobVo> retryApiSyncJob(@PathVariable String id) {
        ApiSyncJobVo result = apiSyncService.retryApiSyncJob(
                id, com.adpilot.common.utils.SecurityUtils.getCurrentUserIdOrNull());
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/api-sync/jobs/{id}/cancel - Mark a pending/running job cancelled.
     */
    @PostMapping("/api-sync/jobs/{id}/cancel")
    @RequirePermission("import:manage")
    public ApiResponse<ApiSyncJobVo> cancelApiSyncJob(@PathVariable String id) {
        ApiSyncJobVo result = apiSyncService.cancelApiSyncJob(id);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/api-sync/logs - List API sync logs with pagination.
     */
    @GetMapping("/api-sync/logs")
    @RequirePermission("import:view")
    public ApiResponse<PageResponse<ApiSyncLogVo>> listApiSyncLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String jobId,
            @RequestParam(required = false) String search) {
        PageResponse<ApiSyncLogVo> result = apiSyncService.listApiSyncLogs(page, pageSize, level, jobId, search);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/stores/{storeId}/sync - Trigger an on-demand sync for a store.
     * Body: { "entityType": "order", "fullResync": false }. The store's active
     * platform connection for the entity type is resolved server-side.
     */
    @PostMapping("/stores/{storeId}/sync")
    @RequirePermission("import:manage")
    public ApiResponse<ApiSyncJobVo> startStoreSync(
            @PathVariable String storeId,
            @RequestBody StoreSyncRequest request) {
        ApiSyncJobVo result = apiSyncService.startStoreSync(
                storeId, request, SecurityUtils.getCurrentUserIdOrNull());
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/stores/{storeId}/sync-jobs - Sync history for a store, most recent first.
     */
    @GetMapping("/stores/{storeId}/sync-jobs")
    @RequirePermission("import:view")
    public ApiResponse<PageResponse<ApiSyncJobVo>> listStoreSyncJobs(
            @PathVariable String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status) {
        PageResponse<ApiSyncJobVo> result = apiSyncService.listStoreSyncJobs(storeId, page, pageSize, status);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/api-sync/jobs/{id}/logs - Record-level logs for a single sync job, most recent first.
     */
    @GetMapping("/api-sync/jobs/{id}/logs")
    @RequirePermission("import:view")
    public ApiResponse<PageResponse<ApiSyncLogVo>> listJobLogs(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<ApiSyncLogVo> result = apiSyncService.listJobLogs(id, page, pageSize);
        return ApiResponse.ok(result);
    }
}
