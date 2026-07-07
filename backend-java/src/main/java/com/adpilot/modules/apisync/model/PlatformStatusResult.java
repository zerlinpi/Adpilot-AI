package com.adpilot.modules.apisync.model;

/**
 * The result of querying a live platform for the current status of a previously
 * submitted change, via
 * {@link com.adpilot.modules.apisync.connector.PlatformWriteConnector#queryStatus}.
 *
 * <p>This is the read-side counterpart to {@link PlatformWriteResult}: where the
 * latter is the immediate outcome of a {@code submit}, this carries the
 * platform's <em>current</em> status for an already-submitted Operation,
 * correlated by its {@code platformReference} (Req 55.7). It is used by both the
 * status poller and the reconciliation flow (Req 55.2).
 *
 * <p>The {@code queryStatus} operation is idempotent and non-mutating
 * (Req 55.6): repeated calls for the same Operation return the same result for
 * an unchanged platform state and never change platform state. The raw
 * {@link #platformStatus()} is resolved to a single
 * {@link com.adpilot.modules.advertising.operation.SyncState} via
 * {@link com.adpilot.modules.apisync.connector.PlatformWriteConnector#mapPlatformStatus(String)}
 * (Req 55.4).
 *
 * @param found             {@code true} when the platform recognized the
 *                          reference and returned a status; {@code false} when
 *                          the reference is unknown to the platform
 * @param platformReference the platform-assigned reference the status was queried
 *                          for (Req 55.7)
 * @param platformStatus    the platform's own raw status string for the change
 *                          (mapped to a Sync_State by {@code mapPlatformStatus});
 *                          may be {@code null} when {@code found} is {@code false}
 * @param message           a human-readable message or the platform's status
 *                          detail; may be {@code null}
 */
public record PlatformStatusResult(boolean found,
                                   String platformReference,
                                   String platformStatus,
                                   String message) {

    /** A found result carrying the platform's current raw status for the reference. */
    public static PlatformStatusResult of(String platformReference, String platformStatus, String message) {
        return new PlatformStatusResult(true, platformReference, platformStatus, message);
    }

    /** A found result carrying the platform's current raw status for the reference. */
    public static PlatformStatusResult of(String platformReference, String platformStatus) {
        return new PlatformStatusResult(true, platformReference, platformStatus, null);
    }

    /** A result indicating the platform did not recognize the supplied reference. */
    public static PlatformStatusResult notFound(String platformReference) {
        return new PlatformStatusResult(false, platformReference, null, "platform reference not found");
    }
}
