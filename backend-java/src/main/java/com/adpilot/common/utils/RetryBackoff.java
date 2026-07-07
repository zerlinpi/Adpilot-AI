package com.adpilot.common.utils;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Retry backoff helper (reliability fix L2).
 *
 * <p>The retry loops in the codebase used a bare exponential backoff
 * ({@code base * 2^(attempt-1)}). When a shared dependency (Amazon Ads, Feishu)
 * recovers, every client that failed in the same window retries in lockstep — a
 * thundering herd that can re-overload the just-recovered dependency. Adding a
 * bounded random jitter spreads those retries out.</p>
 *
 * <p>The base/exponent are unchanged; this helper only adds jitter on top and an
 * upper cap. Jitter is drawn once per call from {@link ThreadLocalRandom} in
 * {@code [0, jitterFraction * cappedBackoff]}, so the returned delay is always in
 * {@code [cappedBackoff, cappedBackoff + jitterFraction * cappedBackoff]}.</p>
 */
public final class RetryBackoff {

    /** Default jitter as a fraction of the base backoff (50%). */
    public static final double DEFAULT_JITTER_FRACTION = 0.5;

    /** Default upper cap on the (pre-jitter) backoff to keep delays sane. */
    public static final long DEFAULT_MAX_BACKOFF_MS = 60_000L;

    private RetryBackoff() {
    }

    /**
     * Pure exponential backoff: {@code baseMs * 2^(attempt-1)}, unchanged from the
     * original computation.
     *
     * @param baseMs  base backoff in milliseconds
     * @param attempt 1-based attempt number
     * @return the exponential backoff in milliseconds
     */
    public static long exponential(long baseMs, int attempt) {
        return baseMs * (1L << (attempt - 1));
    }

    /**
     * Exponential backoff (capped at {@link #DEFAULT_MAX_BACKOFF_MS}) plus bounded
     * jitter using {@link #DEFAULT_JITTER_FRACTION}.
     *
     * @param baseMs  base backoff in milliseconds
     * @param attempt 1-based attempt number
     * @return jittered backoff in milliseconds
     */
    public static long withJitter(long baseMs, int attempt) {
        return withJitter(baseMs, attempt, DEFAULT_JITTER_FRACTION, DEFAULT_MAX_BACKOFF_MS);
    }

    /**
     * Exponential backoff plus bounded jitter.
     *
     * <p>The exponential term is first capped at {@code maxBackoffMs}; jitter in
     * {@code [0, jitterFraction * capped]} is then added. The result is therefore
     * always {@code >= capped} and {@code <= capped * (1 + jitterFraction)}.</p>
     *
     * @param baseMs         base backoff in milliseconds
     * @param attempt        1-based attempt number
     * @param jitterFraction jitter as a fraction of the capped backoff (clamped to {@code >= 0})
     * @param maxBackoffMs   upper cap applied to the exponential term before jitter
     * @return jittered backoff in milliseconds
     */
    public static long withJitter(long baseMs, int attempt, double jitterFraction, long maxBackoffMs) {
        long capped = Math.min(exponential(baseMs, attempt), maxBackoffMs);
        double fraction = Math.max(0.0, jitterFraction);
        long jitterSpan = (long) (capped * fraction);
        long jitter = jitterSpan > 0 ? ThreadLocalRandom.current().nextLong(0, jitterSpan + 1) : 0L;
        return capped + jitter;
    }
}
