package com.adpilot.modules.auth.service;

/**
 * Identity re-confirmation for sensitive actions (Req 11.2.3).
 *
 * <p>When a user re-confirms their identity (re-entering their password, plus a
 * 2FA code when enabled), a short-lived confirmation marker is recorded in Redis
 * keyed by user id. Actions designated as sensitive then require a live marker
 * before they execute; otherwise the action is rejected and the user is asked to
 * re-confirm.
 */
public interface ReconfirmationService {

    /**
     * Record a successful identity re-confirmation for the user, opening a
     * short-lived window during which sensitive actions may proceed.
     *
     * @param userId the re-confirmed user
     */
    void confirm(String userId);

    /**
     * Whether the user has a live re-confirmation marker.
     *
     * @param userId the user to check
     * @return {@code true} if a recent re-confirmation is still valid
     */
    boolean isConfirmed(String userId);

    /**
     * Clear any re-confirmation marker for the user (e.g. on logout).
     *
     * @param userId the user whose marker is cleared
     */
    void clear(String userId);
}
