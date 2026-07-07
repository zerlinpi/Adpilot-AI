package com.adpilot.modules.apisync.connector;

import java.time.Instant;

/**
 * Per-profile token-bucket rate limiter for Amazon Ads API calls.
 *
 * <p>The limiter never blocks the caller thread. It returns either a permit
 * (via {@link #tryAcquire}) or the next instant a request can be made
 * (via {@link #nextAvailableInstant}), allowing the Outbox to set
 * {@code next_attempt_at} accordingly.
 *
 * <p>Backed by Redis when available; falls back to in-memory
 * {@code ConcurrentHashMap}-based buckets when Redis is unavailable
 * (Requirement 30.3).
 *
 * <p>Supports adaptive backoff: when more than 10 rate-limited responses
 * occur in 5 minutes, the effective rate is reduced by 50% for 15 minutes
 * (Requirement 15.6).
 *
 * @see <a href="Requirements 15.1, 15.2, 15.6, 30.3">Rate Limiting and Token Management</a>
 */
public interface AmazonAdsRateLimiter {

    /**
     * Returns the earliest instant at which a request can be made for the
     * given profile. If a token is available now, returns an instant at or
     * before {@code Instant.now()}.
     *
     * <p>This method NEVER blocks the caller thread.
     *
     * @param profileId the Amazon Ads profile ID (rate limits are per-profile)
     * @return the next instant a request can be sent; may be in the past or present if available now
     */
    Instant nextAvailableInstant(String profileId);

    /**
     * Attempts to acquire a single token for the given profile. Returns
     * {@code true} if the request can proceed immediately, {@code false}
     * if the rate limit would be exceeded.
     *
     * <p>This method NEVER blocks the caller thread.
     *
     * @param profileId the Amazon Ads profile ID
     * @return true if a token was acquired, false otherwise
     */
    boolean tryAcquire(String profileId);

    /**
     * Records that a rate-limit response (HTTP 429) was received for the
     * given profile. Used to drive adaptive backoff: when more than 10
     * rate-limited responses occur within 5 minutes, the effective rate is
     * reduced by 50% for 15 minutes.
     *
     * @param profileId the Amazon Ads profile ID that was rate-limited
     */
    void recordRateLimit(String profileId);
}
