package com.adpilot.modules.apisync.dto;

import lombok.Data;

import java.util.Map;

/**
 * One-step "connect a store" request: pick a platform, name the store, and
 * provide the platform credentials. The backend creates the store (with a
 * per-platform marketplace) and its platform connection in a single call, so the
 * user never has to manage "store" and "connection" as two separate concepts.
 */
@Data
public class ConnectStoreRequest {

    /** Platform key, e.g. shopify / woocommerce / tiktok_shop / amazon_ads. */
    private String platform;

    /**
     * The Nav_Block Platform_Family scope this connection is created under — the
     * connection entry's {@code ?platform=} value (e.g. {@code amazon} /
     * {@code independent_site} / {@code tiktok}). When present, the backend
     * enforces that {@link #platform} belongs to this family and rejects any
     * cross-family platform key (multistore-ai-ads-operations Req 6.2).
     */
    private String blockFamily;

    /** Display name for the new store (defaults to the platform label). */
    private String storeName;

    /** Platform credential fields keyed by field name (see PlatformConnector.fields). */
    private Map<String, String> config;
}
