package com.adpilot.modules.advertising.operation.callback;

import com.adpilot.modules.advertising.operation.controller.CallbackController;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for signature-verified inbound platform callbacks (task 10.12).
 *
 * <p>Feature: advertising-workspace-rework, Property 77: Inbound callbacks are signature-verified
 * before acting.</p>
 *
 * <p>Validates: Requirements 55.5.</p>
 *
 * <p>Property 77 (transcribed from the design's Correctness Properties section): <em>For any inbound
 * platform callback, the Backend verifies its authenticity/signature before mutating any Operation
 * state; a callback whose signature cannot be verified is rejected and mutates no Operation.</em></p>
 *
 * <p>The test drives the <strong>real</strong> {@link CallbackController} wired to the
 * <strong>real</strong> {@link HmacCallbackSignatureVerifier} (the production fail-closed authenticity
 * gate, Req 55.5). The <em>only</em> path by which an inbound callback can mutate Operation state is
 * the {@link OperationCallbackService#process(PlatformCallback)} call the controller makes AFTER the
 * signature gate; that collaborator is a Mockito mock, so "mutates no Operation" is observed exactly
 * as "{@code process} was never invoked". The single shared signing secret is held by the verifier
 * only — the controller never decides authenticity itself.</p>
 *
 * <p>For each generated callback (arbitrary platform, timestamp, and body fields) and each generated
 * signature scenario, the property asserts:</p>
 * <ul>
 *   <li><b>Authentic callbacks</b> (a correct HMAC, optionally {@code sha256=}-prefixed) are accepted
 *       with HTTP 200 / {@code verified=true}, the processing collaborator is invoked exactly once,
 *       and — proving <em>verify-before-act</em> ordering — the signature verifier is consulted
 *       strictly before the processing collaborator.</li>
 *   <li><b>Unverifiable callbacks</b> (missing, blank, wrong-secret, body-tampered, timestamp-tampered,
 *       or garbage signatures) are rejected with HTTP 401 / {@code verified=false} and the processing
 *       collaborator is <em>never</em> invoked — so no Operation state is mutated.</li>
 * </ul>
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 77: Inbound callbacks are signature-verified before acting")
class SignatureVerifiedCallbackPropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    /** The server's configured signing secret (held only by the verifier). */
    private static final String SECRET = "callback-signing-secret-77";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The signature scenarios a real platform (or attacker) can present. */
    private enum SignatureScenario {
        /** Correct HMAC over the exact (timestamp, rawBody) — authentic. */
        VALID(true),
        /** Correct HMAC with the tolerated {@code sha256=} prefix — authentic. */
        VALID_PREFIXED(true),
        /** No signature header at all — must fail closed. */
        MISSING_NULL(false),
        /** Blank signature header — must fail closed. */
        MISSING_BLANK(false),
        /** HMAC computed with a different secret — must be rejected. */
        WRONG_SECRET(false),
        /** Signature computed over a different body than the one delivered — must be rejected. */
        TAMPERED_BODY(false),
        /** Signature computed over a different timestamp than the one delivered — must be rejected. */
        TAMPERED_TIMESTAMP(false),
        /** Arbitrary garbage hex — must be rejected. */
        GARBAGE(false);

        private final boolean authentic;

        SignatureScenario(boolean authentic) {
            this.authentic = authentic;
        }
    }

    /**
     * Feature: advertising-workspace-rework, Property 77: Inbound callbacks are signature-verified
     * before acting.
     *
     * <p>Validates: Requirements 55.5.</p>
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 77: a callback mutates an Operation only after its signature verifies, never otherwise")
    void callbackIsSignatureVerifiedBeforeActing(
            @ForAll("platforms") String platform,
            @ForAll("timestamps") String timestamp,
            @ForAll("submissionKeys") String submissionKey,
            @ForAll("platformStatuses") String platformStatus,
            @ForAll("scenarios") SignatureScenario scenario) throws Exception {

        // Build the exact raw JSON body the platform would POST; the controller passes this byte-exact
        // string to the verifier, so the authentic signature must be computed over this same string.
        String rawBody = body(submissionKey, platformStatus, platform);

        // Fresh real verifier + fresh mock processing collaborator per iteration so invocation counts
        // and ordering are isolated. The verifier is a spy so we can assert verify-before-act ordering
        // while still exercising the REAL fail-closed HMAC verification.
        HmacCallbackSignatureVerifier realVerifier = new HmacCallbackSignatureVerifier(SECRET);
        CallbackSignatureVerifier verifier = Mockito.spy(realVerifier);
        OperationCallbackService callbackService = Mockito.mock(OperationCallbackService.class);
        when(callbackService.process(any())).thenReturn(CallbackOutcome.PROCESSED);

        CallbackController controller = new CallbackController(verifier, callbackService, MAPPER);

        String signature = signatureFor(scenario, timestamp, rawBody);

        ResponseEntity<Map<String, Object>> response =
                controller.callback(platform, signature, timestamp, rawBody);

        if (scenario.authentic) {
            // Accepted: 200 + verified=true, and the Operation-mutating collaborator runs exactly once.
            assertThat(response.getStatusCode())
                    .as("authentic callback (%s) must be accepted", scenario)
                    .isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("verified", true);

            verify(callbackService, times(1)).process(any());

            // Verify-before-act: the signature gate is consulted strictly BEFORE the processing call.
            InOrder inOrder = Mockito.inOrder(verifier, callbackService);
            inOrder.verify(verifier).verify(any(), any(), any(), any());
            inOrder.verify(callbackService).process(any());
        } else {
            // Rejected: 401 + verified=false, and NO Operation state is mutated (process never runs).
            assertThat(response.getStatusCode())
                    .as("unverifiable callback (%s) must be rejected with 401", scenario)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).containsEntry("verified", false);

            verify(callbackService, never()).process(any());
        }
    }

    /** Produce the signature header for the given scenario over the delivered (timestamp, rawBody). */
    private static String signatureFor(SignatureScenario scenario, String timestamp, String rawBody)
            throws Exception {
        return switch (scenario) {
            case VALID -> sign(SECRET, timestamp, rawBody);
            case VALID_PREFIXED -> "sha256=" + sign(SECRET, timestamp, rawBody);
            case MISSING_NULL -> null;
            case MISSING_BLANK -> "   ";
            case WRONG_SECRET -> sign(SECRET + "-tampered", timestamp, rawBody);
            case TAMPERED_BODY -> sign(SECRET, timestamp, rawBody + "X");
            case TAMPERED_TIMESTAMP -> sign(SECRET, timestamp + "9", rawBody);
            case GARBAGE -> "deadbeef" + Integer.toHexString(rawBody.hashCode());
        };
    }

    /** {@code HMAC-SHA256(secret, timestamp + "." + body)} as lowercase hex (mirrors the verifier). */
    private static String sign(String secret, String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    /** Serialize a callback body to the exact JSON string the platform would POST. */
    private static String body(String submissionKey, String platformStatus, String platform)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("platform", platform);
        payload.put("submissionIdempotencyKey", submissionKey);
        payload.put("platformReference", "ref-" + submissionKey);
        payload.put("platformStatus", platformStatus);
        payload.put("message", "platform callback");
        return MAPPER.writeValueAsString(payload);
    }

    // --- generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> platforms() {
        return Arbitraries.of("amazon_ads", "amazon", "AMZ", "test_platform");
    }

    @Provide
    Arbitrary<String> timestamps() {
        return Arbitraries.longs().between(1_600_000_000L, 2_000_000_000L).map(String::valueOf);
    }

    @Provide
    Arbitrary<String> submissionKeys() {
        return Combinators.combine(
                        Arbitraries.strings().alpha().numeric().ofMinLength(4).ofMaxLength(12),
                        Arbitraries.integers().between(0, 9999))
                .as((s, n) -> "subkey-" + s + "-" + n);
    }

    @Provide
    Arbitrary<String> platformStatuses() {
        return Arbitraries.of(
                "SUCCESS", "succeeded", "FAILED", "CANCELLED", "IN_PROGRESS", "PENDING",
                "totally-unrecognized-status", "");
    }

    @Provide
    Arbitrary<SignatureScenario> scenarios() {
        return Arbitraries.of(SignatureScenario.values());
    }
}
