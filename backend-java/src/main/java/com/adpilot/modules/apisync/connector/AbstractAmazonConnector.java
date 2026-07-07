package com.adpilot.modules.apisync.connector;

import com.adpilot.common.config.HttpClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared base for the Amazon {@link PlatformDataConnector} implementations
 * (SP-API and Ads). Both authenticate via Login-with-Amazon (LWA) using the
 * shared {@link AmazonLwaClient}, and both report that they validate their own
 * credentials during the pull so the runner does not run a separate generic
 * credential pre-check (which would mask the re-auth signal, Req 8.1.5).
 *
 * <p>SECURITY: extends {@link AbstractRestDataConnector}, which never logs
 * credential values. Subclasses transmit the LWA access token only to the
 * official Amazon endpoints.</p>
 */
abstract class AbstractAmazonConnector extends AbstractRestDataConnector {

    protected final AmazonLwaClient lwaClient;

    protected AbstractAmazonConnector(ObjectMapper objectMapper, AmazonLwaClient lwaClient,
                                      HttpClientFactory httpClientFactory) {
        super(objectMapper, httpClientFactory);
        this.lwaClient = lwaClient;
    }

    /**
     * Amazon connectors obtain and validate the LWA access token as part of the
     * pull, throwing {@link ReauthRequiredException} when the credential is
     * expired/invalid. They therefore opt out of the runner's generic
     * credential pre-check (Req 8.1.5).
     */
    @Override
    public boolean selfValidatesCredentials() {
        return true;
    }

    /** Normalized region key (lowercase, defaulting to {@code "na"}). */
    protected String region(String raw) {
        if (raw == null || raw.isBlank()) {
            return "na";
        }
        return raw.trim().toLowerCase();
    }

    protected static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
