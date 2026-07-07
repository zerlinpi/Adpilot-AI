package com.adpilot.modules.advertising.hosting;

import java.util.UUID;

/**
 * Pre-optimization data quality gate that verifies freshness and completeness
 * of performance data before allowing the optimization engine to run for a
 * campaign (Requirements 3.1–3.6, 6.6, 25.5, 30.2).
 *
 * <p>The gate is invoked (a) before an engine evaluates a campaign and (b)
 * immediately before an Operation is submitted (Req 3.7, 33.2).</p>
 *
 * <p>Behavior contract:</p>
 * <ul>
 *   <li><b>Freshness</b>: data must exist within the configured window (default 48h).</li>
 *   <li><b>Completeness</b>: judged against {@code report_sync_runs} finalized coverage
 *       for the required lookback — NOT by counting non-empty performance rows (Req 3.2).</li>
 *   <li><b>Fail-closed</b>: on DB error or missing coverage info, refuse to optimize (Req 3.5).</li>
 *   <li><b>Notification</b>: rejected campaigns are batched into the next Feishu digest (Req 3.6).</li>
 * </ul>
 */
public interface DataQualityGate {

    /**
     * Check whether a campaign's data meets quality requirements for optimization.
     *
     * @param storeId    the store that owns the campaign
     * @param campaignId the campaign to check
     * @return a {@link DataQualityResult} indicating pass/fail with reason
     */
    DataQualityResult check(UUID storeId, UUID campaignId);

    /**
     * Check data quality with an explicit freshness window and lookback days.
     *
     * @param storeId           the store that owns the campaign
     * @param campaignId        the campaign to check
     * @param freshnessHours    the maximum allowed age of the most recent data (hours)
     * @param lookbackDays      the number of days of finalized coverage required
     * @return a {@link DataQualityResult} indicating pass/fail with reason
     */
    DataQualityResult check(UUID storeId, UUID campaignId, long freshnessHours, int lookbackDays);
}
