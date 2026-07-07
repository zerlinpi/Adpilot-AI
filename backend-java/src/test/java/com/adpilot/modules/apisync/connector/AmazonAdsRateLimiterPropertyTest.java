package com.adpilot.modules.apisync.connector;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the Amazon Ads rate limiter's non-blocking behaviour
 * and token-bucket semantics.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 7: Rate limiter never blocks
 *
 * <p><b>Validates: Requirements 15.1, 15.2</b>
 *
 * <p>Properties validated:
 * <ol>
 *   <li>{@code tryAcquire()} always returns immediately (never blocks the caller thread)</li>
 *   <li>{@code nextAvailableInstant()} always returns immediately (never blocks)</li>
 *   <li>After exhausting all tokens, {@code tryAcquire()} returns false (not blocking)</li>
 *   <li>The rate limiter respects the configured rate (tokens refill at the expected rate)</li>
 * </ol>
 *
 * <p>Tests the in-memory implementation directly (no Redis needed) by providing a
 * {@link RedisTemplate} whose connection factory throws on {@code ping()}, forcing
 * the fallback path.
 */
@Label("Feature: amazon-ads-ai-hosting-system, Property 7: Rate limiter never blocks")
class AmazonAdsRateLimiterPropertyTest {

    /** Maximum time (ms) any single call is allowed to take before we deem it "blocking". */
    private static final long MAX_CALL_DURATION_MS = 50;

