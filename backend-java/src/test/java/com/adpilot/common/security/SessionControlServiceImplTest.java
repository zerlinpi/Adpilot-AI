package com.adpilot.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionControlServiceImpl} (task 22.6).
 *
 * <p>Covers server-side session invalidation over stateless JWTs:
 * <ul>
 *   <li>Logout denylists a specific token by its JWT id so it can no longer
 *       authorize requests (Req 11.2.1).</li>
 *   <li>Administrative invalidation advances the per-user session epoch so every
 *       token issued before that moment is rejected (Req 11.2.4).</li>
 * </ul>
 *
 * <p>{@link RedisTemplate} (and its {@link ValueOperations}) is backed by an
 * in-memory map so revocation can be observed end-to-end: a {@code set} during
 * logout/invalidation is later read back by {@code isTokenRevoked}. {@link JwtUtil}
 * is mocked to decode test tokens into their jti / userId / issued-at / expiry.
 */
@ExtendWith(MockitoExtension.class)
class SessionControlServiceImplTest {

    private static final long JWT_EXPIRATION_MS = 86_400_000L; // 24h

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private SessionControlServiceImpl service;

    /** In-memory stand-in for the Redis keyspace used by the service. */
    private final Map<String, Object> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new SessionControlServiceImpl(jwtUtil, redisTemplate);
        ReflectionTestUtils.setField(service, "jwtExpirationMs", JWT_EXPIRATION_MS);
        ReflectionTestUtils.setField(service, "revocationFailClosed", false);

        // Wire the RedisTemplate mock to the in-memory store. lenient() because not
        // every test exercises every operation.
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), any(), any(Duration.class));
        lenient().when(valueOperations.get(anyString()))
                .thenAnswer(inv -> store.get(inv.getArgument(0)));
        lenient().when(redisTemplate.hasKey(anyString()))
                .thenAnswer(inv -> store.containsKey(inv.getArgument(0)));
    }

    // Req 11.2.1: WHEN a user logs out, the session can no longer authorize requests.
    @Test
    void logoutDenylistsTokenSoItIsRevoked() {
        String token = "token-logout";
        when(jwtUtil.getJtiFromToken(token)).thenReturn("jti-logout");
        when(jwtUtil.getExpirationFromToken(token)).thenReturn(Instant.now().plusSeconds(3600));

        // Before logout the token is honored.
        assertThat(service.isTokenRevoked(token)).isFalse();

        service.revokeToken(token);

        // After logout the token's jti is denylisted, so it is revoked.
        assertThat(service.isTokenRevoked(token)).isTrue();
    }

    // Req 11.2.1: a token that was never logged out is not revoked.
    @Test
    void tokenThatWasNeverLoggedOutIsNotRevoked() {
        String token = "token-active";
        when(jwtUtil.getJtiFromToken(token)).thenReturn("jti-active");
        when(jwtUtil.getUserIdFromToken(token)).thenReturn("user-1");
        when(jwtUtil.getIssuedAtFromToken(token)).thenReturn(Instant.now());

        assertThat(service.isTokenRevoked(token)).isFalse();
    }

    // Req 11.2.1: an already-expired token is not added to the denylist (no point),
    // and revocation never throws.
    @Test
    void revokingAnAlreadyExpiredTokenIsANoOp() {
        String token = "token-expired";
        when(jwtUtil.getJtiFromToken(token)).thenReturn("jti-expired");
        when(jwtUtil.getExpirationFromToken(token)).thenReturn(Instant.now().minusSeconds(60));

        service.revokeToken(token);

        assertThat(store).isEmpty();
    }

    // Req 11.2.4: WHEN an administrator invalidates a user's sessions, tokens issued
    // before that moment are terminated.
    @Test
    void adminInvalidationRevokesTokensIssuedBeforeTheEpoch() {
        String userId = "user-42";
        String oldToken = "token-old";
        // Token was issued well before the admin action.
        when(jwtUtil.getJtiFromToken(oldToken)).thenReturn("jti-old");
        when(jwtUtil.getUserIdFromToken(oldToken)).thenReturn(userId);
        when(jwtUtil.getIssuedAtFromToken(oldToken)).thenReturn(Instant.now().minusSeconds(120));

        // Before invalidation the token authorizes requests.
        assertThat(service.isTokenRevoked(oldToken)).isFalse();

        service.invalidateUserSessions(userId);

        // After the admin advances the session epoch, the prior token is revoked.
        assertThat(service.isTokenRevoked(oldToken)).isTrue();
    }

    // Req 11.2.4: a fresh token issued AFTER the admin invalidation remains valid.
    @Test
    void tokenIssuedAfterInvalidationRemainsValid() {
        String userId = "user-43";
        service.invalidateUserSessions(userId);

        String newToken = "token-new";
        when(jwtUtil.getJtiFromToken(newToken)).thenReturn("jti-new");
        when(jwtUtil.getUserIdFromToken(newToken)).thenReturn(userId);
        // Issued after the epoch was set (now + buffer).
        when(jwtUtil.getIssuedAtFromToken(newToken)).thenReturn(Instant.now().plusSeconds(5));

        assertThat(service.isTokenRevoked(newToken)).isFalse();
    }

    // Req 11.2.4: invalidation is per-user; another user's tokens are unaffected.
    @Test
    void invalidationIsScopedToTheTargetUser() {
        String invalidatedUser = "user-A";
        service.invalidateUserSessions(invalidatedUser);

        String otherUsersToken = "token-B";
        when(jwtUtil.getJtiFromToken(otherUsersToken)).thenReturn("jti-B");
        when(jwtUtil.getUserIdFromToken(otherUsersToken)).thenReturn("user-B");
        when(jwtUtil.getIssuedAtFromToken(otherUsersToken)).thenReturn(Instant.now().minusSeconds(120));

        // Issued before user-A's epoch, but belongs to user-B who was not invalidated.
        assertThat(service.isTokenRevoked(otherUsersToken)).isFalse();
    }

    @Test
    void redisFailureCanFailClosedForProductionProfiles() {
        ReflectionTestUtils.setField(service, "revocationFailClosed", true);

        String token = "token-redis-down";
        when(jwtUtil.getJtiFromToken(token)).thenReturn("jti-redis-down");
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(service.isTokenRevoked(token)).isTrue();
    }
}
