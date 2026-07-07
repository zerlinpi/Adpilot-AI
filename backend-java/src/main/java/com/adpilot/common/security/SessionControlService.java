package com.adpilot.common.security;

/**
 * Server-side session control over otherwise-stateless JWTs (Req 11.2).
 *
 * <p>Two Redis-backed mechanisms back session invalidation:
 * <ul>
 *   <li><b>Single-token denylist</b> — on logout a specific token is denylisted
 *       by its JWT id until it would naturally expire, so it can no longer
 *       authorize requests (Req 11.2.1).</li>
 *   <li><b>Per-user session epoch</b> — an administrator can terminate all of a
 *       user's active sessions at once by advancing the user's session epoch;
 *       every token issued before that epoch is then rejected (Req 11.2.4).</li>
 * </ul>
 *
 * <p>{@link #isTokenRevoked(String)} is consulted by {@code JwtAuthFilter} on
 * every authenticated request.
 */
public interface SessionControlService {

    /**
     * Revoke a single token (logout). The token's JWT id is denylisted until the
     * token's own expiry, so no further requests can authenticate with it
     * (Req 11.2.1).
     *
     * @param token the raw JWT to revoke
     */
    void revokeToken(String token);

    /**
     * Terminate all active sessions for a user by advancing the user's session
     * epoch; any token issued before now is subsequently rejected (Req 11.2.4).
     *
     * @param userId the user whose sessions are invalidated
     */
    void invalidateUserSessions(String userId);

    /**
     * Whether the given token has been revoked, either individually (logout) or
     * because the owning user's sessions were invalidated by an administrator.
     *
     * @param token the raw JWT presented on a request
     * @return {@code true} if the token must not be honored
     */
    boolean isTokenRevoked(String token);
}
