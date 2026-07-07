package com.adpilot.modules.auth.service;

/**
 * Per-client-IP login attempt limiter (audit L3).
 *
 * <p>Per-account lockout ({@link LoginSecurityService}) throttles repeated
 * failures against a single account but does not defend against an attacker
 * spreading attempts across many accounts from one IP (distributed brute force /
 * account enumeration). This limiter counts failed login attempts per client IP
 * within a rolling window and blocks further attempts once a configurable
 * threshold is exceeded.
 *
 * <p><b>Fail-open:</b> the limiter is a defense-in-depth control, not a hard
 * gate. If the backing store errors it must never lock out legitimate users, so
 * {@link #isBlocked} returns {@code false} on any backend failure. It is also
 * fully toggleable via configuration.
 *
 * <p>Backed by Redis when available (so the limit is shared across instances),
 * with an in-memory fallback when Redis is not configured — mirroring the
 * established {@code AmazonAdsRateLimiter} pattern.
 */
public interface LoginRateLimiter {

    /**
     * Whether the given client IP has exceeded the failed-attempt threshold within
     * the current window and should be blocked from further login attempts.
     *
     * <p>Never throws: on any backend error this returns {@code false} (fail open).
     *
     * @param clientIp the resolved client IP (may be null/blank, treated as "not blocked")
     * @return true if the IP is currently over the threshold
     */
    boolean isBlocked(String clientIp);

    /**
     * Record a failed login attempt for the given client IP, advancing its counter
     * within the rolling window. Never throws.
     *
     * @param clientIp the resolved client IP
     */
    void recordFailure(String clientIp);

    /**
     * Reset the failed-attempt counter for the given client IP. Called on a
     * successful login so a legitimate user's occasional mistakes do not
     * accumulate toward the block. Never throws.
     *
     * @param clientIp the resolved client IP
     */
    void reset(String clientIp);
}
