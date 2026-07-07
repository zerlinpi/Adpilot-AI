package com.adpilot.modules.apisync.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The result of mapping an {@link ExternalRecord} to internal entity fields.
 * Carries the store association (the store owning the originating connection,
 * Req 1.1.4) and retains the external identifier and change timestamp used for
 * idempotent upsert (Req 1.2) and watermark advancement (Req 1.1.8).
 *
 * @param externalId external identifier this record maps from
 * @param entityType internal entity type (e.g. "order", "product")
 * @param storeId    store that owns the originating platform connection
 * @param status     normalized internal status
 * @param fields     mapped internal field values keyed by internal field name
 * @param changedAt  platform-reported change timestamp
 */
public record MappedRecord(String externalId,
                           String entityType,
                           UUID storeId,
                           String status,
                           Map<String, Object> fields,
                           Instant changedAt) {
}
