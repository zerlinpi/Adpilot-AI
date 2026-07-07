package com.adpilot.modules.apisync.model;

/**
 * The outcome of a read-after-write verification call via
 * {@link com.adpilot.modules.apisync.connector.PlatformWriteConnector#verify}.
 *
 * <p>The business layer (VerificationWorker) uses this to compare the live value
 * against the Operation's expected {@code after_value}. A match advances the
 * Operation to {@code effective}; a mismatch to {@code reconciliation_required};
 * a read failure triggers retry or timeout handling (Req 1.13, 1.14, 16.6, 20.2, 20.3).
 *
 * @param read       {@code true} if the platform entity was successfully read;
 *                   {@code false} when verification is unsupported or the read failed
 * @param entityType the type of entity that was read (e.g. "keyword", "campaign")
 * @param field      the specific field that was verified (e.g. "bid", "dailyBudget", "state")
 * @param liveValue  the current value of the field on the platform; {@code null} if read failed
 * @param message    diagnostic message (e.g. unsupported reason, read error details)
 */
public record PlatformVerifyResult(
        boolean read,
        String entityType,
        String field,
        String liveValue,
        String message) {

    /**
     * A successful read returning the live field value for comparison.
     *
     * @param entityType the entity type read
     * @param field      the field read
     * @param liveValue  the current value on the platform
     */
    public static PlatformVerifyResult success(String entityType, String field, String liveValue) {
        return new PlatformVerifyResult(true, entityType, field, liveValue, null);
    }

    /**
     * A read failure — the platform could not be reached or the entity was not found.
     *
     * @param message diagnostic details about the failure
     */
    public static PlatformVerifyResult readFailed(String message) {
        return new PlatformVerifyResult(false, null, null, null, message);
    }

    /**
     * Verification is not supported by this connector (default for connectors that
     * have not implemented the verify method).
     */
    public static PlatformVerifyResult unsupported() {
        return new PlatformVerifyResult(false, null, null, null, "Verification not supported by this connector");
    }
}
