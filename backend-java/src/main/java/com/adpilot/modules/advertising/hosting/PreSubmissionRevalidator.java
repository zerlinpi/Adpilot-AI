package com.adpilot.modules.advertising.hosting;

import java.util.UUID;

/**
 * Pre-submission revalidation service (Requirements 3.7, 33.1–33.5).
 *
 * <p>Before the OutboxWorker submits a queued Operation to the platform, this service
 * verifies that the decision is still valid:</p>
 * <ol>
 *   <li><b>Decision expiry</b>: checks that the associated {@code ai_decisions.expires_at}
 *       has not passed (Req 33.1, 33.3).</li>
 *   <li><b>Data quality re-check</b>: re-runs the {@link DataQualityGate} for the
 *       campaign to confirm data is still fresh and complete (Req 33.2).</li>
 *   <li><b>Safety boundary re-resolution</b>: re-resolves the effective boundary
 *       and verifies the proposed change still fits within the current limits (Req 33.2).</li>
 * </ol>
 *
 * <p>On any failure the Operation is transitioned to {@code superseded} via
 * {@link com.adpilot.modules.advertising.operation.TransitionEvent#SUPERSEDE},
 * with the reason recorded in the Operation's {@code status_reason} field and
 * the optimization run log (Req 33.5).</p>
 *
 * <p>This service is designed to be called by the OutboxWorker after claiming an Outbox
 * row but before invoking the platform connector.</p>
 */
public interface PreSubmissionRevalidator {

    /**
     * Result of pre-submission revalidation.
     *
     * @param valid   {@code true} if the Operation passed all checks and may be submitted
     * @param reason  when {@code valid} is {@code false}, the human-readable reason for rejection
     *                (e.g., "DECISION_EXPIRED", "DATA_STALE", "BOUNDARY_VIOLATION")
     */
    record RevalidationResult(boolean valid, String reason) {

        /** The Operation passed all pre-submission checks. */
        public static RevalidationResult pass() {
            return new RevalidationResult(true, null);
        }

        /** The decision has expired past its TTL. */
        public static RevalidationResult expired() {
            return new RevalidationResult(false, "DECISION_EXPIRED");
        }

        /** Data quality gate failed upon re-check. */
        public static RevalidationResult dataQualityFailed(String dqReason) {
            return new RevalidationResult(false, dqReason);
        }

        /** The proposed change no longer fits within the re-resolved safety boundary. */
        public static RevalidationResult boundaryViolation(String detail) {
            return new RevalidationResult(false, "BOUNDARY_VIOLATION: " + detail);
        }
    }

    /**
     * Revalidate a pending Operation before submission.
     *
     * <p>If validation fails, this method transitions the Operation to {@code superseded}
     * and records the reason — the caller (OutboxWorker) should skip submission and mark
     * the outbox row as done.</p>
     *
     * @param operationId the Operation to revalidate
     * @return the revalidation result; when {@code valid == false} the Operation has
     *         already been transitioned to superseded
     */
    RevalidationResult revalidate(UUID operationId);

    /**
     * Compute and return the decision expiry timestamp for a new decision.
     * Uses the configured TTL (default 4 hours).
     *
     * @return the {@code decision_expires_at} timestamp to set on the ai_decisions row
     */
    java.time.LocalDateTime computeExpiresAt();
}
