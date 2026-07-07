package com.adpilot.modules.apisync.model;

import java.util.Map;

/**
 * A marketplace or store discovered from a single seller-account credential
 * (Req 6.1). Returned by {@code PlatformDataConnector.discoverStores} and used
 * by store discovery to create-or-reuse internal stores keyed on the
 * {@link #marketplaceId()} reported by the platform.
 *
 * @param marketplaceId stable platform marketplace/store identifier used as the
 *                      idempotency anchor for discovery (Req 6.1.3, 6.1.4)
 * @param name          human-readable store/marketplace name
 * @param currency      the marketplace's currency code (e.g. "USD", "EUR"),
 *                      may be {@code null} when the platform does not report it
 * @param region        platform region/locale (e.g. "NA", "EU"), may be
 *                      {@code null}
 * @param extra         any additional platform-reported attributes
 */
public record DiscoveredStore(String marketplaceId,
                              String name,
                              String currency,
                              String region,
                              Map<String, Object> extra) {

    public DiscoveredStore {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    public DiscoveredStore(String marketplaceId, String name, String currency, String region) {
        this(marketplaceId, name, currency, region, Map.of());
    }
}
