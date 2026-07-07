package com.adpilot.modules.auth.service;

import com.adpilot.modules.user.entity.User;

/**
 * Two-factor authentication (TOTP) verification for login and sensitive-action
 * re-confirmation (Req 11.2.2, Req 11.2.3).
 *
 * <p>The shared secret is stored on {@code users.twofa_secret} (encrypted at
 * rest). This service decrypts it on demand and verifies a user-supplied code
 * without ever logging the secret or the code.
 */
public interface TwoFactorService {

    /**
     * Whether two-factor authentication is enabled for the account.
     */
    boolean isEnabled(User user);

    /**
     * Verify a TOTP code for a user whose 2FA is enabled.
     *
     * @param user the account (must have a configured secret)
     * @param code the code supplied by the user
     * @return {@code true} if the code is valid within the accepted time window
     */
    boolean verifyCode(User user, String code);
}
