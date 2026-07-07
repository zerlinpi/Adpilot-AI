package com.adpilot.modules.advertising.vo;

import java.time.LocalDateTime;

/**
 * Read-only view of the Amazon Ads report-sync observability state for a single
 * store and report type (Req 2.5, 2.6).
 *
 * <p>Derived from {@code report_sync_runs} / {@code report_sync_errors}: it
 * surfaces the most recent run's {@code reportStatus} ({@code running} /
 * {@code completed} / {@code failed}), the most recent successful completion
 * time ({@code lastSuccessAt}), and a human-readable failure reason
 * ({@code lastError}) when the most recent run failed.</p>
 *
 * <p>A failed sync is never mapped to a success value: {@code reportStatus} is
 * passed through verbatim from the persisted run and a failed run always carries
 * a non-empty, readable {@code lastError} rather than a blank or fabricated
 * success (Req 2.6).</p>
 *
 * @param storeId      the store the status belongs to
 * @param reportType   the Amazon Ads report type (e.g. {@code SP_CAMPAIGN})
 * @param reportStatus the most recent run status verbatim ({@code running} /
 *                     {@code completed} / {@code failed})
 * @param lastSuccessAt the completion time of the most recent successful run,
 *                      or {@code null} when no run has ever succeeded
 * @param lastError    a readable failure reason when the most recent run failed,
 *                      otherwise {@code null}
 */
public record ProductAdSyncStatusVo(
        String storeId,
        String reportType,
        String reportStatus,
        LocalDateTime lastSuccessAt,
        String lastError) {
}
