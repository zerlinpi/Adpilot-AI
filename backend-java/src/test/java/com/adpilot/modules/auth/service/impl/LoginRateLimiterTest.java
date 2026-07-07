package com.adpilot.modules.auth.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LoginRateLimiterImpl} (audit L3).
 *
 * <p>Verifies the per-IP failed-attempt throttle: crossing the threshold blocks,
 * staying under it does not, the disabled flag bypasses entirely, a successful
 * login (reset) clears the counter, and a limiter backend error fails OPEN so
 * legitimate users are never locked out by infra failures.
 *
 * <p>The Redis-less construction path (mock {@link RedisTemplate} with no
 * connection factory) exercises the deterministic in-memory fixed-window fallback.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoginRateLimiterTest {

    private static final String IP = "203.0.113.7";
    private static final int MAX_ATTEMPTS = 3;
    private static final long WINDOW_SECONDS = 300;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    /** Build a limiter on the in-memory fallback (no Redis connection factory). */
    private LoginRateLimiterImpl inMemoryLimiter(boolean enabled) {
        // getConnectionFactory() returns null by default -> redisEnabled=false.
        return new LoginRateLimiterImpl(redisTemplate, enabled, MAX_ATTEMPTS, WINDOW_SECONDS);
    }

    // L3: exceeding the per-IP threshold blocks further attempts.
    @Test
    void exceedingThreshold_blocks() {
        LoginRateLimiterImpl limiter = inMemoryLimiter(true);

        assertThat(limiter.isBlocked(IP)).isFalse();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            limiter.recordFailure(IP);
        }

        assertThat(limiter.isBlocked(IP)).isTrue();
    }

    // L3: staying under the threshold does not block.
    @Test
    void underThreshold_doesNotBlock() {
        LoginRateLimiterImpl limiter = inMemoryLimiter(true);

        for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
            limiter.recordFailure(IP);
        }

        assertThat(limiter.isBlocked(IP)).isFalse();
    }

    // L3: the disabled flag bypasses the limiter entirely.
    @Test
    void disabledFlag_bypasses() {
        LoginRateLimiterImpl limiter = inMemoryLimiter(false);

        for (int i = 0; i < MAX_ATTEMPTS * 5; i++) {
            limiter.recordFailure(IP);
        }

        assertThat(limiter.isBlocked(IP)).isFalse();
    }

    // L3: a successful login resets the counter so prior failures do not accumulate.
    @Test
    void reset_clearsCounter() {
        LoginRateLimiterImpl limiter = inMemoryLimiter(true);

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            limiter.recordFailure(IP);
        }
        assertThat(limiter.isBlocked(IP)).isTrue();

        limiter.reset(IP);

        assertThat(limiter.isBlocked(IP)).isFalse();
    }

    // L3: the limiter is per-IP; one IP's failures do not block another.
    @Test
    void countingIsPerIp() {
        LoginRateLimiterImpl limiter = inMemoryLimiter(true);

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            limiter.recordFailure(IP);
        }

        assertThat(limiter.isBlocked(IP)).isTrue();
        assertThat(limiter.isBlocked("198.51.100.9")).isFalse();
    }

    // L3: a null/blank IP is never blocked (cannot attribute attempts).
    @Test
    void nullOrBlankIp_notBlocked() {
        LoginRateLimiterImpl limiter = inMemoryLimiter(true);

        assertThat(limiter.isBlocked(null)).isFalse();
        assertThat(limiter.isBlocked("")).isFalse();
    }

    // L3: when the backing store errors, the limiter fails OPEN (never locks out).
    @Test
    @SuppressWarnings("unchecked")
    void backendError_failsOpen() {
        LoginRateLimiterImpl limiter = inMemoryLimiter(true);
        // Force the Redis path, then make every script execution blow up.
        ReflectionTestUtils.setField(limiter, "redisEnabled", true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RuntimeException("redis down"));
        when(redisTemplate.execute(any(RedisScript.class), anyList()))
                .thenThrow(new RuntimeException("redis down"));

        // recordFailure swallows the error, isBlocked fails open to "not blocked".
        limiter.recordFailure(IP);
        assertThat(limiter.isBlocked(IP)).isFalse();
    }
}
