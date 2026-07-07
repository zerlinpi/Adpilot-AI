package com.adpilot.modules.auth.service;

import com.adpilot.modules.user.entity.User;

/**
 * Encapsulates per-account login protection (Req 11.1.1, 11.1.2, 11.1.4,
 * 11.1.5, 11.1.6): the consecutive failed-attempt counter, account lockout,
 * and recording of every login attempt outcome in the login log.
 */
public interface LoginSecurityService {

    /**
     * Whether the account is currently locked (locked_until is set and in the future).
     *
     * @param user the account to check
     * @return true if the account is locked right now
     */
    boolean isLocked(User user);

    /**
     * Record an attempt against an unknown account (no matching user) as a
     * failed login in the login log (Req 11.1.4).
     */
    void recordUnknownUser(String email, String ipAddress, String userAgent);

    /**
     * Record a login attempt that was rejected because the account is locked
     * (Req 11.1.2, 11.1.4). Does not change the failed-attempt counter.
     */
    void recordLockedAttempt(User user, String email, String ipAddress, String userAgent);

    /**
     * Record a failed login attempt for a known account: increment the
     * per-account consecutive failed-attempt counter, lock the account when the
     * configured limit is reached (Req 11.1.1, 11.1.6), and log the outcome
     * (Req 11.1.4).
     */
    void recordFailedAttempt(User user, String email, String ipAddress, String userAgent, String reason);

    /**
     * Record a non-credential failure (e.g. disabled account) in the login log
     * without touching the failed-attempt counter (Req 11.1.4).
     */
    void recordRejectedAttempt(User user, String email, String ipAddress, String userAgent, String reason);

    /**
     * Record a successful login: reset the consecutive failed-attempt counter
     * and clear any lock (Req 11.1.5), update last-login time, and log the
     * outcome (Req 11.1.4).
     */
    void recordSuccessfulLogin(User user, String email, String ipAddress, String userAgent);
}
