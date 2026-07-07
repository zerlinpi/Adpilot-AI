package com.adpilot.modules.advertising.operation.callback;

/**
 * Processes a signature-verified inbound platform callback into an idempotent Operation Sync_State
 * transition (Req 5.7, 53.2, 55.4, 55.6).
 *
 * <p>This service is invoked by the {@code CallbackController} ONLY after the callback's signature
 * has been verified (Req 55.5), so it never sees an unauthenticated callback. It owns the
 * post-authentication processing rules:</p>
 *
 * <ol>
 *   <li>correlate the callback to its Operation by {@code submissionIdempotencyKey} (primary) or
 *       {@code platformReference} (fallback, Req 55.7);</li>
 *   <li>refuse to process callbacks for a Store that is not write-capable (Req 53.2, Property 12);</li>
 *   <li>map the platform's raw status to a single Sync_State via the platform connector
 *       ({@code mapPlatformStatus}, Req 55.4);</li>
 *   <li>apply the corresponding transition idempotently, so re-delivering the same platform result
 *       produces the same final Sync_State (Req 5.7, Property 18) and never regresses a settled
 *       Operation.</li>
 * </ol>
 *
 * <p>The {@code OperationStateMachine} remains the sole transition authority — this service routes
 * through {@code OperationService.transition} rather than mutating {@code sync_state} directly.</p>
 *
 * <p>Validates: Requirements 5.7, 53.2, 55.6.</p>
 */
public interface OperationCallbackService {

    /**
     * Process a signature-verified callback. The caller MUST have verified the signature first
     * (Req 55.5); this method assumes authenticity.
     *
     * @param callback the normalized, already-authenticated callback payload
     * @return the processing outcome (a transition was applied, or a stated non-mutating no-op)
     */
    CallbackOutcome process(PlatformCallback callback);
}
