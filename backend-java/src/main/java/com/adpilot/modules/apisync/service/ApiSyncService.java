package com.adpilot.modules.apisync.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.apisync.dto.ConnectStoreRequest;
import com.adpilot.modules.apisync.dto.PlatformConnectionDto;
import com.adpilot.modules.apisync.dto.StoreSyncRequest;
import com.adpilot.modules.apisync.vo.ApiSyncJobVo;
import com.adpilot.modules.apisync.vo.ApiSyncLogVo;
import com.adpilot.modules.apisync.vo.PlatformConnectionVo;

public interface ApiSyncService {

    /**
     * List platform connections with pagination.
     */
    PageResponse<PlatformConnectionVo> listPlatformConnections(int page, int pageSize);

    /**
     * One-step "connect a store": creates a new store (with a per-platform
     * marketplace) and its platform connection in a single call, so users never
     * manage "store" and "connection" separately. Returns the new connection
     * (carrying the new store id).
     */
    PlatformConnectionVo connectStore(ConnectStoreRequest request, String userId);

    /**
     * Create a new platform connection.
     */
    PlatformConnectionVo createPlatformConnection(PlatformConnectionDto dto, String userId);

    /**
     * Update an existing platform connection.
     */
    PlatformConnectionVo updatePlatformConnection(String id, PlatformConnectionDto dto, String userId);

    /**
     * Test a platform connection by connection identifier (UUID) or platform key.
     * Reads the connection, decrypts its stored config, and validates the
     * credentials against the external platform through {@code PlatformConnector}.
     * Returns a structured result (ok + human-readable message) within a 30s
     * bound. The stored credentials are never modified by a test. Throws a
     * {@code CONNECTION_NOT_FOUND} {@code BusinessException} when no connection
     * exists for the supplied identifier or platform key.
     */
    com.adpilot.modules.apisync.connector.PlatformConnector.TestResult testPlatformConnection(String id);

    /**
     * Connect a platform by its platform key (e.g. "amazon_ads"), creating or
     * updating the connection for the default store. Returns the connection.
     */
    PlatformConnectionVo connectPlatform(String platform, String userId);

    /**
     * Disconnect a platform by its platform key.
     */
    PlatformConnectionVo disconnectPlatform(String platform);

    /**
     * Test a platform connection by its platform key. Returns a status message.
     */
    String testPlatformByKey(String platform);

    /**
     * Save structured credential config for a platform (encrypted at rest),
     * upserting the connection. Runs a connectivity test to set the status.
     */
    PlatformConnectionVo saveConfig(String platform, PlatformConnectionDto dto, String userId);

    /**
     * Get a platform connection with its credential fields masked, for the config form.
     */
    PlatformConnectionVo getConfig(String platform);

    /**
     * List platform keys that have admin-configured credentials (available for one-click binding).
     */
    java.util.List<String> getConfiguredPlatforms();

    /**
     * One-click bind a store to a platform by reusing the admin-configured
     * credentials for that platform. Creates/updates the store's connection and
     * runs a connectivity test.
     */
    PlatformConnectionVo bindStore(String storeId, String platform, String userId);

    /**
     * List API sync jobs with pagination.
     */
    PageResponse<ApiSyncJobVo> listApiSyncJobs(int page, int pageSize,
                                               String platform,
                                               String status,
                                               String syncType);

    /**
     * Create and start a new API sync job.
     */
    ApiSyncJobVo createApiSyncJob(String connectionId, String syncType);

    /**
     * Retry a failed/cancelled/completed sync job by starting a new job with the
     * original connection, entity type, and full/incremental mode.
     */
    ApiSyncJobVo retryApiSyncJob(String jobId, String userId);

    /**
     * Mark a pending/running sync job as cancelled. The runner honors this
     * terminal state before writing completion/failure results.
     */
    ApiSyncJobVo cancelApiSyncJob(String jobId);

    /**
     * List API sync logs with pagination.
     */
    PageResponse<ApiSyncLogVo> listApiSyncLogs(int page, int pageSize,
                                               String level,
                                               String jobId,
                                               String search);

    /**
     * Start an on-demand sync for a Store (Req 1.1.2). Resolves the store's
     * active Platform Connection for the requested entity type and delegates to
     * the {@link SyncJobRunner}.
     */
    ApiSyncJobVo startStoreSync(String storeId, StoreSyncRequest request, String userId);

    /**
     * List the sync-job history for a Store, ordered most recent first (Req 1.3.5).
     */
    PageResponse<ApiSyncJobVo> listStoreSyncJobs(String storeId, int page, int pageSize, String status);

    /**
     * List the record-level logs for a single sync job, ordered most recent
     * first (Req 1.3.4/1.3.5).
     */
    PageResponse<ApiSyncLogVo> listJobLogs(String jobId, int page, int pageSize);
}
