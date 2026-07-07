package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.feishu.service.FeishuService;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.MarketplaceReferenceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.adpilot.common.resilience.CircuitBreaker;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.common.utils.RetryBackoff;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Scheduled job that orchestrates the three Amazon Ads report sync cadences
 * (Requirements 2.1, 2.6, 2.7, 2.8):
 *
 * <ol>
 *   <li><b>Intra-day incremental</b> (default hourly): fetches current day's
 *       preliminary data for near-real-time dashboard visibility.</li>
 *   <li><b>Daily finalization</b> (default 06:00 marketplace timezone): fetches
 *       settled data for the day that just finalized, promotes rows from
 *       {@code preliminary} to {@code finalized}.</li>
 *   <li><b>Rolling backfill</b> (7–14 days): absorbs Amazon attribution lag by
 *       re-fetching historical data and bumping {@code data_version} on changes.</li>
 * </ol>
 *
 * <p>Each run is persisted in {@code report_sync_runs}. Gap detection compares
 * finalized coverage against {@code expectedFinalizedDate = today − finalizationLagDays}.
 * When a &gt;2-day finalized gap is detected, a Feishu data-gap notification is sent.</p>
 *
 * <p>Each report lifecycle is retried up to 3 times with exponential backoff;
 * failures are recorded in {@code report_sync_errors}.</p>
 *
 * <p>Validates: Requirements 2.1, 2.6, 2.7, 2.8.</p>
 */
@Slf4j
@Component
public class ReportSyncJob {

    private static final String PLATFORM = "amazon_ads";
    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 2000L;

    private final ReportLifecycleClient reportLifecycleClient;
    private final ReportIngestionService reportIngestionService;
    private final ReportSyncRunMapper reportSyncRunMapper;
    private final ReportSyncErrorMapper reportSyncErrorMapper;
    private final PlatformConnectionMapper connectionMapper;
    private final StoreMapper storeMapper;
    private final MarketplaceReferenceService marketplaceReferenceService;
    private final FeishuService feishuService;
    private final CryptoUtil cryptoUtil;
    private final CircuitBreaker circuitBreaker;

    /** Whether report sync is enabled. */
    @Value("${adpilot.hosting.report-sync.enabled:true}")
    private boolean enabled;

    /** Finalization lag in days — data older than this is considered finalized. */
    @Value("${adpilot.hosting.report-sync.finalization-lag-days:3}")
    private int finalizationLagDays;

    /** Rolling backfill start days (inclusive, counting from today). */
    @Value("${adpilot.hosting.report-sync.backfill-start-days:14}")
    private int backfillStartDays;

    /** Rolling backfill end days (inclusive, counting from today). */
    @Value("${adpilot.hosting.report-sync.backfill-end-days:7}")
    private int backfillEndDays;

    /** Hour of day (in marketplace tz) to run the daily finalization cadence. */
    @Value("${adpilot.hosting.report-sync.daily-hour:6}")
    private int dailyHour;

    /** Maximum tolerable finalized gap in days before alerting. */
    @Value("${adpilot.hosting.report-sync.max-gap-days:2}")
    private int maxGapDays;

    /**
     * Maximum number of stores processed in parallel within a single cadence tick.
     * The pool is floored at 1 (never unbounded) so the scheduler thread is freed
     * while per-store work (including retry backoff sleeps) runs on worker threads.
     */
    @Value("${adpilot.hosting.report-sync.max-parallel-stores:4}")
    private int maxParallelStores;

    /** Bounded worker pool for per-store sync work. Built in {@link #init()}. */
    private ExecutorService syncExecutor;

