package com.adpilot.modules.apisync.connector;

import java.util.UUID;

/**
 * Thrown when a connection's LWA refresh token has been invalidated by Amazon
 * ({@code invalid_grant}). Callers must stop further API calls for this
 * connection until re-authorization occurs.
 *
 * <p>Distinct from {@link ReauthRequiredException} in purpose: this exception is
 * thrown by the token service to signal that the connection is permanently
 * degraded (token-expired), not as a transient pull-time signal.</p>
 */
public class TokenExpiredException extends RuntimeException {

    private final UUID connectionId;

    public TokenExpiredException(UUID connectionId, String reason) {
        super(reason);
        this.connectionId = connectionId;
    }

    public TokenExpiredException(UUID connectionId, String reason, Throwable cause) {
        super(reason, cause);
        this.connectionId = connectionId;
    }

    public UUID getConnectionId() {
        return connectionId;
    }
}
