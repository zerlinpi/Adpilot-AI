package com.adpilot.modules.advertising.operation.callback;

/**
 * The outcome of processing a (signature-verified) inbound platform callback through
 * {@link OperationCallbackService#process(PlatformCallback)}.
 *
 * <p>Every value other than {@link #PROCESSED} represents a deliberate, non-mutating no-op: the
 * callback was authentic but, for the stated reason, drove no Sync_State change. This makes the
 * idempotency (Req 5.7) and write-capability gating (Req 53.2) decisions observable to callers and
 * tests without exposing Operation internals.</p>
 */
public enum CallbackOutcome {

    /** The callback drove a legal Sync_State transition on the correlated Operation. */
    PROCESSED,

    /**
     * No Operation matched the callback's {@code submissionIdempotencyKey} (or
     * {@code platformReference}); nothing was mutated.
     */
    IGNORED_UNKNOWN_OPERATION,

    /**
     * The correlated Operation's Store is NOT write-capable, so platform callbacks for it are not
     * processed (Req 53.2, Property 12); nothing was mutated.
     */
    IGNORED_NOT_WRITE_CAPABLE,

    /**
     * The callback was redundant — the Operation is already in the mapped target state or in a
     * settled terminal state — so re-processing the same platform result produced the same final
     * state (Req 5.7, Property 18); nothing was mutated.
     */
    IGNORED_DUPLICATE
}