    public ReportSyncJob(ReportLifecycleClient reportLifecycleClient,
                         ReportIngestionService reportIngestionService,
                         ReportSyncRunMapper reportSyncRunMapper,
                         ReportSyncErrorMapper reportSyncErrorMapper,
                         PlatformConnectionMapper connectionMapper,
                         StoreMapper storeMapper,
                         MarketplaceReferenceService marketplaceReferenceService,
                         FeishuService feishuService,
                         CryptoUtil cryptoUtil,
                         CircuitBreaker circuitBreaker) {
        this.reportLifecycleClient = reportLifecycleClient;
        this.reportIngestionService = reportIngestionService;
        this.reportSyncRunMapper = reportSyncRunMapper;
        this.reportSyncErrorMapper = reportSyncErrorMapper;
        this.connectionMapper = connectionMapper;
        this.storeMapper = storeMapper;
        this.marketplaceReferenceService = marketplaceReferenceService;
        this.feishuService = feishuService;
        this.cryptoUtil = cryptoUtil;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * Build the bounded worker pool after Spring has injected the {@code @Value}
     * fields. The pool size is read from
     * {@code adpilot.hosting.report-sync.max-parallel-stores} and floored at 1,
     * so the fleet is processed with bounded parallelism (never unbounded).
     */
    @PostConstruct
    void init() {
        int poolSize = Math.max(1, maxParallelStores);
        this.syncExecutor = Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread t = new Thread(runnable, "report-sync");
            t.setDaemon(true);
            return t;
        });
        log.debug("ReportSyncJob worker pool initialized with {} thread(s)", poolSize);
    }

    /** Shut the worker pool down on context teardown. */
    @PreDestroy
    void shutdown() {
        if (syncExecutor != null) {
            syncExecutor.shutdown();
        }
    }

    /**
     * Submit one task per store to the bounded worker pool and block until the
     * whole tick's work is done, so the {@code @Scheduled} trigger thread is not
     * held during per-store retry backoff sleeps. Per-store exceptions are
     * isolated inside {@code perStore}; this method only drains the futures.
     */
    private void runAcrossStores(List<UUID> storeIds, Consumer<UUID> perStore) {
        List<Future<?>> futures = new ArrayList<>(storeIds.size());
        for (UUID storeId : storeIds) {
            futures.add(syncExecutor.submit(() -> perStore.accept(storeId)));
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                log.warn("Report sync store task failed: {}",
                        cause != null ? cause.getMessage() : e.getMessage());
            }
        }
    }

    // ─── Cadence 1: Intra-day incremental (hourly) ──────────────────────

    /**
     * Intra-day incremental sync: fetches current day's preliminary data hourly.
     * Provides near-real-time dashboard data without waiting for finalization.
     */
    @Scheduled(fixedDelayString = "${adpilot.hosting.report-sync.intraday-interval-ms:3600000}")
    public void intradaySync() {
        if (!enabled) {
            log.debug("Report sync is disabled");
            return;
        }

        List<UUID> storeIds = findStoresWithAmazonAdsConnection();
        if (storeIds.isEmpty()) {
            log.debug("No stores with active Amazon Ads connections for intra-day sync");
            return;
        }

        runAcrossStores(storeIds, this::intradaySyncStore);
    }

    /** Per-store intra-day work; runs on a worker thread with exception isolation. */
    private void intradaySyncStore(UUID storeId) {
        try {
            ConnectionContext ctx = buildConnectionContext(storeId);
            ZoneId marketplaceZone = resolveMarketplaceTimezone(storeId);
            LocalDate today = LocalDate.now(marketplaceZone);
            ReportDateRange range = new ReportDateRange(today, today);

            for (ReportType reportType : ReportType.values()) {
                executeWithRetry(storeId, ctx, reportType, range, "preliminary", "intraday");
            }
        } catch (Exception e) {
            log.warn("Intra-day sync failed for store {}: {}", storeId, e.getMessage());
        }
    }

    // ─── Cadence 2: Daily finalization (06:00 marketplace tz) ───────────

    /**
     * Daily finalization sync: runs at a fixed rate and checks whether the current
     * marketplace-local hour matches the configured daily hour. When it does, it
     * fetches the day that just finalized and promotes data to {@code finalized}.
     *
     * <p>Also performs gap detection after the finalization run (Req 2.8).</p>
     */
    @Scheduled(fixedDelayString = "${adpilot.hosting.report-sync.daily-check-interval-ms:3600000}")
    public void dailyFinalizationSync() {
        if (!enabled) {
            return;
        }

        List<UUID> storeIds = findStoresWithAmazonAdsConnection();
        runAcrossStores(storeIds, this::dailyFinalizationSyncStore);
    }

    /** Per-store daily finalization work; runs on a worker thread with exception isolation. */
    private void dailyFinalizationSyncStore(UUID storeId) {
        try {
            ConnectionContext ctx = buildConnectionContext(storeId);
            ZoneId marketplaceZone = resolveMarketplaceTimezone(storeId);
            int currentHour = LocalTime.now(marketplaceZone).getHour();

            if (currentHour != dailyHour) {
                return;
            }

            LocalDate today = LocalDate.now(marketplaceZone);
            // Finalization promotion: the day that is now finalized
            LocalDate finalizedDate = today.minusDays(finalizationLagDays);
            ReportDateRange range = new ReportDateRange(finalizedDate, finalizedDate);

            for (ReportType reportType : ReportType.values()) {
                executeWithRetry(storeId, ctx, reportType, range, "finalized", "daily");
            }

            // After finalization run, check for gaps
            detectAndAlertGaps(storeId, today, marketplaceZone);

        } catch (Exception e) {
            log.warn("Daily finalization sync failed for store {}: {}", storeId, e.getMessage());
        }
    }

    // ─── Cadence 3: Rolling backfill (7–14 days) ────────────────────────

    /**
     * Rolling backfill sync: re-fetches historical data in the 7–14 day window
     * to absorb Amazon attribution lag. Changed rows have their {@code data_version}
     * incremented by the ingestion service.
     */
    @Scheduled(fixedDelayString = "${adpilot.hosting.report-sync.backfill-interval-ms:86400000}")
    public void rollingBackfillSync() {
        if (!enabled) {
            return;
        }

        List<UUID> storeIds = findStoresWithAmazonAdsConnection();
        runAcrossStores(storeIds, this::rollingBackfillSyncStore);
    }

    /** Per-store rolling backfill work; runs on a worker thread with exception isolation. */
    private void rollingBackfillSyncStore(UUID storeId) {
        try {
            ConnectionContext ctx = buildConnectionContext(storeId);
            ZoneId marketplaceZone = resolveMarketplaceTimezone(storeId);
            LocalDate today = LocalDate.now(marketplaceZone);
            LocalDate backfillStart = today.minusDays(backfillStartDays);
            LocalDate backfillEnd = today.minusDays(backfillEndDays);
            ReportDateRange range = new ReportDateRange(backfillStart, backfillEnd);

            for (ReportType reportType : ReportType.values()) {
                executeWithRetry(storeId, ctx, reportType, range, "finalized", "backfill");
            }
        } catch (Exception e) {
            log.warn("Rolling backfill sync failed for store {}: {}", storeId, e.getMessage());
        }
    }

    // ─── Core execution with retry ──────────────────────────────────────

    /**
     * Execute a single report lifecycle for a store/type/range with up to
     * {@link #MAX_RETRIES} attempts and exponential backoff. Each attempt and
     * failure is recorded in the ledger tables.
     *
     * <p>The {@link ConnectionContext} is resolved once per store per tick by the
     * caller and passed in, rather than re-resolved per report type. When the
     * context is {@code null} (no active connection), the run is marked failed
     * exactly as before.</p>
     *
     * @param storeId    the store to sync
     * @param ctx        the pre-resolved connection context (may be {@code null})
     * @param reportType the report type
     * @param dateRange  the date range to fetch
     * @param dataStatus expected data status (preliminary/finalized)
     * @param cadence    the cadence label for logging
     */
    void executeWithRetry(UUID storeId, ConnectionContext ctx, ReportType reportType,
                          ReportDateRange dateRange, String dataStatus, String cadence) {
        // Create the run record
        ReportSyncRunEntity run = ReportSyncRunEntity.builder()
                .storeId(storeId)
                .reportType(reportType.name())
                .requestedDateStart(dateRange.startDate())
                .requestedDateEnd(dateRange.endDate())
                .reportStatus("running")
                .dataStatus(dataStatus)
                .startedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
        reportSyncRunMapper.insert(run);

        if (ctx == null) {
            markRunFailed(run, "No active Amazon Ads connection found");
            return;
        }

        // H3: when the Amazon Ads reporting dependency for this connection is known-down, skip the
        // lifecycle this tick and mark the run "skipped" (deferred) rather than driving the full
        // create/poll/download and fabricating a failure row every tick. It is retried on a later
        // cadence tick once the breaker half-opens. Per-store isolation is preserved by ReportSyncJob.
        String breakerKey = reportBreakerKey(ctx);
        if (!circuitBreaker.allow(breakerKey)) {
            log.debug("ReportSyncJob: circuit OPEN for {}, skipping {} report for store={} this tick",
                    breakerKey, reportType, storeId);
            markRunSkipped(run, "Circuit open: Amazon Ads reporting temporarily unavailable; deferred");
            return;
        }

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                ReportLifecycleResult result = executeLifecycleGuarded(
                        ctx, reportType, dateRange, breakerKey);

                // Ingest the report data
                IngestionResult ingestionResult = reportIngestionService.ingest(
                        storeId, result, finalizationLagDays);

                // Update the run record with success
                run.setReportStatus("completed");
                run.setRowCount(ingestionResult.totalProcessed());
                run.setAmazonReportId(result.reportId());
                run.setCompletedAt(LocalDateTime.now());
                run.setUpdatedAt(LocalDateTime.now());
                reportSyncRunMapper.updateById(run);

                log.info("Report sync completed: store={}, type={}, cadence={}, range=[{},{}], rows={}",
                        storeId, reportType, cadence, dateRange.startDate(), dateRange.endDate(),
                        ingestionResult.totalProcessed());
                return; // Success — exit retry loop

            } catch (Exception e) {
                // Record the failure
                ReportSyncErrorEntity error = ReportSyncErrorEntity.builder()
                        .runId(run.getId())
                        .storeId(storeId)
                        .attempt(attempt)
                        .error(truncateError(e.getMessage()))
                        .createdAt(LocalDateTime.now())
                        .build();
                reportSyncErrorMapper.insert(error);

                log.warn("Report sync attempt {}/{} failed for store={}, type={}, cadence={}: {}",
                        attempt, MAX_RETRIES, storeId, reportType, cadence, e.getMessage());

                if (attempt < MAX_RETRIES) {
                    // Exponential backoff (2s, 4s, 8s...) plus bounded jitter so many
                    // stores don't retry in lockstep when Amazon recovers (fix L2).
                    long backoffMs = RetryBackoff.withJitter(BASE_BACKOFF_MS, attempt);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        markRunFailed(run, "Interrupted during backoff: " + e.getMessage());
                        return;
                    }
                } else {
                    // All retries exhausted
                    markRunFailed(run, e.getMessage());
                }
            }
        }
    }

    // ─── Gap detection (Req 2.8) ────────────────────────────────────────

    /**
     * Detect finalized-coverage gaps and send a Feishu notification when a gap
     * exceeds {@link #maxGapDays} days.
     *
     * <p>The expected finalized date is {@code today - finalizationLagDays}. Gap
     * detection queries {@code report_sync_runs} for completed finalized runs and
     * computes the latest consecutive finalized date. If the gap between the latest
     * finalized coverage and the expected finalized date exceeds the threshold, a
     * Feishu data-gap notification is sent.</p>
     *
     * @param storeId         the store to check
     * @param today           today's date in marketplace timezone
     * @param marketplaceZone the marketplace timezone (for logging)
     */
    void detectAndAlertGaps(UUID storeId, LocalDate today, ZoneId marketplaceZone) {
        LocalDate expectedFinalizedDate = today.minusDays(finalizationLagDays);

        // Query the latest completed finalized run for this store
        LambdaQueryWrapper<ReportSyncRunEntity> query = new LambdaQueryWrapper<>();
        query.eq(ReportSyncRunEntity::getStoreId, storeId)
                .eq(ReportSyncRunEntity::getReportStatus, "completed")
                .eq(ReportSyncRunEntity::getDataStatus, "finalized")
                .orderByDesc(ReportSyncRunEntity::getRequestedDateEnd)
                .last("LIMIT 1");

        List<ReportSyncRunEntity> runs = reportSyncRunMapper.selectList(query);

        if (runs.isEmpty()) {
            // No finalized data at all — this is a gap from the beginning
            long gapDays = Period.between(LocalDate.EPOCH, expectedFinalizedDate).getDays();
            if (gapDays > maxGapDays) {
                sendDataGapNotification(storeId, null, expectedFinalizedDate);
            }
            return;
        }

        LocalDate latestFinalizedEnd = runs.get(0).getRequestedDateEnd();
        long gapDays = Period.between(latestFinalizedEnd, expectedFinalizedDate).getDays();

        if (gapDays > maxGapDays) {
            log.warn("Finalized data gap detected for store={}: latest={}, expected={}, gap={}d",
                    storeId, latestFinalizedEnd, expectedFinalizedDate, gapDays);
            sendDataGapNotification(storeId, latestFinalizedEnd, expectedFinalizedDate);
        } else {
            log.debug("Finalized coverage OK for store={}: latest={}, expected={}, gap={}d",
                    storeId, latestFinalizedEnd, expectedFinalizedDate, gapDays);
        }
    }

    // ─── Helper methods ─────────────────────────────────────────────────

    private void sendDataGapNotification(UUID storeId, LocalDate latestFinalized, LocalDate expectedFinalized) {
        String title = "⚠️ Report Data Gap Detected";
        String message = String.format(
                "Store %s has a finalized data gap.\nLatest finalized: %s\nExpected: %s\n"
                        + "Please investigate the report sync pipeline.",
                storeId,
                latestFinalized != null ? latestFinalized.toString() : "NONE",
                expectedFinalized.toString());

        try {
            feishuService.pushAiNotification(storeId, title, message);
            log.info("Feishu data-gap notification sent for store={}", storeId);
        } catch (Exception e) {
            log.warn("Failed to send Feishu data-gap notification for store={}: {}",
                    storeId, e.getMessage());
        }
    }

    private void markRunFailed(ReportSyncRunEntity run, String errorMessage) {
        run.setReportStatus("failed");
        run.setError(truncateError(errorMessage));
        run.setCompletedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        reportSyncRunMapper.updateById(run);
    }

    /**
     * Mark a run as skipped/deferred (H3): the run row is retained for observability but is NOT a
     * failure — the circuit was open, so the lifecycle was not attempted this tick. This avoids
     * fabricating a {@code failed} row (and its per-attempt error rows) on every tick while a
     * dependency is down.
     */
    private void markRunSkipped(ReportSyncRunEntity run, String reason) {
        run.setReportStatus("skipped");
        run.setError(truncateError(reason));
        run.setCompletedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        reportSyncRunMapper.updateById(run);
    }

    /**
     * Run one report lifecycle under the circuit breaker: a thrown failure is recorded against
     * {@code breakerKey} (a persistently-down reporting endpoint eventually opens the breaker) and
     * re-propagated to the retry/failure handling in {@link #executeWithRetry}; a successful
     * lifecycle records success, closing the breaker.
     */
    private ReportLifecycleResult executeLifecycleGuarded(ConnectionContext ctx, ReportType reportType,
                                                          ReportDateRange dateRange, String breakerKey) {
        try {
            ReportLifecycleResult result = reportLifecycleClient.executeLifecycle(ctx, reportType, dateRange);
            circuitBreaker.recordSuccess(breakerKey);
            return result;
        } catch (RuntimeException e) {
            circuitBreaker.recordFailure(breakerKey);
            throw e;
        }
    }

    /**
     * Circuit-breaker key for the Amazon Ads reporting dependency of a connection (H3). Keyed by
     * dependency + connectionId so a single down store connection opens the breaker only for its
     * own reporting calls, never for a healthy store's.
     */
    private static String reportBreakerKey(ConnectionContext ctx) {
        return PLATFORM + ":report:" + ctx.connectionId();
    }

    private ConnectionContext buildConnectionContext(UUID storeId) {
        LambdaQueryWrapper<PlatformConnectionEntity> query = new LambdaQueryWrapper<>();
        query.eq(PlatformConnectionEntity::getStoreId, storeId)
                .eq(PlatformConnectionEntity::getPlatform, PLATFORM)
                .eq(PlatformConnectionEntity::getStatus, ConnectionStatus.CONNECTED)
                .last("LIMIT 1");

        List<PlatformConnectionEntity> connections = connectionMapper.selectList(query);
        if (connections.isEmpty()) {
            return null;
        }

        PlatformConnectionEntity conn = connections.get(0);
        Map<String, String> credentials = new HashMap<>();
        if (conn.getRefreshTokenEncrypted() != null) {
            credentials.put("refresh_token", cryptoUtil.decrypt(conn.getRefreshTokenEncrypted()));
        }
        if (conn.getProfileId() != null) {
            credentials.put("profile_id", conn.getProfileId());
        }
        if (conn.getRegion() != null) {
            credentials.put("region", conn.getRegion());
        }

        return new ConnectionContext(conn.getId(), storeId, PLATFORM, credentials);
    }

    /**
     * Resolve the marketplace timezone for a store.
     * Falls back to UTC if the store or marketplace does not have a timezone configured.
     *
     * <p>The store lookup (to resolve the store's marketplace id) stays here; the
     * marketplace timezone lookup + UTC fallback is delegated to the shared,
     * cached {@link MarketplaceReferenceService} so the near-static marketplaces
     * table is not read per store per tick.</p>
     */
    ZoneId resolveMarketplaceTimezone(UUID storeId) {
        try {
            StoreEntity store = storeMapper.selectById(storeId);
            if (store == null || store.getMarketplaceId() == null) {
                return ZoneId.of("UTC");
            }
            return marketplaceReferenceService.timezoneForMarketplace(store.getMarketplaceId());
        } catch (Exception e) {
            log.warn("Failed to resolve marketplace timezone for store={}, defaulting to UTC: {}",
                    storeId, e.getMessage());
            return ZoneId.of("UTC");
        }
    }

    private List<UUID> findStoresWithAmazonAdsConnection() {
        LambdaQueryWrapper<PlatformConnectionEntity> query = new LambdaQueryWrapper<>();
        query.eq(PlatformConnectionEntity::getPlatform, PLATFORM)
                .eq(PlatformConnectionEntity::getStatus, ConnectionStatus.CONNECTED)
                .select(PlatformConnectionEntity::getStoreId);
        return connectionMapper.selectList(query).stream()
                .map(PlatformConnectionEntity::getStoreId)
                .distinct()
                .toList();
    }

    private static String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 1000 ? error.substring(0, 1000) : error;
    }
}
