package com.adpilot.modules.apisync.model;

/**
 * The result of requesting cancellation of an already-submitted or in-flight
 * change on a live platform, via
 * {@link com.adpilot.modules.apisync.connector.PlatformWriteConnector#requestCancel}.
 *
 * <p>A local cancel does NOT guarantee the platform stops; the platform may
 * report the change as already applied, accept the cancellation request, or
 * error. This result carries which of those happened so the operation state
 * machine can drive the {@code cancel_requested} path of Requirement 4 to the
 * correct resolution ({@code cancelled}, {@code effective} with a compensating
 * rollback, or {@code reconciliation_required}) — and never resolve a failed
 * cancellation to {@code failed} (Req 4.9, 55.3).
 *
 * @param outcome           the cancellation outcome the platform reported
 * @param platformReference the platform-assigned reference the cancellation was
 *                          requested for (Req 55.7)
 * @param message           a human-readable message or the platform's detail;
 *                          may be {@code null}
 */
public record PlatformCancelResult(Outcome outcome, String platformReference, String message) {

    /**
     * The outcome of a cancellation request, mapped by the operation state
     * machine onto the {@code cancel_requested} resolution of Requirement 4.
     */
    public enum Outcome {
        /** The platform accepted the cancellation request; the final state is still being resolved. */
        REQUESTED,
        /** The platform confirmed the change was NOT applied (resolves to {@code cancelled}). */
        CANCELLED,
        /** The platform reports the change was already applied (resolves to {@code effective}). */
        ALREADY_APPLIED,
        /** The platform does not support cancellation for this change. */
        UNSUPPORTED,
        /** The cancellation request itself errored (resolves to {@code reconciliation_required}). */
        ERROR
    }

    /** The platform accepted the cancellation request; final state is pending resolution. */
    public static PlatformCancelResult requested(String platformReference, String message) {
        return new PlatformCancelResult(Outcome.REQUESTED, platformReference, message);
    }

    /** The platform confirmed the change was not applied. */
    public static PlatformCancelResult cancelled(String platformReference, String message) {
        return new PlatformCancelResult(Outcome.CANCELLED, platformReference, message);
    }

    /** The platform reports the change was already applied and cannot be cancelled. */
    public static PlatformCancelResult alreadyApplied(String platformReference, String message) {
        return new PlatformCancelResult(Outcome.ALREADY_APPLIED, platformReference, message);
    }

    /** The platform does not support cancellation. */
    public static PlatformCancelResult unsupported(String platformReference) {
        return new PlatformCancelResult(Outcome.UNSUPPORTED, platformReference, "platform does not support cancellation");
    }

    /** The cancellation request errored and the platform state must be reconciled. */
    public static PlatformCancelResult error(String platformReference, String reason) {
        return new PlatformCancelResult(Outcome.ERROR, platformReference, reason);
    }
}
