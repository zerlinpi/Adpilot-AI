package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.feishu.service.FeishuService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.UUID;

/**
 * Implementation of the {@link DataQualityGate} (Requirements 3.1–3.6, 6.6, 25.5, 30.2).
 *
 * <p>Performs two checks in order:</p>
 * <ol>
 *   <li><b>Freshness</b>: verifies the latest performance data for the campaign
 *       exists within a configurable window (default 48 hours). Uses the most recent
 *       {@code report_date} in {@code performance_daily} for the campaign's store.</li>
 *   <li><b>Completeness</b>: verifies that finalized report coverage (from
 *       {@code report_sync_runs}) covers the required lookback range. Judged by
 *       finalized run coverage, NOT by counting non-empty rows (Req 3.2).</li>
 * </ol>
 *
 * <p>Operates <b>fail-closed</b>: any DB error or missing coverage info results
 * in a refusal to optimize (Req 3.5).</p>
 *
 * <p>Rejected campaigns are batched into a queue for the next Feishu digest
 * notification (Req 3.6).</p>
 */
@Service
public class DataQualityGateImpl implements DataQualityGate {

    private static final Logger log = LoggerFactory.getLogger(DataQualityGateImpl.class);

    /** Default freshness window in hours. */
    private static final long DEFAULT_FRESHNESS_HOURS = 48;

    /** Default lookback in days for finalized coverage completeness. */
    private static final int DEFAULT_LOOKBACK_DAYS = 7;

    private final PerformanceDailyMapper performanceDailyMapper;
    private final ReportSyncRunMapper reportSyncRunMapper;
    private final FeishuService feishuService;

    /** Queue of rejected campaigns pending Feishu digest notification. */
    private final ConcurrentLinkedQueue<RejectedCampaign> rejectedCampaignQueue = new ConcurrentLinkedQueue<>();

    @Value("${hosting.data-quality.freshness-hours:" + DEFAULT_FRESHNESS_HOURS + "}")
    private long freshnessHours = DEFAULT_FRESHNESS_HOURS;

    @Value("${hosting.data-quality.lookback-days:" + DEFAULT_LOOKBACK_DAYS + "}")
    private int lookbackDays = DEFAULT_LOOKBACK_DAYS;

    public DataQualityGateImpl(PerformanceDailyMapper performanceDailyMapper,
                               ReportSyncRunMapper reportSyncRunMapper,
                               FeishuService feishuService) {
        this.performanceDailyMapper = performanceDailyMapper;
        this.reportSyncRunMapper = reportSyncRunMapper;
        this.feishuService = feishuService;
    }

    @Override
    public DataQualityResult check(UUID storeId, UUID campaignId) {
        return check(storeId, campaignId, freshnessHours, lookbackDays);
    }

    @Override
    public DataQualityResult check(UUID storeId, UUID campaignId, long freshnessHours, int lookbackDays) {
        try {
            // 1. Freshness check (Req 3.1): latest performance data must be within window
            DataQualityResult freshnessResult = checkFreshness(storeId, campaignId, freshnessHours);
            if (!freshnessResult.passed()) {
                queueRejection(storeId, campaignId, freshnessResult.reason());
                return freshnessResult;
            }

            // 2. Completeness check (Req 3.2): finalized coverage from report_sync_runs
            DataQualityResult completenessResult = checkCompleteness(storeId, lookbackDays);
            if (!completenessResult.passed()) {
                queueRejection(storeId, campaignId, completenessResult.reason());
                return completenessResult;
            }

            return DataQualityResult.pass();
        } catch (Exception e) {
            // Fail-closed (Req 3.5): on any error, refuse to optimize
            log.error("Data quality gate failed-closed for store={}, campaign={}: {}",
                    storeId, campaignId, e.getMessage(), e);
            DataQualityResult result = DataQualityResult.stale();
            queueRejection(storeId, campaignId, "FAIL_CLOSED: " + e.getMessage());
            return result;
        }
    }

    /**
     * Freshness check: the most recent report_date for this campaign's store
     * must fall within the freshness window from now.
     */
    private DataQualityResult checkFreshness(UUID storeId, UUID campaignId, long freshnessHours) {
        // Query the latest report_date for performance data of the campaign in this store
        LambdaQueryWrapper<PerformanceDailyEntity> query = new LambdaQueryWrapper<>();
        query.eq(PerformanceDailyEntity::getStoreId, storeId)
             .eq(PerformanceDailyEntity::getCampaignId, campaignId)
             .orderByDesc(PerformanceDailyEntity::getDate)
             .last("LIMIT 1");

        List<PerformanceDailyEntity> latest = performanceDailyMapper.selectList(query);

        if (latest.isEmpty()) {
            // No data at all — fail-closed (Req 3.5)
            log.warn("No performance data found for store={}, campaign={} — DATA_STALE",
                    storeId, campaignId);
            return DataQualityResult.stale();
        }

        LocalDate latestDate = latest.get(0).getDate();
        // The freshness threshold: the latest data date must be no older than
        // freshnessHours from now. We convert hours to days for date comparison:
        // a 48-hour window means the latest date must be at most 2 days old.
        LocalDate freshnessThreshold = LocalDate.now().minusDays(freshnessHours / 24);

        if (latestDate.isBefore(freshnessThreshold)) {
            log.info("Data stale for store={}, campaign={}: latest={}, threshold={}",
                    storeId, campaignId, latestDate, freshnessThreshold);
            return DataQualityResult.stale();
        }

        return DataQualityResult.pass();
    }

