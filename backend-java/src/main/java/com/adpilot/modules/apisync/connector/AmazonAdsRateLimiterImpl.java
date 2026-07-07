package com.adpilot.modules.apisync.connector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token-bucket rate limiter for Amazon Ads API, per advertising profile.
 *
 * <p><b>Design:</b>
 * <ul>
 *   <li>Each profile has an independent token bucket (default 10 tokens/sec).</li>
 *   <li>Redis is attempted first for distributed state; on failure, falls back
 *       to an in-memory {@code ConcurrentHashMap} bucket (Requirement 30.3).</li>
 *   <li>Adaptive backoff: when &gt;10 rate-limited responses are recorded in a
 *       5-minute sliding window, effective rate drops to 50% for 15 minutes
 *       (Requirement 15.6).</li>
 *   <li>Never blocks the caller thread (Requirements 15.1, 15.2).</li>
 * </ul>
 *
 * <p><b>Token bucket algorithm (in-memory):</b> tracks the last refill timestamp
 * and available tokens. On {@code tryAcquire}, tokens are refilled based on
 * elapsed time, then one token is consumed if available.
 */
@Component
public class AmazonAdsRateLimiterImpl implements AmazonAdsRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AmazonAdsRateLimiterImpl.class);

    /** Default maximum requests per second per profile. */
    private final double defaultRate;

    /** Adaptive backoff: number of 429 responses within the window that triggers reduction. */
    private static final int BACKOFF_TRIGGER_COUNT = 10;

    /** Adaptive backoff: sliding window for counting rate-limit responses. */
    private static final Duration BACKOFF_WINDOW = Duration.ofMinutes(5);

    /** Adaptive backoff: how long the reduced rate stays in effect. */
    private static final Duration BACKOFF_DURATION = Duration.ofMinutes(15);

    /** Adaptive backoff: rate reduction factor (50%). */
    private static final double BACKOFF_RATE_FACTOR = 0.5;

    private final RedisTemplate<String, Object> redisTemplate;
    private final boolean redisEnabled;

    /** In-memory fallback: per-profile token bucket state. */
    private final ConcurrentHashMap<String, TokenBucket> localBuckets = new ConcurrentHashMap<>();

    /** Per-profile rate-limit event history for adaptive backoff. */
    private final ConcurrentHashMap<String, RateLimitHistory> rateLimitHistory = new ConcurrentHashMap<>();

    /**
     * Lua script for atomic Redis token-bucket acquire.
     * KEYS[1] = bucket key
     * ARGV[1] = max tokens (capacity)
     * ARGV[2] = refill rate (tokens per second)
     * ARGV[3] = current time in milliseconds
     * Returns: 1 if acquired, 0 if denied
     */
    private static final String REDIS_TRY_ACQUIRE_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local now_ms = tonumber(ARGV[3])
            
            local data = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
            local tokens = tonumber(data[1])
            local last_refill_ms = tonumber(data[2])
            
            if tokens == nil then
                tokens = capacity
                last_refill_ms = now_ms
            end
            
            local elapsed_ms = now_ms - last_refill_ms
            if elapsed_ms > 0 then
                local refill = elapsed_ms * rate / 1000.0
                tokens = math.min(capacity, tokens + refill)
                last_refill_ms = now_ms
            end
            
            if tokens >= 1 then
                tokens = tokens - 1
                redis.call('HSET', key, 'tokens', tostring(tokens), 'last_refill_ms', tostring(last_refill_ms))
                redis.call('EXPIRE', key, 300)
                return 1
            else
                redis.call('HSET', key, 'tokens', tostring(tokens), 'last_refill_ms', tostring(last_refill_ms))
                redis.call('EXPIRE', key, 300)
                return 0
            end
            """;

    /**
     * Lua script to query next available instant from Redis bucket.
     * Returns milliseconds until a token is available (0 if available now).
     */
    private static final String REDIS_NEXT_AVAILABLE_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local now_ms = tonumber(ARGV[3])
            
            local data = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
            local tokens = tonumber(data[1])
            local last_refill_ms = tonumber(data[2])
            
            if tokens == nil then
                return 0
            end
            
            local elapsed_ms = now_ms - last_refill_ms
            if elapsed_ms > 0 then
                local refill = elapsed_ms * rate / 1000.0
                tokens = math.min(capacity, tokens + refill)
            end
            
            if tokens >= 1 then
                return 0
            else
                local deficit = 1 - tokens
                local wait_ms = math.ceil(deficit / rate * 1000)
                return wait_ms
            end
            """;

    public AmazonAdsRateLimiterImpl(
            RedisTemplate<String, Object> redisTemplate,
            @Value("${adpilot.amazon-ads.rate-limit.requests-per-second:10}") double defaultRate) {
        this.redisTemplate = redisTemplate;
        this.defaultRate = defaultRate;
        // Probe Redis availability at construction time; we also handle per-call failures
        this.redisEnabled = probeRedis();
    }

    @Override
    public Instant nextAvailableInstant(String profileId) {
        double effectiveRate = effectiveRate(profileId);

        if (redisEnabled) {
            try {
                long waitMs = redisNextAvailableMs(profileId, effectiveRate);
                if (waitMs <= 0) {
                    return Instant.now();
                }
                return Instant.now().plusMillis(waitMs);
            } catch (Exception e) {
                log.warn("Redis unavailable for rate limiter (nextAvailableInstant), falling back to in-memory: {}",
                        e.getMessage());
            }
        }

        // In-memory fallback
        TokenBucket bucket = getOrCreateBucket(profileId, effectiveRate);
        return bucket.nextAvailableInstant(effectiveRate);
    }

    @Override
    public boolean tryAcquire(String profileId) {
        double effectiveRate = effectiveRate(profileId);

        if (redisEnabled) {
            try {
                return redisTryAcquire(profileId, effectiveRate);
            } catch (Exception e) {
                log.warn("Redis unavailable for rate limiter (tryAcquire), falling back to in-memory: {}",
                        e.getMessage());
            }
        }

        // In-memory fallback
        TokenBucket bucket = getOrCreateBucket(profileId, effectiveRate);
        return bucket.tryAcquire(effectiveRate);
    }

    @Override
    public void recordRateLimit(String profileId) {
        RateLimitHistory history = rateLimitHistory.computeIfAbsent(
                profileId, k -> new RateLimitHistory());
        history.record(Instant.now());
        log.debug("Recorded rate-limit event for profile {}, count in window: {}",
                profileId, history.countInWindow(Instant.now()));
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Adaptive backoff
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Returns the effective rate (requests/sec) for the profile, applying
     * adaptive backoff reduction when triggered.
     */
    double effectiveRate(String profileId) {
        RateLimitHistory history = rateLimitHistory.get(profileId);
        if (history != null && history.isBackoffActive(Instant.now())) {
            return defaultRate * BACKOFF_RATE_FACTOR;
        }
        return defaultRate;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Redis operations
    // ────────────────────────────────────────────────────────────────────────────

    private boolean redisTryAcquire(String profileId, double rate) {
        String key = redisKey(profileId);
        long capacity = (long) Math.ceil(rate); // bucket capacity = rate (1 second of burst)
        long nowMs = System.currentTimeMillis();

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(REDIS_TRY_ACQUIRE_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script,
                List.of(key),
                capacity, rate, nowMs);
        return result != null && result == 1L;
    }

    private long redisNextAvailableMs(String profileId, double rate) {
        String key = redisKey(profileId);
        long capacity = (long) Math.ceil(rate);
        long nowMs = System.currentTimeMillis();

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(REDIS_NEXT_AVAILABLE_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script,
                List.of(key),
                capacity, rate, nowMs);
        return result != null ? result : 0L;
    }

    private String redisKey(String profileId) {
        return "adpilot:ratelimit:amazon_ads:" + profileId;
    }

    private boolean probeRedis() {
        var connectionFactory = redisTemplate.getConnectionFactory();
        if (connectionFactory == null) {
            log.info("Redis connection factory is not configured; using in-memory fallback");
            return false;
        }
        try {
            try (var connection = connectionFactory.getConnection()) {
                connection.ping();
            }
            return true;
        } catch (Exception e) {
            log.info("Redis not available for rate limiter; using in-memory fallback: {}", e.getMessage());
            return false;
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // In-memory token bucket fallback
    // ────────────────────────────────────────────────────────────────────────────

    private TokenBucket getOrCreateBucket(String profileId, double rate) {
        return localBuckets.computeIfAbsent(profileId, k -> new TokenBucket(rate));
    }

    /**
     * Thread-safe in-memory token bucket. Uses compare-and-swap on an
     * {@code AtomicLong} representation (tokens × 1000 for sub-token precision)
     * and never blocks.
     */
    static final class TokenBucket {

        private static final long PRECISION = 1000L; // milli-token precision

        private final AtomicLong tokensMillis; // tokens * PRECISION
        private final AtomicLong lastRefillNanos;
        private final long capacityMillis;

        TokenBucket(double rate) {
            long capacity = (long) Math.ceil(rate);
            this.capacityMillis = capacity * PRECISION;
            this.tokensMillis = new AtomicLong(capacityMillis);
            this.lastRefillNanos = new AtomicLong(System.nanoTime());
        }

        boolean tryAcquire(double rate) {
            refill(rate);
            while (true) {
                long current = tokensMillis.get();
                if (current < PRECISION) {
                    return false;
                }
                if (tokensMillis.compareAndSet(current, current - PRECISION)) {
                    return true;
                }
                // CAS failed — another thread consumed a token; retry without blocking
            }
        }

        Instant nextAvailableInstant(double rate) {
            refill(rate);
            long current = tokensMillis.get();
            if (current >= PRECISION) {
                return Instant.now();
            }
            // Calculate wait time for one token to become available
            double deficitTokens = (PRECISION - current) / (double) PRECISION;
            long waitMs = (long) Math.ceil(deficitTokens / rate * 1000.0);
            return Instant.now().plusMillis(waitMs);
        }

        private void refill(double rate) {
            long now = System.nanoTime();
            long lastRefill = lastRefillNanos.get();
            long elapsedNanos = now - lastRefill;
            if (elapsedNanos <= 0) {
                return;
            }
            // Calculate refill amount
            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
            long refillMillis = (long) (elapsedSeconds * rate * PRECISION);
            if (refillMillis <= 0) {
                return;
            }
            // Attempt to update the last refill time (only one thread wins)
            if (lastRefillNanos.compareAndSet(lastRefill, now)) {
                long capacity = capacityMillis;
                while (true) {
                    long current = tokensMillis.get();
                    long newVal = Math.min(capacity, current + refillMillis);
                    if (tokensMillis.compareAndSet(current, newVal)) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * Tracks rate-limit (429) events within a sliding window and manages
     * adaptive backoff state.
     */
    static final class RateLimitHistory {

        private final Deque<Instant> events = new ConcurrentLinkedDeque<>();
        private volatile Instant backoffUntil = Instant.MIN;

        void record(Instant now) {
            events.addLast(now);
            evict(now);
            if (countInWindow(now) > BACKOFF_TRIGGER_COUNT) {
                backoffUntil = now.plus(BACKOFF_DURATION);
            }
        }

        boolean isBackoffActive(Instant now) {
            return now.isBefore(backoffUntil);
        }

        int countInWindow(Instant now) {
            evict(now);
            return events.size();
        }

        private void evict(Instant now) {
            Instant windowStart = now.minus(BACKOFF_WINDOW);
            while (!events.isEmpty() && events.peekFirst().isBefore(windowStart)) {
                events.pollFirst();
            }
        }
    }
}
