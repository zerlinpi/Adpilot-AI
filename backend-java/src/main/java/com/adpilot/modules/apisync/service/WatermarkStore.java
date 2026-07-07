package com.adpilot.modules.apisync.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-(store, entityType) sync watermark accessor backed by the
 * {@code sync_watermarks} table.
 *
 * <p>A watermark records the point of the most recent record successfully
 * processed for a Store and entity type. It is used to resolve the incremental
 * retrieval window: when present, only records created or changed since the
 * watermark are pulled; when absent, a full pull is performed.
 *
 * <p>Requirements: 1.1.6 (incremental window), 1.1.7 (full pull when absent or
 * full resync requested), 1.1.8 (advance to most recent processed record on
 * success).
 */
public interface WatermarkStore {

    /**
     * Returns the current watermark for the given store and entity type.
     *
     * @return the watermark instant, or {@link Optional#empty()} when no
     *         watermark exists (the caller should perform a full pull).
     */
    Optional<Instant> get(UUID storeId, String entityType);

    /**
     * Advances the watermark for the given store and entity type to
     * {@code newWatermark}.
     *
     * <p>The watermark never regresses: the stored value is only updated when
     * {@code newWatermark} is strictly greater than the existing watermark. If
     * no watermark exists yet, it is created.
     */
    void advance(UUID storeId, String entityType, Instant newWatermark);
}
