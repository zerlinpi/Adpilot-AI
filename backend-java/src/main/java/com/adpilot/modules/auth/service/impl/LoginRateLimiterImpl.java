package com.adpilot.modules.auth.service.impl;

import com.adpilot.modules.auth.service.LoginRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-backed {@link LoginRateLimiter} with an in-memory fallback, following the
 * established {@code AmazonAdsRateLimiter} design.
 *
 * <p><b>Algorithm:</b> a fixed window per client IP. The first failure in a window
 * sets the key with a TTL of {@code window-seconds}; subsequent failures increment
 * it; the key self-expires so the window rolls forward. An IP is blocked once its
 * failure count reaches {@code max-attempts}.
 *
 * <p><b>Redis:</b> counting uses a Lua script (atomic INCR + EXPIRE), which also
 * sidesteps value-serializer issues since the counter lives entirely server-side.
 * If Redis is not configured at startup, an in-memory {@code ConcurrentHashMap}
 * of fixed-window counters is used instead.
 *
 * <p><b>Fail-open:</b> when Redis is enabled but a call throws at runtime,
 * {@link #isBlocked} returns {@code false} and {@link #recordFailure} is a no-op —
 * infra failures must never lock out legitimate users. The whole limiter is also
 * disabled via {@code adpilot.security.login-rate-limit.enabled=false}.
 */
@Component
public class LoginRateLimiterImpl implements LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiterImpl.class);

    private static final String KEY_PREFIX = "adpilot:ratelimit:login:ip:";

    /**
     * Atomic increment + first-write expiry. Returns the post-increment count.
     * KEYS[1] = counter key; ARGV[1] = window seconds.
     */
    private static final String INCR_SCRIPT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
            end
            return count
            """;

    /** Read the current count without mutating it. Returns 0 when the key is absent. */
    private static final String READ_SCRIPT = """
            local v = redis.call('GET', KEYS[1])
            if v == false then return 0 end
            return tonumber(v)
            """;

    private final RedisTemplate<String, Object> redisTemplate;
    private final boolean redisEnabled;

    /** Whether the limiter is active at all (toggleable off). */
    private final boolean enabled;

    /** Failed attempts within the window that block further attempts. */
    private final int maxAttempts;

    /** Rolling window length in seconds. */
    private final long windowSeconds;

    /** In-memory fallback: per-IP fixed-window counter. */
    private final ConcurrentHashMap<String, Window> localWindows = new ConcurrentHashMap<>();

    public LoginRateLimiterImpl(
            RedisTemplate<String, Object> redisTemplate,
            @Value("${adpilot.security.login-rate-limit.enabled:true}") boolean enabled,
            @Value("${adpilot.security.login-rate-limit.max-attempts:20}") int maxAttempts,
            @Value("${adpilot.security.login-rate-limit.window-seconds:300}") long windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.maxAttempts = maxAttempts;
        this.windowSeconds = windowSeconds;
        this.redisEnabled = probeRedis();
    }

    @Override
    public boolean isBlocked(String clientIp) {
        if (!enabled || clientIp == null || clientIp.isBlank()) {
            return false;
        }
        try {
            long count = redisEnabled ? redisCount(clientIp) : localCount(clientIp);
            return count >= maxAttempts;
        } catch (Exception e) {
            // Fail open: an infra failure must never lock out legitimate users.
            log.warn("Login rate-limit check failed; failing open for IP: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void recordFailure(String clientIp) {
        if (!enabled || clientIp == null || clientIp.isBlank()) {
            return;
        }
        try {
            if (redisEnabled) {
                redisIncrement(clientIp);
            } else {
                localIncrement(clientIp);
            }
        } catch (Exception e) {
            // Never let limiter bookkeeping break the login flow.
            log.warn("Login rate-limit record failed; ignoring: {}", e.getMessage());
        }
    }

    @Override
    public void reset(String clientIp) {
        if (!enabled || clientIp == null || clientIp.isBlank()) {
            return;
        }
        try {
            if (redisEnabled) {
                redisTemplate.delete(key(clientIp));
            } else {
                localWindows.remove(clientIp);
            }
        } catch (Exception e) {
            log.warn("Login rate-limit reset failed; ignoring: {}", e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Redis operations
    // ────────────────────────────────────────────────────────────────────────

    private long redisCount(String clientIp) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(READ_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script, List.of(key(clientIp)));
        return result != null ? result : 0L;
    }

    private void redisIncrement(String clientIp) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(INCR_SCRIPT, Long.class);
        redisTemplate.execute(script, List.of(key(clientIp)), windowSeconds);
    }

    private String key(String clientIp) {
        return KEY_PREFIX + clientIp;
    }

    private boolean probeRedis() {
        var connectionFactory = redisTemplate.getConnectionFactory();
        if (connectionFactory == null) {
            log.info("Redis not configured for login rate limiter; using in-memory fallback");
            return false;
        }
        try {
            try (var connection = connectionFactory.getConnection()) {
                connection.ping();
            }
            return true;
        } catch (Exception e) {
            log.info("Redis not available for login rate limiter; using in-memory fallback: {}", e.getMessage());
            return false;
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // In-memory fixed-window fallback
    // ────────────────────────────────────────────────────────────────────────

    private long localCount(String clientIp) {
        Window w = localWindows.get(clientIp);
        return w == null ? 0L : w.count(Instant.now());
    }

    private void localIncrement(String clientIp) {
        Instant now = Instant.now();
        localWindows.compute(clientIp, (k, existing) -> {
            if (existing == null || existing.isExpired(now)) {
                return new Window(now.plusSeconds(windowSeconds));
            }
            existing.increment();
            return existing;
        });
    }

    /** A thread-safe fixed-window counter that resets once its window elapses. */
    private static final class Window {
        private final Instant windowEnd;
        private long count;

        Window(Instant windowEnd) {
            this.windowEnd = windowEnd;
            this.count = 1;
        }

        synchronized boolean isExpired(Instant now) {
            return !now.isBefore(windowEnd);
        }

        synchronized void increment() {
            count++;
        }

        synchronized long count(Instant now) {
            return isExpired(now) ? 0L : count;
        }
    }
}
