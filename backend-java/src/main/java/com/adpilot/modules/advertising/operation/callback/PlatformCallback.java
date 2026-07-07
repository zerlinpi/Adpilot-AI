package com.adpilot.modules.advertising.operation.callback;

/**
 * The normalized payload of an inbound platform callback that reports the current status of a
 * previously submitted {@code platform_mutation} Operation (Req 55.5, 55.7).
 *
 * <p>A callback is correlated back to its Operation primarily by the
 * {@code submissionIdempotencyKey} the Operation was submitted with (Req 5.2/5.7), with the
 * platform's own {@code platformReference} (Req 55.7) as a fallback correlation key. The raw
 * {@code platformStatus} string is resolved to a single {@code SyncState} by the platform's
 * connector ({@code mapPlatformStatus}, Req 55.4) before any transition is applied.</p>
 *
 * @param platform                 the platform the callback claims to originate from
 * @param submissionIdempotencyKey the submission key the Operation was submitted with (primary
 *                                 correlation key, Req 5.7)
 * @param platformReference        the platform's own reference for the change (fallback correlation
 *                                 key, Req 55.7); may be {@code null}
 * @param platformStatus           the platform's raw status string, mapped to a Sync_State (Req 55.4)
 * @param message                  optional human-readable status detail recorded as the status reason
 */
public record PlatformCallback(String platform,
                               String submissionIdempotencyKey,
                               String platformReference,
                               String platformStatus,
                               String message) {
}
