package com.adpilot.modules.apisync.connector;

import java.util.UUID;

/**
 * Caches LWA (Login with Amazon) access tokens per {@code PlatformConnection}
 * and proactively refreshes them before expiry.
 *
 * <p>Requirements covered:
 * <ul>
 *   <li><b>1.7</b> — Use the Store's PlatformConnection refresh_token to obtain a
 *       fresh LWA access token before each API call when the current token is
 *       expired or absent.</li>
 *   <li><b>1.15</b> — Per-connection token caching to avoid redundant token requests.</li>
 *   <li><b>15.3</b> — Proactive refresh within 5 minutes of expiry.</li>
 *   <li><b>15.4</b> — On {@code invalid_grant}: mark connection {@code token_expired},
 *       stop calls, flag degraded write capability, trigger Feishu alert.</li>
 * </ul>
 *
 * <p>SECURITY: The service decrypts refresh tokens via {@code CryptoUtil} only at
 * point of use, and never logs or exposes credential values.</p>
 */
public interface AmazonAdsTokenService {

    /**
     * Obtain a valid LWA access token for the given connection. Returns a cached
     * token if still valid (with a 5-minute proactive refresh margin), otherwise
     * refreshes from the LWA token endpoint.
     *
     * @param connectionId the platform connection id to obtain a token for
     * @return a valid LWA access token (never null or blank)
     * @throws TokenExpiredException if the connection's refresh token has been
     *         invalidated by Amazon ({@code invalid_grant}); callers should stop
     *         further calls for this connection
     * @throws IllegalStateException if the connection does not exist or has no
     *         encrypted refresh token
     */
    String getAccessToken(UUID connectionId);

    /**
     * Evict the cached token for a connection (e.g. after a 401 from the Ads API
     * indicating the access token is stale despite appearing valid by TTL).
     *
     * @param connectionId the platform connection id whose cached token to evict
     */
    void evict(UUID connectionId);

    /**
     * Check whether the given connection is in a {@code token_expired} state,
     * meaning its refresh token has been invalidated and no further API calls
     * should be attempted.
     *
     * @param connectionId the platform connection id to check
     * @return {@code true} if the connection is marked as token-expired
     */
    boolean isTokenExpired(UUID connectionId);
}