    // ────────────────────────────────────────────────────────────────────────────
    // Property 1: tryAcquire() always returns immediately
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 7: Rate limiter never blocks
     *
     * <p><b>Validates: Requirements 15.1, 15.2</b>
     *
     * <p>{@code tryAcquire()} completes within a strict wall-clock bound for any
     * profile and any configured rate, regardless of whether the bucket has tokens.
     */
    @Property(tries = 200)
    void tryAcquireNeverBlocks(
            @ForAll("profileIds") String profileId,
            @ForAll("rates") double rate) {

        AmazonAdsRateLimiter limiter = createInMemoryLimiter(rate);

        long start = System.nanoTime();
        boolean result = limiter.tryAcquire(profileId);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(elapsedMs)
                .as("tryAcquire() must return immediately (within %d ms), took %d ms", MAX_CALL_DURATION_MS, elapsedMs)
                .isLessThanOrEqualTo(MAX_CALL_DURATION_MS);
        // Result is a boolean — no exception, no hang
        assertThat(result).isIn(true, false);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 2: nextAvailableInstant() always returns immediately
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 7: Rate limiter never blocks
     *
     * <p><b>Validates: Requirements 15.1, 15.2</b>
     *
     * <p>{@code nextAvailableInstant()} completes within a strict wall-clock bound
     * for any profile and any configured rate, returning an {@link Instant} without
     * sleeping.
     */
    @Property(tries = 200)
    void nextAvailableInstantNeverBlocks(
            @ForAll("profileIds") String profileId,
            @ForAll("rates") double rate) {

        AmazonAdsRateLimiter limiter = createInMemoryLimiter(rate);

        long start = System.nanoTime();
        Instant next = limiter.nextAvailableInstant(profileId);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(elapsedMs)
                .as("nextAvailableInstant() must return immediately (within %d ms), took %d ms",
                        MAX_CALL_DURATION_MS, elapsedMs)
                .isLessThanOrEqualTo(MAX_CALL_DURATION_MS);
        assertThat(next).isNotNull();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 3: After exhausting tokens, tryAcquire returns false (not blocking)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 7: Rate limiter never blocks
     *
     * <p><b>Validates: Requirements 15.1, 15.2</b>
     *
     * <p>After draining all tokens from the bucket, subsequent {@code tryAcquire()}
     * calls return {@code false} without blocking. The number of successful acquires
     * never exceeds the bucket capacity (ceil of the rate).
     */
    @Property(tries = 200)
    void afterExhaustingTokensTryAcquireReturnsFalseWithoutBlocking(
            @ForAll("profileIds") String profileId,
            @ForAll("rates") double rate) {

        AmazonAdsRateLimiter limiter = createInMemoryLimiter(rate);
        int capacity = (int) Math.ceil(rate);

        // Drain all tokens
        int acquired = 0;
        for (int i = 0; i < capacity + 5; i++) {
            if (limiter.tryAcquire(profileId)) {
                acquired++;
            }
        }

        // We should have acquired exactly capacity tokens (initial bucket fill)
        assertThat(acquired)
                .as("Number of acquired tokens should equal bucket capacity (ceil of rate)")
                .isEqualTo(capacity);

        // Now the bucket is exhausted — subsequent calls must return false immediately
        long start = System.nanoTime();
        boolean exhaustedResult = limiter.tryAcquire(profileId);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(exhaustedResult)
                .as("tryAcquire() must return false when tokens are exhausted")
                .isFalse();
        assertThat(elapsedMs)
                .as("tryAcquire() must not block even when tokens are exhausted")
                .isLessThanOrEqualTo(MAX_CALL_DURATION_MS);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 4: Rate limiter respects the configured rate (tokens refill)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 7: Rate limiter never blocks
     *
     * <p><b>Validates: Requirements 15.1, 15.2</b>
     *
     * <p>After exhausting all tokens, {@code nextAvailableInstant()} returns a
     * future instant that is consistent with the configured refill rate. The wait
     * time is bounded by {@code 1/rate} seconds (time to refill one token) with a
     * tolerance margin for measurement.
     */
    @Property(tries = 200)
    void rateLimiterRespectsConfiguredRefillRate(
            @ForAll("profileIds") String profileId,
            @ForAll("rates") double rate) {

        AmazonAdsRateLimiter limiter = createInMemoryLimiter(rate);
        int capacity = (int) Math.ceil(rate);

        // Drain all tokens
        for (int i = 0; i < capacity + 5; i++) {
            limiter.tryAcquire(profileId);
        }

        // After exhaustion, nextAvailableInstant should be in the future
        Instant now = Instant.now();
        Instant next = limiter.nextAvailableInstant(profileId);

        // The next available instant should be after now (or very close due to refill during the loop)
        // and no more than ~1/rate seconds away (time to produce one token)
        long maxWaitMs = (long) Math.ceil(1000.0 / rate) + 50; // +50ms tolerance for execution time
        Duration waitDuration = Duration.between(now, next);

        assertThat(waitDuration.toMillis())
                .as("Next available instant should be at most 1/rate seconds (%d ms) in the future", maxWaitMs)
                .isLessThanOrEqualTo(maxWaitMs);

        // The next available instant should not be unreasonably far in the past
        // (which would indicate a broken calculation)
        assertThat(waitDuration.toMillis())
                .as("Next available instant should not be more than 1 second in the past")
                .isGreaterThanOrEqualTo(-1000L);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Creates an in-memory rate limiter by providing a RedisTemplate whose
     * connection factory throws on ping(), ensuring the fallback path is used.
     */
    @SuppressWarnings("unchecked")
    private static AmazonAdsRateLimiter createInMemoryLimiter(double rate) {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenThrow(new RuntimeException("Redis unavailable (test)"));

        return new AmazonAdsRateLimiterImpl(redisTemplate, rate);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Generators
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Amazon Ads profile IDs: non-blank alphanumeric strings of realistic length.
     */
    @Provide
    Arbitrary<String> profileIds() {
        return Arbitraries.strings()
                .withCharRange('A', 'Z')
                .numeric()
                .ofMinLength(1)
                .ofMaxLength(20);
    }

    /**
     * Rate values (requests per second): realistic positive rates between 1 and 100.
     * Covers the default (10) and both lower/higher configurations.
     */
    @Provide
    Arbitrary<Double> rates() {
        return Arbitraries.doubles().between(1.0, 100.0);
    }
}
