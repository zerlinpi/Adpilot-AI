package com.adpilot.modules.apisync.model;

import java.time.Instant;
import java.util.Map;

/**
 * A normalized record retrieved from an external platform before it is mapped
 * to an internal entity. Connectors emit a stream of these for orders,
 * products, inventory, and ad reports.
 *
 * @param externalId stable external identifier used as the idempotency anchor
 * @param entityType internal entity type this record maps to
 *                   (e.g. "order", "product")
 * @param changedAt  platform-reported create/change timestamp, used for
 *                   incremental selection and watermark advancement
 * @param status     platform-reported status (e.g. "active", "cancelled",
 *                   "deleted"); drives status-marking of removed records
 * @param fields     raw normalized field values keyed by field name
 */
public record ExternalRecord(String externalId,
                             String entityType,
                             Instant changedAt,
                             String status,
                             Map<String, Object> fields) {
}
