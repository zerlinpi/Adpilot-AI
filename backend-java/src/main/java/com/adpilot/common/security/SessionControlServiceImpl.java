package com.adpilot.common.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Redis-backed {@link SessionControlService}.
 *
 * <p>Single-token revocation stores a marker keyed by the token's JWT id with a
 * TTL equal to the token's remaining lifetime, so the denylist self-cleans and
 * never grows unbounded (Req 11.2.1). Per-user invalidation stores a session
 * epoch (the instant after which all prior tokens are invalid) for the maximum
 * token lifetime; any token issued before that epoch is rejected (Req 11.2.4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionControlServiceImpl implements SessionControlService {

    private static final String DENYLIST_PREFIX = "auth:denylist:jti:";
    private static final String SESSION_EPOCH_PREFIX = "auth:session-epoch:";
    private static final String REVOKED = "1";

    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, Object> redisTemplate;

    /** Maximum token lifetime in ms; bounds how long a session epoch must be retained. */
    @Value("${adpilot.jwt.expiration:7200000}")
    private long jwtExpirationMs;

    /**
     * When true, Redis revocation-check failures deny requests. This is safer for
     * production because logout and forced-session invalidation are security
     * guarantees, while development can keep the previous availability-friendly
     * behavior.
     */
    @Value("${adpilot.security.session.revocation-fail-closed:false}")
    private boolean revocationFailClosed;

    @Override
    public void revokeToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            String jti = jwtUtil.getJtiFromToken(token);
            if (jti == null || jti.isBlank()) {
                return;
            }
            Instant expiry = jwtUtil.getExpirationFromToken(token);
            Duration ttl = expiry != null
                    ? Duration.between(Instant.now(), expiry)
                    : Duration.ofMillis(jwtExpirationMs);
            if (ttl.isNegative() || ttl.isZero()) {
                // Already expired; nothing to denylist.
                return;
            }
            redisTemplate.opsForValue().set(DENYLIST_PREFIX + jti, REVOKED, ttl);
            log.debug("Revoked token jti for logout");
        } catch (Exception e) {
            // A revocation failure must not crash logout; surface a warning only.
            log.warn("Failed to revoke token on logout: {}", e.getMessage());
        }
    }

    @Override
    public void invalidateUserSessions(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        try {
            long epochMs = Instant.now().toEpochMilli();
            redisTemplate.opsForValue().set(
                    SESSION_EPOCH_PREFIX + userId,
                    Long.toString(epochMs),
                    Duration.ofMillis(jwtExpirationMs));
            log.info("Invalidated all active sessions for user {}", userId);
        } catch (Exception e) {
            log.warn("Failed to invalidate sessions for user {}: {}", userId, e.getMessage());
        }
    }

    @Override
    public boolean isTokenRevoked(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            String jti = jwtUtil.getJtiFromToken(token);
            if (jti != null && !jti.isBlank()
                    && Boolean.TRUE.equals(redisTemplate.hasKey(DENYLIST_PREFIX + jti))) {
                return true;
            }

            String userId = jwtUtil.getUserIdFromToken(token);
            Instant issuedAt = jwtUtil.getIssuedAtFromToken(token);
            if (userId != null && issuedAt != null) {
                Object epochValue = redisTemplate.opsForValue().get(SESSION_EPOCH_PREFIX + userId);
                if (epochValue != null) {
                    long epochMs = Long.parseLong(String.valueOf(epochValue));
                    // Tokens issued before the epoch were terminated by an admin.
                    if (issuedAt.toEpochMilli() < epochMs) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Token revocation check failed; failClosed={}: {}",
                    revocationFailClosed, e.getMessage());
            return revocationFailClosed;
        }
        return false;
    }
}
