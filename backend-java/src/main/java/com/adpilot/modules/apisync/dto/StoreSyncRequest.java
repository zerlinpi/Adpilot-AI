package com.adpilot.modules.apisync.dto;

import lombok.Data;

/**
 * Request body for an on-demand store sync (Req 1.1.2). The store's active
 * Platform Connection for the requested entity type is resolved server-side; a
 * specific {@code connectionId} or {@code platform} may be supplied to
 * disambiguate when a store has multiple connections.
 */
@Data
public class StoreSyncRequest {

    /** Entity type to pull (e.g. {@code "order"}, {@code "product"}, {@code "inventory"}, {@code "ad_report"}). */
    private String entityType;

    /** Force a full retrieval ignoring the watermark (Req 1.1.7). Defaults to false. */
    private Boolean fullResync;

    /** Optional: explicit platform connection to sync. Overrides automatic resolution. */
    private String connectionId;

    /** Optional: platform key (e.g. {@code "shopify"}) used to pick a connection when a store has several. */
    private String platform;
}
