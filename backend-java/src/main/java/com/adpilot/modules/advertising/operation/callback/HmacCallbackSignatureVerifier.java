package com.adpilot.modules.advertising.operation.callback;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Default {@link CallbackSignatureVerifier} — an HMAC-SHA256 verifier that fails closed (Req 55.5).
 *
 * <p>The expected signature is {@code HMAC-SHA256(secret, timestamp + "." + rawBody)} rendered as a
 * lowercase hex string. The platform computes the same value with the shared signing secret and
 * sends it on the callback; this verifier recomputes it over the EXACT raw body received and compares
 * the two in constant time. An accepted {@code "sha256="} prefix on the supplied signature is
 * tolerated.</p>
 *
 * <p>Verification is mandatory and fails closed in every degenerate case, because the callback
 * endpoint is network-exposed and unauthenticated apart from this gate:</p>
 * <ul>
 *   <li>no signing secret configured ({@code adpilot.advertising.callback.signing-secret} blank) —
 *       the endpoint cannot prove authenticity, so it rejects ALL callbacks rather than trusting
 *       them;</li>
 *   <li>a missing or blank signature header — rejected;</li>
 *   <li>a malformed or mismatched signature — rejected.</li>
 * </ul>
 *
 * <p>The shared secret is read from configuration and is never logged. The constant-time comparison
 * avoids leaking match progress through timing.</p>
 *
 * <p>Validates: Requirements 55.5.</p>
 */
@Slf4j
@Component
public class HmacCallbackSignatureVerifier implements CallbackSignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * Shared signing secret for inbound platform callbacks. Left BLANK by default so an
     * unconfigured deployment fails closed (rejects every callback) rather than silently trusting
     * unsigned traffic. Override via {@code ADPILOT_ADVERTISING_CALLBACK_SIGNING_SECRET}.
     */
    private final String signingSecret;

    public HmacCallbackSignatureVerifier(
            @Value("${adpilot.advertising.callback.signing-secret:}") String signingSecret) {
        this.signingSecret = signingSecret;
    }

    @Override
    public boolean verify(String platform, String timestamp, String signature, String rawBody) {
        // Fail closed: with no configured secret the endpoint cannot prove authenticity (Req 55.5).
        if (isBlank(signingSecret)) {
            log.warn("Inbound platform callback rejected: no callback signing secret is configured");
            return false;
        }
        // Fail closed: a callback without a signature is never trusted (Req 55.5).
        if (isBlank(signature)) {
            log.warn("Inbound platform callback rejected: missing signature header");
            return false;
        }

        String expected = computeHmacHex(timestamp, rawBody == null ? "" : rawBody);
        if (expected == null) {
            // HMAC computation failed (environment error) — fail closed rather than accept.
            return false;
        }

        String provided = stripPrefix(signature.trim());
        boolean matches = constantTimeEquals(expected, provided.toLowerCase());
        if (!matches) {
            log.warn("Inbound platform callback rejected: signature mismatch (platform={})", platform);
        }
        return matches;
    }

    /** {@code HMAC-SHA256(secret, timestamp + "." + rawBody)} as lowercase hex; {@code null} on error. */
    private String computeHmacHex(String timestamp, String rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            String signingBase = (timestamp == null ? "" : timestamp) + "." + rawBody;
            byte[] digest = mac.doFinal(signingBase.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            log.warn("Inbound platform callback signature verification failed to compute HMAC: {}",
                    e.getMessage());
            return null;
        }
    }

    /** Tolerate a {@code sha256=} prefix some platforms prepend to the hex signature. */
    private static String stripPrefix(String signature) {
        int eq = signature.indexOf('=');
        if (eq >= 0 && signature.regionMatches(true, 0, "sha256", 0, 6)) {
            return signature.substring(eq + 1);
        }
        return signature;
    }

    /** Constant-time string comparison to avoid leaking match progress via timing. */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
