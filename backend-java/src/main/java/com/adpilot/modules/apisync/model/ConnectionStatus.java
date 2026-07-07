package com.adpilot.modules.apisync.model;

/**
 * Canonical {@code platform_connections.status} values, consolidated here so the
 * status string literals are defined once instead of being duplicated across the
 * many services, workers and jobs that read or write a connection's status.
 *
 * <p>The string values are the exact, byte-for-byte identical values already
 * persisted in the {@code platform_connections.status} column and serialized to
 * clients; this class introduces no new value or behavior, it only removes the
 * duplicated magic literals.</p>
 */
public final class ConnectionStatus {

    /** A valid, active connection with working credentials. */
    public static final String CONNECTED = "connected";

    /** No credentials configured / the connection was explicitly disconnected. */
    public static final String DISCONNECTED = "disconnected";

    /** Credentials are complete but not yet validated against the platform. */
    public static final String CONFIGURED = "configured";

    /** Credentials are missing required fields or failed validation. */
    public static final String CONFIG_ERROR = "config_error";

    /** The stored refresh token was invalidated and re-authorization is required. */
    public static final String TOKEN_EXPIRED = "token_expired";

    private ConnectionStatus() {
    }
}
