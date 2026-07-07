package com.adpilot.modules.apisync.model;

import java.util.UUID;

/**
 * Per-run context threaded through mapping, validation, upsert, and watermark
 * advancement for a single sync job. Identifies the job, the connection and
 * store being synced, the entity type, and whether a full resync was requested
 * (Req 1.1.7).
 *
 * @param jobId        the running sync job
 * @param connectionId originating platform connection id
 * @param storeId      store the records belong to
 * @param platform     platform key being synced
 * @param entityType   entity type being synced (e.g. "order", "product")
 * @param fullResync   whether a full retrieval was explicitly requested
 */
public record SyncContext(UUID jobId,
                          UUID connectionId,
                          UUID storeId,
                          String platform,
                          String entityType,
                          boolean fullResync) {
}
