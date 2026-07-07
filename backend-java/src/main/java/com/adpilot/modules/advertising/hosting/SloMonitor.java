package com.adpilot.modules.advertising.hosting;

import java.util.Map;

/**
 * Service interface for SLO (Service Level Objective) monitoring (Requirement 35.4).
 *
 * <p>Defines and monitors SLOs for the AI Hosting System:</p>
 * <ul>
 *   <li>Report sync freshness — data must be no older than the configured window.</li>
 *   <li>Submission success rate — percentage of Operations that reach {@code effective}
 *       vs. {@code failed}.</li>
 *   <li>Verification latency — time from submitted to effective/failed.</li>
 *   <li>End-to-end latency — time from decision creation to effective.</li>
 * </ul>
 *
 * <p>When SLOs are breached, alerts are raised via the notification service.</p>
 *
 * <p>Validates: Requirements 35.4.</p>
 */
public interface SloMonitor {

    /**
     * Represents the health status of an SLO.
     */
    enum SloStatus {
        /** SLO monitoring is not enabled for this deployment. */
        DISABLED,
        /** SLO is within acceptable bounds. */
        HEALTHY,
        /** SLO is approaching its threshold (warning). */
        WARNING,
        /** SLO is breached. */
        BREACHED
    }

    /**
     * Represents a single SLO measurement result.
     *
     * @param name      the SLO name (e.g., "report_sync_freshness")
     * @param status    the current status
     * @param value     the measured value
     * @param threshold the configured threshold
     * @param unit      the measurement unit (e.g., "hours", "percent", "ms")
     */
    record SloResult(String name, SloStatus status, double value, double threshold, String unit) {}

    /**
     * Evaluate all configured SLOs and return their current status.
     *
     * @return a map of SLO name to its current measurement result
     */
    Map<String, SloResult> evaluateAll();

    /**
     * Evaluate a specific SLO by name.
     *
     * @param sloName the SLO to evaluate
     * @return the measurement result, or null if the SLO is not recognized
     */
    SloResult evaluate(String sloName);

    /**
     * Check all SLOs and raise alerts for any breaches.
     * Typically invoked on a schedule.
     */
    void checkAndAlert();
}
