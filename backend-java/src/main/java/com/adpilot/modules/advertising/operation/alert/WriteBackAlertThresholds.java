package com.adpilot.modules.advertising.operation.alert;

import java.time.Duration;
import java.util.Objects;

/**
 * The configurable thresholds that govern when a write-back failure alert is raised (Req 51.7), all
 * of which are configurable backend values (Req 51.11):
 *
 * <ul>
 *   <li>{@code window} — the rolling window over which the failure rate is computed (default 15
 *       minutes);</li>
 *   <li>{@code minSample} — the minimum number of write-back attempts within the window before the
 *       failure-rate rule can fire (default 10), preventing a single early failure from tripping the
 *       alert;</li>
 *   <li>{@code failureRateThreshold} — the failure rate, in {@code [0,1]}, at or above which the
 *       rate rule fires (default 0.25);</li>
 *   <li>{@code consecutiveFailureThreshold} — the number of consecutive failures (independent of the
 *       window) at or above which the consecutive-failure rule fires (default 5).</li>
 * </ul>
 *
 * <p>Pure, immutable value with no Spring dependency so the evaluator stays unit/property-testable.
 * The {@link WriteBackAlertingImpl} builds an instance from the {@code adpilot.advertising.writeback-alert.*}
 * configuration so the operational thresholds can be tuned without code changes.</p>
 *
 * @param window                      rolling window duration; must be positive
 * @param minSample                   minimum windowed attempts before the rate rule fires; {@code >= 0}
 * @param failureRateThreshold        failure rate in {@code [0,1]} at/above which the rate rule fires
 * @param consecutiveFailureThreshold consecutive-failure count at/above which the consecutive rule
 *                                    fires; {@code >= 0} (0 disables the rule)
 */
public record WriteBackAlertThresholds(Duration window,
                                       int minSample,
                                       double failureRateThreshold,
                                       int consecutiveFailureThreshold) {

    public WriteBackAlertThresholds {
        Objects.requireNonNull(window, "window must not be null");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be a positive duration");
        }
        if (minSample < 0) {
            throw new IllegalArgumentException("minSample must not be negative");
        }
        if (failureRateThreshold < 0.0d || failureRateThreshold > 1.0d) {
            throw new IllegalArgumentException("failureRateThreshold must be within [0,1]");
        }
        if (consecutiveFailureThreshold < 0) {
            throw new IllegalArgumentException("consecutiveFailureThreshold must not be negative");
        }
    }

    /** The Requirement 51.7 defaults: 15-minute window, 10 attempts, 25% rate, 5 consecutive. */
    public static WriteBackAlertThresholds defaults() {
        return new WriteBackAlertThresholds(Duration.ofMinutes(15), 10, 0.25d, 5);
    }
}
