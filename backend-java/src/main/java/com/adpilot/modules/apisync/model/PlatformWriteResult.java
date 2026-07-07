package com.adpilot.modules.apisync.model;

/**
 * The outcome of submitting a {@link PlatformChange} to a live platform via a
 * {@link com.adpilot.modules.apisync.connector.PlatformWriteConnector}.
 *
 * <p>Captures whether the platform accepted the change, an optional platform
 * reference (the id/handle the platform returned for the change), a
 * human-readable message, and structured Amazon Ads fields (Req 1.11, 1.12,
 * 16.5). The full result is recorded in the audit trail (Req 13.1.2). When
 * {@link #accepted()} is {@code false}, the rejection {@link #message()} is the
 * failure reason persisted to the audit trail while the internal record is left
 * unchanged (Req 13.1.3).
 *
 * @param accepted            {@code true} when the platform accepted the change
 * @param platformReference   the platform-assigned reference for the change, if any
 * @param message             a success message, or the platform's rejection reason
 * @param amazonRequestId     the Amazon request ID returned on success (Req 1.11 / 16.5)
 * @param externalEntityId    the Amazon external entity ID affected (Req 1.11 / 16.5)
 * @param platformErrorCode   the Amazon error code on rejection (Req 1.12 / 16.5)
 * @param retryable           whether the rejection is retryable (Req 1.8 / 1.9 / 16.5)
 * @param retryAfterSeconds   nullable backoff hint in seconds (Req 1.8 / 16.5)
 */
public record PlatformWriteResult(
        boolean accepted,
        String platformReference,
        String message,
        String amazonRequestId,
        String externalEntityId,
        String platformErrorCode,
        boolean retryable,
        Long retryAfterSeconds) {

    // -------------------------------------------------------------------------
    // Existing 3-arg factories retained for backward compatibility.
    // They delegate to the canonical constructor with null/false defaults.
    // -------------------------------------------------------------------------

    /** An accepted result carrying the platform reference and an optional message. */
    public static PlatformWriteResult accepted(String platformReference, String message) {
        return new PlatformWriteResult(true, platformReference, message, null, null, null, false, null);
    }

    /** A rejected result carrying the platform's failure reason. */
    public static PlatformWriteResult rejected(String reason) {
        return new PlatformWriteResult(false, null, reason, null, null, null, false, null);
    }

    // -------------------------------------------------------------------------
    // New factories for Amazon Ads structured results (Req 1.11, 1.12, 16.5)
    // -------------------------------------------------------------------------

    /**
     * An accepted result from the Amazon Ads API carrying structured identifiers.
     *
     * @param amazonRequestId  the Amazon request ID from the response
     * @param externalEntityId the external entity ID that was affected
     * @param message          an optional success message
     */
    public static PlatformWriteResult acceptedAmazon(String amazonRequestId, String externalEntityId, String message) {
        return new PlatformWriteResult(true, amazonRequestId, message, amazonRequestId, externalEntityId, null, false, null);
    }

    /**
     * A retryable rejection — the caller (Outbox) should schedule a later retry
     * rather than marking the Operation failed.
     *
     * @param reason            the rejection reason (e.g. "Rate limited", "Service unavailable")
     * @param retryAfterSeconds nullable hint for how long to wait before retrying
     */
    public static PlatformWriteResult retryable(String reason, Long retryAfterSeconds) {
        return new PlatformWriteResult(false, null, reason, null, null, null, true, retryAfterSeconds);
    }

    /**
     * A permanent (non-retryable) rejection carrying the platform error code.
     *
     * @param platformErrorCode the structured error code from the platform (e.g. "INVALID_BID_AMOUNT")
     * @param reason            the human-readable rejection reason
     */
    public static PlatformWriteResult permanentReject(String platformErrorCode, String reason) {
        return new PlatformWriteResult(false, null, reason, null, null, platformErrorCode, false, null);
    }
}
