package com.adpilot.modules.apisync.connector;

import java.util.UUID;

/**
 * Thrown by a {@link PlatformDataConnector} when a platform rejects a request
 * because the connection's credentials are expired or invalid and the
 * connection must be re-authorized by the user (Req 8.1.5).
 *
 * <p>The sync job runner catches this distinctly from generic pull failures:
 * it records the failure reason and marks the originating platform connection
 * as requiring re-authorization, rather than treating it as a transient or
 * record-level error.</p>
 *
 * <p>SECURITY: the {@link #getReason()} message must describe the failure
 * (e.g. "LWA refresh token rejected (invalid_grant)") without echoing any
 * secret/credential value.</p>
 */
public class ReauthRequiredException extends RuntimeException {

    /** The platform connection whose credentials require re-authorization. */
    private final UUID connectionId;

    public ReauthRequiredException(UUID connectionId, String reason) {
        super(reason);
        this.connectionId = connectionId;
    }

    public ReauthRequiredException(UUID connectionId, String reason, Throwable cause) {
        super(reason, cause);
        this.connectionId = connectionId;
    }

    public UUID getConnectionId() {
        return connectionId;
    }

    /** Human-readable, secret-free reason suitable for persisting as the failure reason. */
    public String getReason() {
        return getMessage();
    }
}
