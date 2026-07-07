package com.adpilot.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM symmetric encryption for sensitive data at rest (e.g. third-party
 * platform API credentials stored in {@code platform_connections.config}).
 *
 * <p>The 256-bit key is derived by SHA-256 over a configured secret
 * ({@code adpilot.crypto.secret}). Ciphertext is stored as
 * {@code enc:v1:<base64(iv|ciphertext|tag)>} so we can distinguish encrypted
 * values from any legacy plaintext and migrate transparently.</p>
 */
@Slf4j
@Component
public class CryptoUtil {

    private static final String PREFIX = "enc:v1:";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;       // 96-bit nonce (GCM standard)
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec keySpec;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * @param secret the configured {@code adpilot.crypto.secret}. There is no
     *               hardcoded fallback: an absent/blank value fails fast at startup
     *               (mirrors the production JWT-secret requirement) so a deployment
     *               can never silently encrypt credentials under a well-known
     *               default key. The {@code dev} profile (active by default) and
     *               tests supply an explicit value, so local/test runs are unaffected.
     */
    public CryptoUtil(@Value("${adpilot.crypto.secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "adpilot.crypto.secret is not configured. Set the ADPILOT_CRYPTO_SECRET "
                            + "environment variable (or adpilot.crypto.secret property) to a strong, "
                            + "non-blank secret before starting the application.");
        }
        this.keySpec = deriveKey(secret);
    }

    private static SecretKeySpec deriveKey(String secret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] key = sha.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive crypto key", e);
        }
    }

    /** Encrypt plaintext; returns a prefixed, base64-encoded blob. Null/blank passes through. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ct, 0, combined, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * Decrypt a value produced by {@link #encrypt}. If the value is not in our
     * encrypted format (legacy plaintext), it is returned unchanged so existing
     * rows keep working.
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (!stored.startsWith(PREFIX)) {
            return stored; // legacy plaintext
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH];
            byte[] ct = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to decrypt stored credential: {}", e.getMessage());
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