    /**
     * Completeness check: judged against {@code report_sync_runs} finalized coverage
     * for the required lookback range (Req 3.2).
     *
     * <p>A date with zero impressions may legitimately have no performance row, so
     * completeness is judged by whether finalized report sync runs cover the required
     * lookback range — NOT by counting non-empty performance rows.</p>
     */
    private DataQualityResult checkCompleteness(UUID storeId, int lookbackDays) {
        LocalDate today = LocalDate.now();
        LocalDate lookbackStart = today.minusDays(lookbackDays);

        // Query completed + finalized report sync runs for this store covering
        // dates within the required lookback window
        LambdaQueryWrapper<ReportSyncRunEntity> query = new LambdaQueryWrapper<>();
        query.eq(ReportSyncRunEntity::getStoreId, storeId)
             .eq(ReportSyncRunEntity::getReportStatus, "completed")
             .eq(ReportSyncRunEntity::getDataStatus, "finalized")
             .le(ReportSyncRunEntity::getRequestedDateStart, today)
             .ge(ReportSyncRunEntity::getRequestedDateEnd, lookbackStart);

        List<ReportSyncRunEntity> runs = reportSyncRunMapper.selectList(query);

        if (runs.isEmpty()) {
            // No finalized coverage at all — fail-closed (Req 3.5)
            log.warn("No finalized report coverage found for store={} in lookback [{}, {}]",
                    storeId, lookbackStart, today);
            return DataQualityResult.incomplete();
        }

        // Verify the finalized runs collectively cover the required lookback range.
        // Build a set of covered dates from all completed finalized runs.
        Set<LocalDate> coveredDates = new HashSet<>();
        for (ReportSyncRunEntity run : runs) {
            LocalDate start = run.getRequestedDateStart().isBefore(lookbackStart)
                    ? lookbackStart : run.getRequestedDateStart();
            LocalDate end = run.getRequestedDateEnd().isAfter(today)
                    ? today : run.getRequestedDateEnd();
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                coveredDates.add(d);
            }
        }

        // Check that every date in the lookback range is covered
        for (LocalDate d = lookbackStart; !d.isAfter(today); d = d.plusDays(1)) {
            if (!coveredDates.contains(d)) {
                log.info("Incomplete finalized coverage for store={}: missing date {}",
                        storeId, d);
                return DataQualityResult.incomplete();
            }
        }

        return DataQualityResult.pass();
    }

    /**
     * Queue a rejected campaign for the next Feishu notification digest (Req 3.6).
     */
    private void queueRejection(UUID storeId, UUID campaignId, String reason) {
        rejectedCampaignQueue.add(new RejectedCampaign(storeId, campaignId, reason, LocalDateTime.now()));
        log.debug("Queued DQ rejection for Feishu digest: store={}, campaign={}, reason={}",
                storeId, campaignId, reason);
    }

    /**
     * Drain all queued rejections and send them as a batch Feishu notification.
     * Called by the notification digest worker or at the end of an optimization run.
     */
    public void flushRejectionNotifications() {
        List<RejectedCampaign> batch = new ArrayList<>();
        RejectedCampaign item;
        while ((item = rejectedCampaignQueue.poll()) != null) {
            batch.add(item);
        }

        if (batch.isEmpty()) {
            return;
        }

        // Group by store for per-store notifications
        Map<UUID, List<RejectedCampaign>> byStore = new LinkedHashMap<>();
        for (RejectedCampaign rc : batch) {
            byStore.computeIfAbsent(rc.storeId(), k -> new ArrayList<>()).add(rc);
        }

        for (Map.Entry<UUID, List<RejectedCampaign>> entry : byStore.entrySet()) {
            UUID storeId = entry.getKey();
            List<RejectedCampaign> rejections = entry.getValue();

            StringBuilder message = new StringBuilder();
            message.append("Data Quality Gate rejected ").append(rejections.size())
                   .append(" campaign(s) from optimization:\n");
            for (RejectedCampaign rc : rejections) {
                message.append("• Campaign ").append(rc.campaignId())
                       .append(" — ").append(rc.reason()).append("\n");
            }

            try {
                feishuService.pushAiNotification(storeId,
                        "⚠️ Data Quality Gate Rejections",
                        message.toString());
            } catch (Exception e) {
                // Feishu unavailable — queue without blocking (Req 30.4)
                log.warn("Failed to send DQ rejection notification for store={}: {}",
                        storeId, e.getMessage());
            }
        }
    }

    /**
     * Returns the current count of queued rejection notifications (for testing/monitoring).
     */
    public int pendingRejectionCount() {
        return rejectedCampaignQueue.size();
    }

    /**
     * Internal record for a rejected campaign pending notification.
     */
    record RejectedCampaign(UUID storeId, UUID campaignId, String reason, LocalDateTime rejectedAt) {}
}
