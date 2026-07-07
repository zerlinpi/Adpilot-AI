package com.adpilot.modules.apisync.service;

import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.MappedRecord;
import com.adpilot.modules.apisync.model.SyncContext;

/**
 * Maps a normalized {@link ExternalRecord} retrieved from a platform connector
 * to internal entity fields, producing a {@link MappedRecord} (Req 1.1.3) that
 * is associated with the Store owning the originating Platform Connection
 * (Req 1.1.4).
 *
 * <p>The mapper is pure and side-effect free: it reads the raw normalized field
 * values from the external record and emits internal field values with the
 * correct Java types for the target table (e.g. {@code price}/{@code
 * total_amount} as {@link java.math.BigDecimal}, timestamps as
 * {@link java.time.Instant}). It does not validate required fields or write to
 * the database; data-quality validation (Req 1.4) and idempotent upsert
 * (Req 1.2) are separate stages of the sync pipeline.</p>
 *
 * <h2>Supported entity types</h2>
 * <ul>
 *   <li>{@code "order"} &rarr; {@code channel_orders}</li>
 *   <li>{@code "product"} &rarr; {@code channel_products}</li>
 *   <li>{@code "inventory"} &rarr; {@code channel_inventory_sync} (Amazon SP-API)</li>
 *   <li>{@code "ad_report"} &rarr; {@code performance_daily} (Amazon Ads, Req 8.1.3)</li>
 * </ul>
 */
public interface RecordMapper {

    /**
     * Map a single external record to internal entity fields.
     *
     * @param ctx    the per-run sync context; supplies the {@code storeId} used
     *               for store association (Req 1.1.4)
     * @param record the normalized external record to map
     * @return a {@link MappedRecord} carrying internal field values, the store
     *         association, and the external identifier/change timestamp used by
     *         later stages
     * @throws IllegalArgumentException if the record's entity type is not one of
     *                                  the supported types
     */
    MappedRecord map(SyncContext ctx, ExternalRecord record);
}
