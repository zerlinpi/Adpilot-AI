package com.adpilot.modules.advertising.operation.callback;

/**
 * Verifies the authenticity / signature of an inbound platform callback BEFORE any Operation state
 * is mutated (Req 55.5).
 *
 * <p>The inbound callback endpoint ({@code CallbackController}) is a network-exposed,
 * server-to-server endpoint that skips JWT authentication (the platform's servers call it). Its sole
 * authenticity gate is this verifier: a callback whose signature cannot be verified is rejected and
 * mutates no Operation. Verification is MANDATORY — there is no "trust without a signature" path — so
 * a missing secret, missing signature, or mismatch all fail closed.</p>
 *
 * <p>Validates: Requirements 55.5.</p>
 */
public interface CallbackSignatureVerifier {

    /**
     * Verify an inbound callback's signature over its raw body.
     *
     * @param platform  the platform the callback claims to be from (used to resolve the signing
     *                  secret); may be {@code null}/blank, which fails closed
     * @param timestamp the timestamp header the signature was computed over; may be {@code null}
     * @param signature the signature header supplied by the caller; may be {@code null}/blank, which
     *                  fails closed
     * @param rawBody   the exact raw request body bytes the signature was computed over
     * @return {@code true} only when the signature is present, the signing secret is configured, and
     *         the recomputed signature matches; {@code false} (reject) in every other case
     */
    boolean verify(String platform, String timestamp, String signature, String rawBody);
}
