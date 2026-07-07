package com.adpilot.modules.auth.service.impl;

import com.adpilot.common.security.TotpVerifier;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.auth.service.TwoFactorService;
import com.adpilot.modules.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Default {@link TwoFactorService}. Treats {@code users.twofa_secret} as an
 * (optionally encrypted) Base32 TOTP secret: it is decrypted via
 * {@link CryptoUtil} (which passes through legacy plaintext) and verified with
 * {@link TotpVerifier}. Neither the secret nor the code is ever logged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TwoFactorServiceImpl implements TwoFactorService {

    private final TotpVerifier totpVerifier;
    private final CryptoUtil cryptoUtil;

    @Override
    public boolean isEnabled(User user) {
        return user != null
                && Boolean.TRUE.equals(user.getTwofaEnabled())
                && user.getTwofaSecret() != null
                && !user.getTwofaSecret().isBlank();
    }

    @Override
    public boolean verifyCode(User user, String code) {
        if (!isEnabled(user) || code == null || code.isBlank()) {
            return false;
        }
        final String secret;
        try {
            secret = cryptoUtil.decrypt(user.getTwofaSecret());
        } catch (Exception e) {
            // A secret that cannot be decrypted can never validate; do not leak details.
            log.warn("Unable to decrypt 2FA secret for user {}", user.getId());
            return false;
        }
        return totpVerifier.verify(secret, code);
    }
}
