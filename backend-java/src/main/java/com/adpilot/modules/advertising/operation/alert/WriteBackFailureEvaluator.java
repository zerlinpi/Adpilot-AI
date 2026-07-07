package com.adpilot.modules.advertising.operation.alert;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure, side-effect-free evaluator for the write-back failure alerting rule (Req 51.7, 51.11).
 *
 * <p>Given a sequence of {@link WriteBackAttempt}s for ONE Store + Write_Connector, an evaluation
 * time {@code now}, and the configured {@link WriteBackAlertThresholds}, it decides whether a
 * write-back failure alert should be raised. An alert fires when EITHER of two rules holds:</p>
 *
 * <ol>
 *   <li><b>Windowed failure rate.</b> Over the rolling window {@code (now - window, now]}, the number
 *       of attempts is at least {@code minSample} AND the failure rate is at or above
 *       {@code failureRateThreshold}.</li>
 *   <li><b>Consecutive failures.</b> The number of trailing consecutive failures (the most recent
 *       attempts at or before {@code now}, counted backwards until the first success) is at or above
 *       {@code consecutiveFailureThreshold}. This rule is independent of the rolling window — five
 *       failures in a row trip the alert however far apart they occurred.</li>
 * </ol>
 *
 * <p>The method is deterministic and free of any framework/persistence dependency so it can be
 * exhaustively property-tested (Property 80, task 10.14). Attempts whose timestamp is strictly after
 * {@code now} are ignored (they have not happened yet relative to the evaluation point).</p>
 *
 * <p>Validates: Requirements 51.7, 51.11.</p>
 */
public final class WriteBackFailureEvaluator {

    private WriteBackFailureEvaluator() {
        // Utility class — not instantiable.
    }

    /**
     * Evaluate the alerting rule for a single Store + Write_Connector.
     *
     * @param attempts   the observed write-back attempts; must not be {@code null} (may be empty)
     * @param now        the evaluation time; must not be {@code null}
     * @param thresholds the configured thresholds; must not be {@code null}
     * @return the alert decision with the figures it was based on
     */
    public static WriteBackAlertDecision evaluate(List<WriteBackAttempt> attempts,
                                                  Instant now,
                                                  WriteBackAlertThresholds thresholds) {
        Objects.requireNonNull(attempts, "attempts must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(thresholds, "thresholds must not be null");

        // Consider only attempts at or before the evaluation point, in chronological order.
        List<WriteBackAttempt> upToNow = attempts.stream()
                .filter(Objects::nonNull)
                .filter(a -> !a.timestamp().isAfter(now))
                .sorted(Comparator.comparing(WriteBackAttempt::timestamp))
                .toList();

        // Rule 1: windowed failure rate over (now - window, now].
        Instant windowStart = now.minus(thresholds.window());
        int windowedAttempts = 0;
        int windowedFailures = 0;
        for (WriteBackAttempt a : upToNow) {
            if (a.timestamp().isAfter(windowStart)) {
                windowedAttempts++;
                if (a.failed()) {
                    windowedFailures++;
                }
            }
        }
        boolean failureRateBreached = windowedAttempts > 0
                && windowedAttempts >= thresholds.minSample()
                && ((double) windowedFailures / windowedAttempts) >= thresholds.failureRateThreshold();

        // Rule 2: trailing consecutive failures (window-independent).
        int trailingConsecutiveFailures = 0;
        for (int i = upToNow.size() - 1; i >= 0; i--) {
            if (upToNow.get(i).success()) {
                break;
            }
            trailingConsecutiveFailures++;
        }
        boolean consecutiveFailuresBreached = thresholds.consecutiveFailureThreshold() > 0
                && trailingConsecutiveFailures >= thresholds.consecutiveFailureThreshold();

        return new WriteBackAlertDecision(
                failureRateBreached,
                consecutiveFailuresBreached,
                windowedAttempts,
                windowedFailures,
                trailingConsecutiveFailures);
    }
}
