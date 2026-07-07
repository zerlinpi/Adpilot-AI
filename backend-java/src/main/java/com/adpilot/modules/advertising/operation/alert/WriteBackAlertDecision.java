package com.adpilot.modules.advertising.operation.alert;

/**
 * The outcome of evaluating a sequence of write-back attempts against the configured thresholds
 * (Req 51.7). An alert is raised when EITHER rule fires:
 *
 * <ul>
 *   <li>{@code failureRateBreached} — over the rolling window, with at least the minimum sample, the
 *       failure rate is at or above the configured threshold;</li>
 *   <li>{@code consecutiveFailuresBreached} — the configured number of consecutive failures occurred
 *       (independent of the window).</li>
 * </ul>
 *
 * <p>The remaining fields expose the figures the decision was based on so they can be surfaced in the
 * alert detail and asserted in tests.</p>
 *
 * @param failureRateBreached         whether the windowed failure-rate rule fired
 * @param consecutiveFailuresBreached whether the consecutive-failure rule fired
 * @param windowedAttempts            number of attempts within the rolling window
 * @param windowedFailures            number of failed attempts within the rolling window
 * @param trailingConsecutiveFailures number of trailing consecutive failures up to the evaluation time
 */
public record WriteBackAlertDecision(boolean failureRateBreached,
                                     boolean consecutiveFailuresBreached,
                                     int windowedAttempts,
                                     int windowedFailures,
                                     int trailingConsecutiveFailures) {

    /** @return {@code true} iff an alert should be raised (either rule fired). */
    public boolean shouldAlert() {
        return failureRateBreached || consecutiveFailuresBreached;
    }

    /** The windowed failure rate in {@code [0,1]}, or {@code 0} when there were no windowed attempts. */
    public double windowedFailureRate() {
        return windowedAttempts == 0 ? 0.0d : (double) windowedFailures / windowedAttempts;
    }

    /** A short, human-readable explanation of why the alert fired (or that it did not). */
    public String reason() {
        if (!shouldAlert()) {
            return "正常：未触发写回失败告警阈值";
        }
        StringBuilder sb = new StringBuilder();
        if (failureRateBreached) {
            sb.append(String.format("窗口内写回失败率 %.0f%%（%d/%d）达到告警阈值",
                    windowedFailureRate() * 100, windowedFailures, windowedAttempts));
        }
        if (consecutiveFailuresBreached) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(String.format("连续写回失败 %d 次达到告警阈值", trailingConsecutiveFailures));
        }
        return sb.toString();
    }
}
