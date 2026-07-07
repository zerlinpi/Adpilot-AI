package com.adpilot.modules.apisync.service;

import com.adpilot.modules.apisync.vo.ApiSyncJobVo;

import java.util.UUID;

/**
 * Owns the sync-job lifecycle and orchestration for a single platform
 * connection and entity type (Req 1.1.2, 1.3). The runner drives the same
 * pipeline across every {@link com.adpilot.modules.apisync.connector.PlatformDataConnector}
 * implementation:
 *
 * <pre>connector pull &rarr; mapper &rarr; validator &rarr; upsert &rarr; watermark</pre>
 *
 * <h2>Lifecycle (Req 1.3.1, 1.3.3)</h2>
 * <p>{@link #startSync} records a job in the {@code running} state with its
 * start time and begins retrieval; on success the job is marked
 * {@code completed} with its completion time, processed/failed counts, and the
 * watermark advanced to the most recent processed record (Req 1.1.8). On
 * failure (including rejected credentials, Req 1.1.5) the job is marked
 * {@code failed} with the failure reason recorded.</p>
 *
 * <h2>Record-level resilience (Req 1.3.2, 1.3.4, 1.4)</h2>
 * <p>An invalid record is excluded from upsert, recorded as exactly one
 * {@code sync_record_errors} entry, and processing continues with the running
 * processed/failed counts kept consistent.</p>
 *
 * <h2>Single-flight (Req 1.3.6)</h2>
 * <p>At most one job runs per {@code (store, entityType)} pair at any time;
 * a concurrent start request for the same pair is rejected.</p>
 */
public interface SyncJobRunner {

    /**
     * Create and begin an on-demand sync job for a connection's entity type
     * (Req 1.1.2). Records the job as {@code running} with its start time and
     * dispatches the pipeline for execution.
     *
     * @param connectionId the platform connection to sync
     * @param entityType   the entity type to pull (e.g. {@code "order"},
     *                     {@code "product"})
     * @param fullResync   whether to force a full retrieval ignoring the
     *                     watermark (Req 1.1.7)
     * @param triggeredBy  the user who triggered the sync, or {@code null} for
     *                     a system/scheduled trigger
     * @return the created job view
     * @throws com.adpilot.common.exception.BusinessException if the connection
     *         is unknown or a job for the same {@code (store, entityType)} is
     *         already running (Req 1.3.6)
     */
    ApiSyncJobVo startSync(UUID connectionId, String entityType, boolean fullResync, UUID triggeredBy);

    /**
     * Execute the pipeline for a previously created job (Req 1.3). Invoked
     * asynchronously by {@link #startSync} and synchronously by the scheduler.
     * Records terminal status, completion time, counts, record-level errors,
     * and advances the watermark on success.
     *
     * @param jobId the job to execute
     */
    void execute(UUID jobId);
}
