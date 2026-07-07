package com.adpilot.modules.advertising.hosting;

import java.util.List;

/**
 * Service interface for Decision Drift Monitoring (Requirement 35.5).
 *
 * <p>Monitors sustained shifts in decision volume, average risk score, or rejection
 * rate. When drift exceeds configured thresholds, alerts are raised.</p>
 *
 * <p>Drift detection compares recent windows (e.g., last 24h) against a baseline
 * (e.g., prior 7-day average) for key metrics:</p>
 * <ul>
 *   <li>Decision volume per store/campaign</li>
 *   <li>Average risk score</li>
 *   <li>Rejection rate (percentage of decisions rejected at approval)</li>
 *   <li>Failure rate (percentage of submitted Operations that fail)</li>
 * </ul>
 *
 * <p>Validates: Requirements 35.5.</p>
 */
public interface DriftMonitor {

    /**
     * Represents a single drift detection result.
     *
     * @param metric        the metric name (e.g., "decision_volume", "avg_risk_score")
     * @param currentValue  the current period's measured value
     * @param baselineValue the baseline (historical) value
     * @param driftPercent  the percentage drift from baseline
     * @param threshold     the configured drift threshold (percentage)
     * @param breached      whether the drift exceeds the threshold
     */
    record DriftResult(
            String metric,
            double currentValue,
            double baselineValue,
            double driftPercent,
            double threshold,
            boolean breached
    ) {}

    /**
     * Evaluate drift across all monitored metrics for a store.
     *
     * @param storeId the store to evaluate (null for system-wide)
     * @return list of drift results, including non-breached metrics
     */
    List<DriftResult> evaluateDrift(java.util.UUID storeId);

    /**
     * Check all stores for drift and raise alerts for breaches.
     * Typically invoked on a schedule.
     */
    void checkAndAlert();
}
