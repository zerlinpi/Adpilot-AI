package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.connector.PlatformConnector;
import com.adpilot.modules.apisync.connector.PlatformDataConnector;
import com.adpilot.modules.apisync.connector.ReauthRequiredException;
import com.adpilot.modules.apisync.entity.ApiSyncJobEntity;
import com.adpilot.modules.apisync.entity.ApiSyncLogEntity;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.entity.SyncRecordErrorEntity;
import com.adpilot.modules.apisync.mapper.ApiSyncJobMapper;
import com.adpilot.modules.apisync.mapper.ApiSyncLogMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.mapper.SyncRecordErrorMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.FieldError;
import com.adpilot.modules.apisync.model.MappedRecord;
import com.adpilot.modules.apisync.model.PageCursor;
import com.adpilot.modules.apisync.model.SyncContext;
import com.adpilot.modules.apisync.model.ValidationResult;
import com.adpilot.modules.apisync.service.DataQualityValidator;
import com.adpilot.modules.apisync.service.IncrementalSelector;
import com.adpilot.modules.apisync.service.RecordMapper;
import com.adpilot.modules.apisync.service.SyncJobRunner;
import com.adpilot.modules.apisync.service.UpsertService;
import com.adpilot.modules.apisync.service.WatermarkStore;
import com.adpilot.modules.apisync.vo.ApiSyncJobVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default {@link SyncJobRunner}. Owns job lifecycle, single-flight enforcement,
 * and orchestration of the connector &rarr; mapper &rarr; validator &rarr;
 * upsert &rarr; watermark pipeline (Req 1.1, 1.3, 1.4).
 *
 * <p>The building blocks are reused as-is: the per-platform
 * {@link PlatformDataConnector} implementations pull normalized records, the
 * {@link RecordMapper} maps them to internal fields, the
 * {@link DataQualityValidator} excludes invalid records, the
 * {@link UpsertService} writes idempotently, and the {@link WatermarkStore}
 * tracks the incremental window. {@link PlatformConnector#test(ConnectionContext)}
 * validates credentials before any pull so rejected credentials fail the job
 * cleanly (Req 1.1.5).</p>
 *
 * <h2>Single-flight (Req 1.3.6)</h2>
 * <p>A start request reserves an in-memory key {@code storeId|entityType}
 * atomically and is rejected if the key is already held; the reservation is
 * released when execution finishes. A defensive database check additionally
 * rejects a start when a {@code running} job already exists for the same
 * {@code (store, entityType)}.</p>
 */
@Slf4j
@Service
public class SyncJobRunnerImpl implements SyncJobRunner {

    static final String STATUS_RUNNING = "running";
    static final String STATUS_COMPLETED = "completed";
    static final String STATUS_FAILED = "failed";
    static final String STATUS_CANCELLED = "cancelled";
    /** Connection status set when a platform rejects expired/invalid credentials (Req 8.1.5). */
    static final String CONNECTION_STATUS_REQUIRES_REAUTH = "requires_reauth";

    private final PlatformConnectionMapper platformConnectionMapper;
    private final ApiSyncJobMapper apiSyncJobMapper;
    private final ApiSyncLogMapper apiSyncLogMapper;
    private final SyncRecordErrorMapper syncRecordErrorMapper;
    private final RecordMapper recordMapper;
    private final DataQualityValidator dataQualityValidator;
    private final UpsertService upsertService;
    private final WatermarkStore watermarkStore;
    private final PlatformConnector platformConnector;
    private final CryptoUtil cryptoUtil;
    private final ObjectMapper objectMapper;

    /** platform key -> data connector, built from all injected connector beans. */
    private final Map<String, PlatformDataConnector> connectors = new ConcurrentHashMap<>();

    /** Reserved {@code storeId|entityType} keys with a job currently running. */
    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();

    /**
     * Runs the pipeline off the request thread so single-flight is observable.
     *
     * <p>Explicitly bounded (reliability fix M2): a fixed pool over an
     * <em>unbounded</em> {@code LinkedBlockingQueue} could grow without limit if
     * dispatch ever outran execution. Single-flight already bounds concurrency in
     * practice, but this makes the bound explicit — a bounded
     * {@link ArrayBlockingQueue} plus a {@link ThreadPoolExecutor.CallerRunsPolicy}
     * rejection policy. Only {@link #startSync} submits here, and it runs on a
     * request thread (never a scheduler thread), so CallerRuns safely degrades to
     * synchronous execution under saturation rather than dropping a job.</p>
     */
    private final ThreadPoolExecutor executor;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public SyncJobRunnerImpl(PlatformConnectionMapper platformConnectionMapper,
                             ApiSyncJobMapper apiSyncJobMapper,
                             ApiSyncLogMapper apiSyncLogMapper,
                             SyncRecordErrorMapper syncRecordErrorMapper,
                             RecordMapper recordMapper,
                             DataQualityValidator dataQualityValidator,
                             UpsertService upsertService,
                             WatermarkStore watermarkStore,
                             PlatformConnector platformConnector,
                             CryptoUtil cryptoUtil,
                             ObjectMapper objectMapper,
                             List<PlatformDataConnector> dataConnectors,
                             @Value("${adpilot.apisync.runner.pool-size:4}") int poolSize,
                             @Value("${adpilot.apisync.runner.queue-capacity:100}") int queueCapacity) {
        this.platformConnectionMapper = platformConnectionMapper;
        this.apiSyncJobMapper = apiSyncJobMapper;
        this.apiSyncLogMapper = apiSyncLogMapper;
        this.syncRecordErrorMapper = syncRecordErrorMapper;
        this.recordMapper = recordMapper;
        this.dataQualityValidator = dataQualityValidator;
        this.upsertService = upsertService;
        this.watermarkStore = watermarkStore;
        this.platformConnector = platformConnector;
        this.cryptoUtil = cryptoUtil;
        this.objectMapper = objectMapper;
        for (PlatformDataConnector connector : dataConnectors) {
            this.connectors.put(connector.platform(), connector);
        }
        this.executor = buildExecutor(poolSize, queueCapacity);
    }

    /**
     * Build the bounded pipeline executor (reliability fix M2). Pool size and
     * queue capacity are config-driven with safe defaults; both are floored at 1
     * so a misconfigured non-positive value can never disable the runner.
     */
    private static ThreadPoolExecutor buildExecutor(int poolSize, int queueCapacity) {
        int cappedPool = Math.max(1, poolSize);
        int cappedQueue = Math.max(1, queueCapacity);
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread t = new Thread(runnable, "apisync-runner-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return new ThreadPoolExecutor(
                cappedPool, cappedPool,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(cappedQueue),
                threadFactory,
                // Safe under saturation: the submitter (a request thread) runs the
                // task inline instead of the job being silently dropped.
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    @Override
    public ApiSyncJobVo startSync(UUID connectionId, String entityType, boolean fullResync, UUID triggeredBy) {
        if (connectionId == null) {
            throw new BusinessException("INVALID_REQUEST", "connectionId is required");
        }
        if (entityType == null || entityType.isBlank()) {
            throw new BusinessException("INVALID_REQUEST", "entityType is required");
        }

        PlatformConnectionEntity connection = platformConnectionMapper.selectById(connectionId);
        if (connection == null) {
            throw new BusinessException("CONNECTION_NOT_FOUND", "Platform connection not found: " + connectionId);
        }
        UUID storeId = connection.getStoreId();
        String key = singleFlightKey(storeId, entityType);

        // Req 1.3.6: at most one running job per (store, entityType); reject concurrent requests.
        // Only the thread whose putIfAbsent returns null actually acquires the slot. A losing
        // concurrent thread (putIfAbsent != null) must NOT touch inFlight, otherwise it would
        // remove the winner's reservation and let a second simultaneous job start.
        boolean acquired = inFlight.putIfAbsent(key, Boolean.TRUE) == null;
        if (!acquired || hasRunningJob(storeId, entityType)) {
            if (acquired) {
                // We acquired the in-memory slot but the defensive DB check found a running
                // job; release the marker WE set before rejecting.
                inFlight.remove(key, Boolean.TRUE);
            }
            throw new BusinessException(409, "SYNC_IN_PROGRESS",
                    "A sync is already running for this store and entity type: " + entityType);
        }

        boolean reserved = true;
        ApiSyncJobEntity job;
        try {
            // Req 1.3.1: record the job as running with its start time.
            job = ApiSyncJobEntity.builder()
                    .connectionId(connectionId)
                    .syncType(fullResync ? "full" : "incremental")
                    .entityType(entityType)
                    .status(STATUS_RUNNING)
                    .totalRecords(0)
                    .recordsProcessed(0)
                    .failedRecords(0)
                    .startedAt(LocalDateTime.now())
                    .createdBy(triggeredBy)
                    .build();
            apiSyncJobMapper.insert(job);

            UUID jobId = job.getId();
            // Dispatch the pipeline off the request thread; the async task owns release.
            executor.submit(() -> runGuarded(jobId, key));
            reserved = false;
        } finally {
            if (reserved) {
                inFlight.remove(key, Boolean.TRUE);
            }
        }

        log.info("Sync job started: id={}, store={}, entityType={}, fullResync={}",
                job.getId(), storeId, entityType, fullResync);
        return toJobVo(job);
    }

    @Override
    public void execute(UUID jobId) {
        if (jobId == null) {
            return;
        }
        ApiSyncJobEntity job = apiSyncJobMapper.selectById(jobId);
        if (job == null) {
            log.warn("execute called for unknown sync job: {}", jobId);
            return;
        }
        PlatformConnectionEntity connection = platformConnectionMapper.selectById(job.getConnectionId());
        UUID storeId = connection != null ? connection.getStoreId() : null;
        String key = singleFlightKey(storeId, job.getEntityType());

        // Honor single-flight for the synchronous (scheduler) entry point too.
        if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) {
            log.info("Skipping execute for job {}: a sync is already running for {}", jobId, key);
            return;
        }
        runGuarded(jobId, key);
    }

    /** Runs the pipeline and always releases the single-flight reservation. */
    private void runGuarded(UUID jobId, String key) {
        try {
            runPipeline(jobId);
        } catch (Exception e) {
            log.error("Sync job {} failed unexpectedly: {}", jobId, e.getMessage(), e);
            failJob(jobId, rootMessage(e));
        } finally {
            inFlight.remove(key, Boolean.TRUE);
        }
    }

    // ── orchestration ─────────────────────────────────────────────────────────

    private void runPipeline(UUID jobId) {
        ApiSyncJobEntity job = apiSyncJobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        if (STATUS_CANCELLED.equalsIgnoreCase(job.getStatus())) {
            log.info("Skipping cancelled sync job {}", jobId);
            return;
        }
        String entityType = job.getEntityType();
        boolean fullResync = "full".equalsIgnoreCase(job.getSyncType());

        PlatformConnectionEntity connection = platformConnectionMapper.selectById(job.getConnectionId());
        if (connection == null) {
            failJob(jobId, "Platform connection not found: " + job.getConnectionId());
            return;
        }
        String platform = connection.getPlatform();
        UUID storeId = connection.getStoreId();

        PlatformDataConnector connector = connectors.get(platform);
        if (connector == null) {
            failJob(jobId, "No data connector available for platform '" + platform + "'");
            return;
        }

        ConnectionContext ctx = new ConnectionContext(
                connection.getId(), storeId, platform, decryptConfig(connection.getConfigEncrypted()));

        // Req 1.1.5: validate credentials before pulling for connectors that do not
        // self-validate. Self-validating connectors (Amazon) acquire/refresh their
        // token during the pull and signal expired/invalid credentials via
        // ReauthRequiredException so the connection can be marked for re-auth (Req 8.1.5).
        if (!connector.selfValidatesCredentials()) {
            PlatformConnector.TestResult credentialCheck = platformConnector.test(ctx);
            if (!credentialCheck.ok()) {
                failJob(jobId, "Credentials rejected: " + credentialCheck.message());
                return;
            }
        }

        SyncContext syncCtx = new SyncContext(jobId, connection.getId(), storeId, platform, entityType, fullResync);

        Optional<Instant> watermark = watermarkStore.get(storeId, entityType);
        Instant since = IncrementalSelector.resolveSince(watermark, fullResync);

        AtomicInteger attempted = new AtomicInteger();
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        Instant maxChangedAt = null;

        try {
            PageCursor cursor = PageCursor.start();
            boolean hasMore = true;
            while (hasMore) {
                ExternalPage page = pull(connector, ctx, entityType, since, cursor);
                List<ExternalRecord> records = page.records() == null ? List.of() : page.records();

                // Req 1.1.6/1.1.7: defensively restrict to the incremental window.
                List<ExternalRecord> selected = IncrementalSelector.select(records, since);
                for (ExternalRecord record : selected) {
                    attempted.incrementAndGet();
                    Instant recordChangedAt = processRecord(jobId, syncCtx, record, processed, failed);
                    if (recordChangedAt != null
                            && (maxChangedAt == null || recordChangedAt.isAfter(maxChangedAt))) {
                        maxChangedAt = recordChangedAt;
                    }
                }

                // Req 1.3.2: keep running counts current while the job runs.
                updateProgress(jobId, attempted.get(), processed.get(), failed.get());

                hasMore = page.hasMore() && page.next() != null;
                cursor = page.next();
            }
        } catch (ReauthRequiredException e) {
            // Req 8.1.5: expired/invalid token — record the reason and mark the
            // connection as requiring re-authorization.
            log.warn("Sync job {} requires re-auth for {}/{}: {}",
                    jobId, platform, entityType, e.getReason());
            updateProgress(jobId, attempted.get(), processed.get(), failed.get());
            markRequiresReauth(connection, e.getReason());
            failJob(jobId, e.getReason());
            return;
        } catch (Exception e) {
            log.warn("Sync job {} pull failed for {}/{}: {}", jobId, platform, entityType, rootMessage(e));
            updateProgress(jobId, attempted.get(), processed.get(), failed.get());
            failJob(jobId, rootMessage(e));
            return;
        }

        // Req 1.1.8: advance the watermark to the most recent processed record.
        if (maxChangedAt != null) {
            watermarkStore.advance(storeId, entityType, maxChangedAt);
        }
        completeJob(jobId, attempted.get(), processed.get(), failed.get());
        log.info("Sync job {} completed: store={}, entityType={}, processed={}, failed={}",
                jobId, storeId, entityType, processed.get(), failed.get());
    }

    /**
     * Map, validate, and upsert a single record. Returns the record's change
     * timestamp when it was successfully processed (for watermark advancement),
     * or {@code null} when it was excluded or errored.
     */
    private Instant processRecord(UUID jobId, SyncContext ctx, ExternalRecord record,
                                  AtomicInteger processed, AtomicInteger failed) {
        String externalId = record != null ? record.externalId() : null;
        MappedRecord mapped;
        try {
            mapped = recordMapper.map(ctx, record);
        } catch (Exception e) {
            // A record that cannot even be mapped is a record-level error (Req 1.3.4).
            failed.incrementAndGet();
            recordError(jobId, externalId, null, "mapping_error", rootMessage(e));
            return null;
        }

        // Req 1.4.1/1.4.2: validate; exclude invalid records and log exactly one error each.
        ValidationResult validation = dataQualityValidator.validate(ctx.entityType(), mapped);
        if (!validation.valid()) {
            failed.incrementAndGet();
            FieldError first = validation.errors().isEmpty() ? null : validation.errors().get(0);
            recordError(jobId, externalId,
                    first != null ? first.field() : null,
                    first != null ? first.code() : "validation_error",
                    summarize(validation));
            return null;
        }

        try {
            // Req 1.2: idempotent upsert keyed on the external identifier.
            upsertService.upsert(ctx, mapped);
            processed.incrementAndGet();
            return mapped.changedAt();
        } catch (Exception e) {
            // An upsert failure for one record must not abort the whole job (Req 1.3.4).
            failed.incrementAndGet();
            recordError(jobId, externalId, null, "upsert_error", rootMessage(e));
            return null;
        }
    }

    private ExternalPage pull(PlatformDataConnector connector, ConnectionContext ctx,
                              String entityType, Instant since, PageCursor cursor) {
        return switch (entityType) {
            case DefaultRecordMapper.ENTITY_ORDER -> connector.pullOrders(ctx, since, cursor);
            case DefaultRecordMapper.ENTITY_PRODUCT -> connector.pullProducts(ctx, since, cursor);
            case "inventory" -> connector.pullInventory(ctx, since, cursor);
            case "ad_report" -> connector.pullAdReports(ctx, since, cursor);
            default -> throw new BusinessException("UNSUPPORTED_ENTITY_TYPE",
                    "Unsupported entity type for sync: '" + entityType + "'");
        };
    }

    // ── job-state persistence ───────────────────────────────────────────────

    private void updateProgress(UUID jobId, int total, int processed, int failed) {
        if (isCancelled(jobId)) {
            return;
        }
        ApiSyncJobEntity update = new ApiSyncJobEntity();
        update.setId(jobId);
        update.setTotalRecords(total);
        update.setRecordsProcessed(processed);
        update.setFailedRecords(failed);
        apiSyncJobMapper.updateById(update);
    }

    private void completeJob(UUID jobId, int total, int processed, int failed) {
        if (isCancelled(jobId)) {
            log.info("Not completing cancelled sync job {}", jobId);
            return;
        }
        ApiSyncJobEntity update = new ApiSyncJobEntity();
        update.setId(jobId);
        update.setStatus(STATUS_COMPLETED);
        update.setTotalRecords(total);
        update.setRecordsProcessed(processed);
        update.setFailedRecords(failed);
        update.setCompletedAt(LocalDateTime.now());
        apiSyncJobMapper.updateById(update);
    }

    private void failJob(UUID jobId, String reason) {
        if (isCancelled(jobId)) {
            log.info("Not failing cancelled sync job {}", jobId);
            return;
        }
        ApiSyncJobEntity update = new ApiSyncJobEntity();
        update.setId(jobId);
        update.setStatus(STATUS_FAILED);
        update.setErrorMessage(truncate(reason, 1000));
        update.setCompletedAt(LocalDateTime.now());
        apiSyncJobMapper.updateById(update);
        log(jobId, "error", "Sync job failed", reason);
    }

    private boolean isCancelled(UUID jobId) {
        ApiSyncJobEntity current = apiSyncJobMapper.selectById(jobId);
        return current != null && STATUS_CANCELLED.equalsIgnoreCase(current.getStatus());
    }

    /**
     * Req 8.1.5: mark a connection as requiring re-authorization after a platform
     * rejected its credentials as expired/invalid. The accompanying failure
     * reason is recorded on the job by {@link #failJob}.
     */
    private void markRequiresReauth(PlatformConnectionEntity connection, String reason) {
        if (connection == null) {
            return;
        }
        PlatformConnectionEntity update = new PlatformConnectionEntity();
        update.setId(connection.getId());
        update.setStatus(CONNECTION_STATUS_REQUIRES_REAUTH);
        update.setUpdatedAt(LocalDateTime.now());
        platformConnectionMapper.updateById(update);
        log.warn("Connection {} marked '{}': {}",
                connection.getId(), CONNECTION_STATUS_REQUIRES_REAUTH, reason);
    }

    /** Persist exactly one {@code sync_record_errors} entry for an invalid/errored record (Req 1.4.2). */
    private void recordError(UUID jobId, String externalId, String field, String code, String message) {
        SyncRecordErrorEntity error = SyncRecordErrorEntity.builder()
                .jobId(jobId)
                .externalEntityId(truncate(externalId, 255))
                .field(truncate(field, 100))
                .errorCode(truncate(code, 100))
                .errorMessage(message)
                .build();
        syncRecordErrorMapper.insert(error);
        // Req 1.3.4: also record a log entry with the error detail and continue.
        log(jobId, "warn", "Record error: " + code, message);
    }

    private void log(UUID jobId, String level, String message, String details) {
        try {
            ApiSyncLogEntity entry = ApiSyncLogEntity.builder()
                    .jobId(jobId)
                    .level(level)
                    .message(truncate(message, 500))
                    .details(details)
                    .build();
            apiSyncLogMapper.insert(entry);
        } catch (Exception e) {
            log.debug("Failed to write sync log for job {}: {}", jobId, e.getMessage());
        }
    }

    // ── single-flight helpers ─────────────────────────────────────────────────

    private static String singleFlightKey(UUID storeId, String entityType) {
        return storeId + "|" + entityType;
    }

    /** Defensive cross-restart check: is a job already running for this (store, entityType)? */
    private boolean hasRunningJob(UUID storeId, String entityType) {
        LambdaQueryWrapper<PlatformConnectionEntity> connWrapper =
                new LambdaQueryWrapper<PlatformConnectionEntity>()
                        .eq(PlatformConnectionEntity::getStoreId, storeId)
                        .select(PlatformConnectionEntity::getId);
        List<PlatformConnectionEntity> connections = platformConnectionMapper.selectList(connWrapper);
        if (connections.isEmpty()) {
            return false;
        }
        List<UUID> connectionIds = connections.stream().map(PlatformConnectionEntity::getId).toList();
        LambdaQueryWrapper<ApiSyncJobEntity> jobWrapper = new LambdaQueryWrapper<ApiSyncJobEntity>()
                .in(ApiSyncJobEntity::getConnectionId, connectionIds)
                .eq(ApiSyncJobEntity::getEntityType, entityType)
                .eq(ApiSyncJobEntity::getStatus, STATUS_RUNNING);
        return apiSyncJobMapper.selectCount(jobWrapper) > 0;
    }

    // ── credential decryption (mirrors ApiSyncServiceImpl) ─────────────────────

    private Map<String, String> decryptConfig(String stored) {
        if (stored == null || stored.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> encrypted = objectMapper.readValue(
                    stored, new TypeReference<LinkedHashMap<String, String>>() {});
            Map<String, String> plain = new LinkedHashMap<>();
            encrypted.forEach((k, v) -> plain.put(k, v == null ? null : cryptoUtil.decrypt(v)));
            return plain;
        } catch (Exception e) {
            log.warn("Failed to read platform config for sync: {}", e.getMessage());
            return Map.of();
        }
    }

    // ── misc helpers ──────────────────────────────────────────────────────────

    private static String summarize(ValidationResult validation) {
        if (validation.errors().isEmpty()) {
            return "Validation failed";
        }
        StringBuilder sb = new StringBuilder();
        for (FieldError error : validation.errors()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(error.message());
        }
        return sb.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m != null ? m : cur.getClass().getSimpleName();
    }

    private ApiSyncJobVo toJobVo(ApiSyncJobEntity entity) {
        return ApiSyncJobVo.builder()
                .id(entity.getId().toString())
                .connectionId(entity.getConnectionId().toString())
                .syncType(entity.getSyncType())
                .entityType(entity.getEntityType())
                .status(entity.getStatus())
                .totalRecords(entity.getTotalRecords())
                .recordsProcessed(entity.getRecordsProcessed())
                .failedRecords(entity.getFailedRecords())
                .errorMessage(entity.getErrorMessage())
                .startedAt(entity.getStartedAt() != null ? entity.getStartedAt().format(FORMATTER) : null)
                .completedAt(entity.getCompletedAt() != null ? entity.getCompletedAt().format(FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }
}
