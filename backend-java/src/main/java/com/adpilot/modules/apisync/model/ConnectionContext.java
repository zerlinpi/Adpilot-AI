package com.adpilot.modules.apisync.model;

import java.util.Map;
import java.util.UUID;

/**
 * Everything a {@code PlatformDataConnector} needs to talk to an external
 * platform for a single connection: the platform key, the store the connection
 * belongs to, and the decrypted credential map.
 *
 * <p>SECURITY: the {@code credentials} map holds secrets decrypted on demand
 * via {@code CryptoUtil}. It must never be logged. {@link #toString()} is
 * overridden to redact the credential values.</p>
 *
 * @param connectionId originating platform connection id
 * @param storeId      store that owns the connection (used for store
 *                     association, Req 1.1.4)
 * @param platform     platform key (e.g. "woocommerce", "shopify",
 *                     "amazon_sp_api")
 * @param credentials  decrypted credential fields keyed by field name
 */
public record ConnectionContext(UUID connectionId,
                                UUID storeId,
                                String platform,
                                Map<String, String> credentials) {

    public ConnectionContext {
        credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
    }

    /** Convenience accessor for a single credential field. */
    public String credential(String key) {
        return credentials.get(key);
    }

    /** Redacts credential values to keep secrets out of logs. */
    @Override
    public String toString() {
        return "ConnectionContext{connectionId=" + connectionId
                + ", storeId=" + storeId
                + ", platform=" + platform
                + ", credentials=[REDACTED " + credentials.size() + " field(s)]}";
    }
}
