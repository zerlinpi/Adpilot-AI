package com.adpilot.common.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Self-contained RFC 6238 (TOTP) verifier built on RFC 4226 (HOTP) with an
 * HMAC-SHA1 primitive, so two-factor verification needs no extra dependency.
 *
 * <p>The shared secret is supplied as a Base32 string (RFC 4648, the de-facto
 * encoding used by authenticator apps). Verification accepts codes from the
 * current 30-second step plus a small backward/forward window to tolerate clock
 * skew. Secrets are never logged.
 */
@Component
public class TotpVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final int TIME_STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    /** Number of steps to check on each side of the current step (clock skew tolerance). */
    private static final int WINDOW_STEPS = 1;

    private static final int[] DIGITS_POWER = {1, 10, 100, 1000, 10000, 100000, 1000000, 10000000};

    /**
     * Verify a user-supplied TOTP code against a Base32 secret using the current
     * wall-clock time.
     *
     * @param base32Secret the shared secret, Base32-encoded (RFC 4648)
     * @param code         the code entered by the user
     * @return {@code true} if the code matches within the accepted time window
     */
    public boolean verify(String base32Secret, String code) {
        return verifyAt(base32Secret, code, System.currentTimeMillis() / 1000L);
    }

    /**
     * Verify a code at a specific Unix timestamp (seconds). Exposed for testing.
     */
    public boolean verifyAt(String base32Secret, String code, long epochSeconds) {
        if (base32Secret == null || base32Secret.isBlank() || code == null) {
            return false;
        }
        String normalized = code.trim();
        if (normalized.length() != DIGITS || !normalized.chars().allMatch(Character::isDigit)) {
            return false;
        }

        final byte[] key;
        try {
            key = base32Decode(base32Secret);
        } catch (IllegalArgumentException e) {
            // Malformed secret can never validate; do not leak details.
            return false;
        }
        if (key.length == 0) {
            return false;
        }

        long currentStep = epochSeconds / TIME_STEP_SECONDS;
        int expected;
        try {
            expected = Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            return false;
        }

        for (long step = currentStep - WINDOW_STEPS; step <= currentStep + WINDOW_STEPS; step++) {
            if (generate(key, step) == expected) {
                return true;
            }
        }
        return false;
    }

    /**
     * Generate the current 6-digit TOTP for a Base32 secret. Exposed so callers
     * (e.g. enrollment flows or tests) can derive an expected code.
     */
    public String generateCurrent(String base32Secret) {
        byte[] key = base32Decode(base32Secret);
        long step = (System.currentTimeMillis() / 1000L) / TIME_STEP_SECONDS;
        return String.format("%0" + DIGITS + "d", generate(key, step));
    }

    private int generate(byte[] key, long step) {
        byte[] data = ByteBuffer.allocate(Long.BYTES).putLong(step).array();
        byte[] hash = hmacSha1(key, data);

        // Dynamic truncation (RFC 4226 section 5.3).
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        return binary % DIGITS_POWER[DIGITS];
    }

    private byte[] hmacSha1(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute TOTP HMAC", e);
        }
    }

    /**
     * Decode a Base32 string (RFC 4648, no padding required, case-insensitive).
     */
    static byte[] base32Decode(String input) {
        String cleaned = input.trim().replace("=", "").replace(" ", "").toUpperCase();
        if (cleaned.isEmpty()) {
            return new byte[0];
        }
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        int buffer = 0;
        int bitsLeft = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (char c : cleaned.toCharArray()) {
            int val = alphabet.indexOf(c);
            if (val < 0) {
                throw new IllegalArgumentException("Invalid Base32 character");
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out.write((buffer >> bitsLeft) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
