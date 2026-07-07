package com.adpilot.modules.auth.service;

import com.adpilot.modules.auth.dto.LoginRequest;
import com.adpilot.modules.auth.dto.LoginResponse;
import com.adpilot.modules.auth.dto.UserInfo;

public interface AuthService {

    /**
     * Authenticate user with email and password, recording a login log.
     *
     * @param request   login credentials
     * @param ipAddress client IP (for the login log)
     * @param userAgent client user agent (for the login log)
     * @return login response with JWT token and user info
     */
    LoginResponse login(LoginRequest request, String ipAddress, String userAgent);

    /**
     * Get current user information by user ID.
     *
     * @param userId the user ID
     * @return user info including permissions
     */
    UserInfo getCurrentUser(String userId);

    /**
     * Re-confirm the user's identity for a sensitive action (Req 11.2.3) by
     * verifying their password and, when 2FA is enabled, a current TOTP code.
     * On success a short-lived re-confirmation window is opened.
     *
     * @param userId        the current user's id
     * @param password      the user's password
     * @param twoFactorCode the TOTP code (required when 2FA is enabled)
     */
    void reconfirmIdentity(String userId, String password, String twoFactorCode);

    /**
     * Change the current user's password after verifying the current password
     * and enforcing the configured password policy.
     *
     * @param userId          the current user's id
     * @param currentPassword the user's current password
     * @param newPassword     the replacement password
     */
    void changePassword(String userId, String currentPassword, String newPassword);

    /**
     * Administratively terminate all of a user's active sessions (Req 11.2.4).
     *
     * @param userId the user whose sessions are invalidated
     */
    void invalidateUserSessions(String userId);
}
