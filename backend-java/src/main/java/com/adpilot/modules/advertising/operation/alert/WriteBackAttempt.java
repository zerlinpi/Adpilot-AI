package com.adpilot.modules.advertising.operation.alert;

import java.time.Instant;
import java.util.Objects;

/**
 * A single write-back attempt outcome for one Store + Write_Connector, used by the
 * {@link WriteBackFailureEvaluator} to decide whether a write-back failure alert should be raised
 * (Req 51.7).
 *
 * <p>An attempt is the terminal result of submitting one {@code platform_mutation} Operation to the
 * platform: a {@link #success() success} corresponds to the Operation reaching {@code effective},
 * and a failure corresponds to it reaching {@code failed}. The {@code timestamp} is when the outcome
 * was observed; it is the basis of the rolling-window membership test.</p>
 *
 * <p>This is a pure, immutable value with no Spring/persistence dependency so the alerting decision
 * can be property-tested in isolation (Property 80, task 10.14).</p>
 *
 * @param timestamp when the attempt outcome was observed; never {@code null}
 * @param success   {@code true} iff the write-back succeeded (Operation reached {@code effective})
 */
public record WriteBackAttempt(Instant timestamp, boolean success) {

    public WriteBackAttempt {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    /** A successful write-back attempt observed at {@code timestamp}. */
    public static WriteBackAttempt success(Instant timestamp) {
        return new WriteBackAttempt(timestamp, true);
    }

    /** A failed write-back attempt observed at {@code timestamp}. */
    public static WriteBackAttempt failure(Instant timestamp) {
        return new WriteBackAttempt(timestamp, false);
    }

    /** @return {@code true} iff this attempt failed. */
    public boolean failed() {
        return !success;
    }
}
