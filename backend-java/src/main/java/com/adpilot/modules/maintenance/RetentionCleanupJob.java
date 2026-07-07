package com.adpilot.modules.maintenance;

import com.adpilot.modules.advertising.hosting.NotificationDeliveryLogMapper;
import com.adpilot.modules.advertising.hosting.ReportSyncErrorMapper;
import com.adpilot.modules.audit.mapper.AiModelCallLogMapper;
import com.adpilot.modules.audit.mapper.AuditLogMapper;
import com.adpilot.modules.feishu.mapper.FeishuMessageLogMapper;
import com.adpilot.modules.user.mapper.LoginLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Scheduled retention sweeper for high-write log / ledger tables (reliability fix M4).
 *
 * <p>Only {@code optimization_runs} had a scheduled retention cleanup previously
 * (see {@code OptimizationRunServiceImpl#scheduledRetentionCleanup}); these other
 * high-write tables grew unbounded — worst during a retrying outage. This job
 * mirrors that pattern: one daily {@code @Scheduled} method deletes rows older than
 * a configurable age from each table using each table's {@code created_at} column.</p>
 *
 * <p>Retention is driven by two knobs with safe defaults:</p>
 * <ul>
 *   <li>{@code adpilot.retention.operational-log-days} (default 90) — applies to
 *       {@code report_sync_errors}, {@code notification_delivery_log},
 *       {@code ai_model_call_logs}, {@code feishu_message_logs};</li>
 *   <li>{@code adpilot.retention.audit-log-days} (default 365, longer because it is
 *       compliance-sensitive) — applies to {@code audit_logs}, {@code login_logs}.</li>
 * </ul>
 *
 * <p>A retention of {@code 0} or negative means "keep forever" — that table is
 * skipped entirely (never deleted). Deletes run in bounded batches
 * ({@code adpilot.retention.batch-size}, default 1000) so a large backlog never
 * locks a table with one huge statement. Each table is swept inside its own
 * try/catch so one table's failure does not abort the others, and the job runs on
 * the shared bounded scheduler pool so it never blocks other scheduled work.</p>
 */
@Component
public class RetentionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupJob.class);

    /** Retention for operational logs (days). {@code <= 0} disables cleanup (keep forever). */
    @Value("${adpilot.retention.operational-log-days:90}")
    private int operationalLogDays;

    /** Retention for audit/compliance logs (days). {@code <= 0} disables cleanup (keep forever). */
    @Value("${adpilot.retention.audit-log-days:365}")
    private int auditLogDays;

    /** Maximum rows deleted per DELETE statement, looped until the backlog is drained. */
    @Value("${adpilot.retention.batch-size:1000}")
    private int batchSize;

    private final ReportSyncErrorMapper reportSyncErrorMapper;
    private final NotificationDeliveryLogMapper notificationDeliveryLogMapper;
    private final AiModelCallLogMapper aiModelCallLogMapper;
    private final FeishuMessageLogMapper feishuMessageLogMapper;
    private final AuditLogMapper auditLogMapper;
    private final LoginLogMapper loginLogMapper;

    public RetentionCleanupJob(ReportSyncErrorMapper reportSyncErrorMapper,
                               NotificationDeliveryLogMapper notificationDeliveryLogMapper,
                               AiModelCallLogMapper aiModelCallLogMapper,
                               FeishuMessageLogMapper feishuMessageLogMapper,
                               AuditLogMapper auditLogMapper,
                               LoginLogMapper loginLogMapper) {
        this.reportSyncErrorMapper = reportSyncErrorMapper;
        this.notificationDeliveryLogMapper = notificationDeliveryLogMapper;
        this.aiModelCallLogMapper = aiModelCallLogMapper;
        this.feishuMessageLogMapper = feishuMessageLogMapper;
        this.auditLogMapper = auditLogMapper;
        this.loginLogMapper = loginLogMapper;
    }

    /**
     * A bounded, batched delete of rows older than {@code retentionDays} for a
     * single table. Extracted so it can be unit-tested per table.
     */
    @FunctionalInterface
    interface BatchDelete {
        /** Delete up to {@code batchSize} rows older than {@code cutoff}; return rows deleted. */
        int deleteOlderThan(LocalDateTime cutoff, int batchSize);
    }

    /**
     * Daily retention sweep across all managed tables (reliability fix M4). Runs at
     * 03:30 — mirroring the {@code optimization_runs} cleanup cron (03:00) while
     * avoiding overlap. Cadence is config-driven and each table is fault-isolated.
     */
    @Scheduled(cron = "${adpilot.retention.cleanup-cron:0 30 3 * * ?}")
    public void scheduledCleanup() {
        int effectiveBatch = Math.max(1, batchSize);

        cleanupTable("report_sync_errors", operationalLogDays, effectiveBatch,
                reportSyncErrorMapper::deleteOlderThan);
        cleanupTable("notification_delivery_log", operationalLogDays, effectiveBatch,
                notificationDeliveryLogMapper::deleteOlderThan);
        cleanupTable("ai_model_call_logs", operationalLogDays, effectiveBatch,
                aiModelCallLogMapper::deleteOlderThan);
        cleanupTable("feishu_message_logs", operationalLogDays, effectiveBatch,
                feishuMessageLogMapper::deleteOlderThan);
        cleanupTable("audit_logs", auditLogDays, effectiveBatch,
                auditLogMapper::deleteOlderThan);
        cleanupTable("login_logs", auditLogDays, effectiveBatch,
                loginLogMapper::deleteOlderThan);
    }

    /**
     * Sweep one table: skip when retention is disabled ({@code <= 0}), otherwise
     * delete rows older than the cutoff in bounded batches until fewer than
     * {@code batchSize} rows come back. Wrapped in try/catch so one table's failure
     * never aborts the rest of the sweep.
     *
     * @return total rows deleted (0 when skipped or on error)
     */
    int cleanupTable(String table, int retentionDays, int batchSize, BatchDelete delete) {
        if (retentionDays <= 0) {
            log.debug("Retention cleanup skipped for {} (retention disabled: {} days)",
                    table, retentionDays);
            return 0;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int totalDeleted = 0;
        try {
            int deleted;
            do {
                deleted = delete.deleteOlderThan(cutoff, batchSize);
                totalDeleted += deleted;
            } while (deleted >= batchSize);

            if (totalDeleted > 0) {
                log.info("Retention cleanup: deleted {} {} rows older than {} days",
                        totalDeleted, table, retentionDays);
            }
        } catch (Exception e) {
            log.error("Retention cleanup failed for {} (deleted {} before failure)",
                    table, totalDeleted, e);
        }
        return totalDeleted;
    }
}
