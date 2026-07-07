package com.adpilot.modules.advertising.operation.callback;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HmacCallbackSignatureVerifier} (task 10.3).
 *
 * <p>Confirms the mandatory, fail-closed authenticity gate of Requirement 55.5: a correctly signed
 * callback verifies, while a missing secret, missing signature, or tampered body/signature is
 * rejected.</p>
 *
 * <p>Validates: Requirements 55.5.</p>
 */
class HmacCallbackSignatureVerifierTest {

    private static final String SECRET = "test-signing-secret";

    private static String sign(String secret, String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    @Test
    void verifiesCorrectlySignedCallback() throws Exception {
        HmacCallbackSignatureVerifier verifier = new HmacCallbackSignatureVerifier(SECRET);
        String body = "{\"submissionIdempotencyKey\":\"k1\",\"platformStatus\":\"SUCCESS\"}";
        String ts = "1700000000";

        assertThat(verifier.verify("amazon_ads", ts, sign(SECRET, ts, body), body)).isTrue();
    }

    @Test
    void toleratesSha256Prefix() throws Exception {
        HmacCallbackSignatureVerifier verifier = new HmacCallbackSignatureVerifier(SECRET);
        String body = "{\"platformStatus\":\"FAILED\"}";
        String ts = "1700000001";

        assertThat(verifier.verify("amazon_ads", ts, "sha256=" + sign(SECRET, ts, body), body)).isTrue();
    }

    @Test
    void failsClosedWhenNoSecretConfigured() throws Exception {
        HmacCallbackSignatureVerifier verifier = new HmacCallbackSignatureVerifier("");
        String body = "{\"platformStatus\":\"SUCCESS\"}";
        String ts = "1700000000";

        // Even a signature computed with some secret is rejected when the server has no secret.
        assertThat(verifier.verify("amazon_ads", ts, sign(SECRET, ts, body), body)).isFalse();
    }

    @Test
    void rejectsMissingSignature() {
        HmacCallbackSignatureVerifier verifier = new HmacCallbackSignatureVerifier(SECRET);
        assertThat(verifier.verify("amazon_ads", "1700000000", null, "{}")).isFalse();
        assertThat(verifier.verify("amazon_ads", "1700000000", "  ", "{}")).isFalse();
    }

    @Test
    void rejectsTamperedBody() throws Exception {
        HmacCallbackSignatureVerifier verifier = new HmacCallbackSignatureVerifier(SECRET);
        String body = "{\"platformStatus\":\"SUCCESS\"}";
        String ts = "1700000000";
        String signature = sign(SECRET, ts, body);

        String tamperedBody = "{\"platformStatus\":\"FAILED\"}";
        assertThat(verifier.verify("amazon_ads", ts, signature, tamperedBody)).isFalse();
    }

    @Test
    void rejectsWrongSecret() throws Exception {
        HmacCallbackSignatureVerifier verifier = new HmacCallbackSignatureVerifier(SECRET);
        String body = "{\"platformStatus\":\"SUCCESS\"}";
        String ts = "1700000000";

        assertThat(verifier.verify("amazon_ads", ts, sign("other-secret", ts, body), body)).isFalse();
    }
}
