package com.adpilot.modules.advertising.operation;

import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.OperationOutboxEntity;
import com.adpilot.modules.advertising.hosting.PreSubmissionRevalidator;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.apisync.connector.AmazonAdsRateLimiter;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformWriteResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@code OutboxWorker}: the scheduled component that drains the {@code Outbox} and submits each
 * pending {@code platform_mutation} Operation to the live platform through the Store's
 * {@code Write_Connector}, strictly OUTSIDE any database transaction (Req 6.3).
 *
 * <p><b>Retry ownership (Req 16.7, 30.1, 30.7):</b> The Outbox is the <em>single retry owner</em>.
 * The connector NEVER retries internally — all delayed retry scheduling lives on the Outbox row:
 * <ul>
 *   <li>On {@code retryable=true}: increment {@code attempt_count}, set
 *       {@code next_attempt_at = now + retryAfterSeconds}, record {@code last_error}, and transition
 *       the Operation from {@code submitting} → {@code pending} (via {@link TransitionEvent#RETRYABLE_REJECT}).
 *       The Operation stays pre-submission and the same Outbox row is re-claimed after the delay.</li>
 *   <li>On accepted: transition the Operation from {@code submitting} → {@code submitted}
 *       (via {@link TransitionEvent#PLATFORM_ACCEPTED}), record the {@code amazonRequestId} and
 *       {@code externalEntityId}.</li>
 *   <li>On permanent reject: transition the Operation from {@code submitting} → {@code failed}
 *       (via {@link TransitionEvent#PERMANENT_REJECT}).</li>
 * </ul>
 *
 * <p><b>Circuit-breaker gate (Req 30.1, 15.1, 15.2):</b> Before claiming an Outbox row for an
 * {@code amazon_ads} platform, the worker checks the {@link AmazonAdsRateLimiter}. If no token is
 * available, the row is skipped (the Outbox re-claims it on the next tick or after the rate window).
 *
 * <p><b>Delayed retry respect (Req 30.7):</b> The {@link #findPendingRows()} query only selects rows
 * where {@code next_attempt_at} is NULL or in the past, so delayed retries are never claimed early.
 *
 * <p>Validates: Requirements 4.5, 6.3, 16.7, 30.1, 30.7, 51.6.</p>
 */
@Slf4j
@Component
public class OutboxWorker {

    /** The connection status treated as a valid active connection (mirrors WriteCapabilityServiceImpl). */
    private static final String STATUS_CONNECTED = ConnectionStatus.CONNECTED;

    /** Outbox row lifecycle values (mirrors the operation_outbox.status column comment). */
    private static final String OUTBOX_PENDING = "pending";
    private static final String OUTBOX_CLAIMED = "claimed";
    private static final String OUTBOX_SUBMITTED = "submitted";
    private static final String OUTBOX_DONE = "done";
    private static final String OUTBOX_FAILED = "failed";

    private final OperationOutboxMapper outboxMapper;
    private final OperationMapper operationMapper;
    private final OperationRecordService operationRecordService;
    private final OperationService operationService;
    private final IdempotencyService idempotencyService;
    private final WriteCapabilityService writeCapabilityService;
    private final PlatformConnectionMapper platformConnectionMapper;
    private final OperationJsonCodec jsonCodec;
    private final ObjectMapper objectMapper;
    private final CryptoUtil cryptoUtil;
    private final AmazonAdsRateLimiter rateLimiter;
    private final PreSubmissionRevalidator preSubmissionRevalidator;

    /** platform key -> write connector, built once from all injected connector beans. */
    private final Map<String, PlatformWriteConnector> writeConnectors = new ConcurrentHashMap<>();

    /** Number of {@code pending} Outbox rows claimed per tick (configurable). */
    private final int batchSize;

    /** Default retry delay in seconds when the platform doesn't specify one. */
    private static final long DEFAULT_RETRY_DELAY_SECONDS = 30;

    // --- Write-back submission monitoring counters (Req 51.6). ---
    private final AtomicLong submittedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();

    public OutboxWorker(OperationOutboxMapper outboxMapper,
                        OperationMapper operationMapper,
                        OperationRecordService operationRecordService,
                        OperationService operationService,
                        IdempotencyService idempotencyService,
                        WriteCapabilityService writeCapabilityService,
                        PlatformConnectionMapper platformConnectionMapper,
                        OperationJsonCodec jsonCodec,
                        ObjectMapper objectMapper,
                        CryptoUtil cryptoUtil,
                        AmazonAdsRateLimiter rateLimiter,
                        PreSubmissionRevalidator preSubmissionRevalidator,
                        List<PlatformWriteConnector> writeConnectorBeans,
                        @Value("${adpilot.operation.outbox-batch:50}") int batchSize) {
        this.outboxMapper = outboxMapper;
        this.operationMapper = operationMapper;
        this.operationRecordService = operationRecordService;
        this.operationService = operationService;
        this.idempotencyService = idempotencyService;
        this.writeCapabilityService = writeCapabilityService;
        this.platformConnectionMapper = platformConnectionMapper;
        this.jsonCodec = jsonCodec;
        this.objectMapper = objectMapper;
        this.cryptoUtil = cryptoUtil;
        this.rateLimiter = rateLimiter;
        this.preSubmissionRevalidator = preSubmissionRevalidator;
        this.batchSize = batchSize > 0 ? batchSize : 50;
        for (PlatformWriteConnector connector : writeConnectorBeans) {
            this.writeConnectors.put(connector.platform(), connector);
        }
    }

    /**
     * One drain tick. The whole tick — and every row within it — is isolated so a single failing row
     * never aborts the batch and the scheduler keeps ticking. Crucially this method is NOT
     * {@code @Transactional}: the claim, the platform submission, and the resulting transitions are
     * independent units, so no external platform request is ever made inside a DB transaction
     * (Req 6.3).
     */
    @Scheduled(fixedDelayString = "${adpilot.operation.outbox-ms:15000}")
    public void drain() {
        List<OperationOutboxEntity> candidates;
        try {
            candidates = findPendingRows();
        } catch (Exception ex) {
            log.warn("OutboxWorker failed to load pending Outbox rows", ex);
            return;
        }
        for (OperationOutboxEntity row : candidates) {
            try {
                if (!claim(row)) {
                    // Lost the race to a concurrent worker (version-claim affected 0 rows): skip.
                    continue;
                }
                processClaimedRow(row);
            } catch (RuntimeException ex) {
                // Per-row isolation (Req 6): one failing row never aborts the batch.
                log.warn("OutboxWorker failed to process Outbox row {} (operation {}): {}",
                        row.getId(), row.getOperationId(), ex.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Claim
    // ---------------------------------------------------------------------------------------------

    /**
     * Select pending Outbox rows that are eligible for claim: status == pending AND
     * (next_attempt_at IS NULL OR next_attempt_at <= NOW). This ensures delayed retries are never
     * claimed early — the Outbox is the single retry owner and it respects the backoff schedule
     * set by a previous retryable rejection (Req 30.7).
     */
    private List<OperationOutboxEntity> findPendingRows() {
        return outboxMapper.selectList(new LambdaQueryWrapper<OperationOutboxEntity>()
                .eq(OperationOutboxEntity::getStatus, OUTBOX_PENDING)
                .and(wrapper -> wrapper
                        .isNull(OperationOutboxEntity::getNextAttemptAt)
                        .or()
                        .le(OperationOutboxEntity::getNextAttemptAt, LocalDateTime.now()))
                .orderByAsc(OperationOutboxEntity::getCreatedAt)
                .last("LIMIT " + batchSize));
    }

    /**
     * Version-claim a single Outbox row: flip {@code pending → claimed} with a conditional update
     * guarded on the current status. Exactly one worker's update affects one row; a concurrent worker
     * observes zero affected rows and skips it (the {@code SKIP LOCKED} effect), so a row is never
     * submitted twice. The claim is its own committed statement — no surrounding transaction holds a
     * lock across the later platform call.
     *
     * <p>Note: {@code attempt_count} is NOT incremented on claim. It is incremented only when a
     * retryable rejection occurs, representing the number of failed platform attempts for this
     * Outbox row (Req 16.7, 30.7).</p>
     *
     * @return {@code true} iff this worker won the claim
     */
    private boolean claim(OperationOutboxEntity row) {
        UpdateWrapper<OperationOutboxEntity> claim = new UpdateWrapper<OperationOutboxEntity>()
                .set("status", OUTBOX_CLAIMED)
                .set("claimed_at", LocalDateTime.now())
                .eq("id", row.getId().toString())
                .eq("status", OUTBOX_PENDING);
        return outboxMapper.update(null, claim) == 1;
    }

    // ---------------------------------------------------------------------------------------------
    // Submission (outside any DB transaction)
    // ---------------------------------------------------------------------------------------------

    private void processClaimedRow(OperationOutboxEntity row) {
        OperationEntity operation = operationRecordService.findById(row.getOperationId()).orElse(null);
        if (operation == null) {
            // Orphaned Outbox row (its Operation was never persisted or was removed): nothing to submit.
            log.warn("OutboxWorker: Outbox row {} references missing Operation {}; marking failed",
                    row.getId(), row.getOperationId());
            markOutbox(row.getId(), OUTBOX_FAILED);
            return;
        }

        // Idempotent advance (Req 5.7): only a still-pending Operation is submitted. A re-claimed row
        // whose Operation already advanced (crash after submit, or a callback/poll won the race) is
        // recognized and never re-submitted — submitting the same change twice is avoided.
        SyncState current = OperationMachineValues.toSyncState(operation.getSyncState());
        if (current != SyncState.PENDING) {
            log.debug("OutboxWorker: Operation {} already in {}; Outbox row {} needs no submission",
                    operation.getId(), current, row.getId());
            markOutbox(row.getId(),
                    current == SyncState.SUBMITTED || current == SyncState.AMAZON_PROCESSING
                            || current == SyncState.EFFECTIVE
                            ? OUTBOX_SUBMITTED : OUTBOX_DONE);
            return;
        }

        // Defensive write-capability gate (Req 53.2, Property 12): an Outbox entry is only written for
        // a write-capable Store, but a Store can lose capability after the row is written. Never submit
        // for a Store that is no longer write-capable, and never advance it into a platform Sync_State.
        ResolvedConnector resolved = resolveConnector(operation.getStoreId());
        if (resolved == null) {
            log.warn("OutboxWorker: Store {} is not write-capable / has no connector; Outbox row {} held",
                    operation.getStoreId(), row.getId());
            markOutbox(row.getId(), OUTBOX_FAILED);
            return;
        }

        // Circuit-breaker gate (Req 30.1, 15.1, 15.2): check rate limiter before submission for
        // amazon_ads platform. If no token is available, return the row to pending so it is picked up
        // on the next tick or after the rate window. This prevents overwhelming the Amazon Ads API.
        if ("amazon_ads".equals(row.getPlatform())) {
            String profileId = resolveProfileId(operation.getStoreId());
            if (profileId != null && !rateLimiter.tryAcquire(profileId)) {
                // Rate limited — return the row to pending status so it can be re-claimed later.
                log.debug("OutboxWorker: rate limiter blocked Outbox row {} for profile {}; returning to pending",
                        row.getId(), profileId);
                markOutbox(row.getId(), OUTBOX_PENDING);
                return;
            }
        }

        // Pre-submission revalidation (Req 3.7, 33.1–33.5): verify decision expiry, re-run
        // data-quality gate, and re-resolve safety boundaries. If any check fails, the Operation is
        // superseded and must not be submitted. This ensures stale decisions accumulated during an
        // outage are never blindly submitted when connectivity is restored (Req 33.4).
        PreSubmissionRevalidator.RevalidationResult revalidation =
                preSubmissionRevalidator.revalidate(operation.getId());
        if (!revalidation.valid()) {
            log.info("OutboxWorker: Pre-submission revalidation failed for Operation {} (reason={}); "
                    + "Outbox row {} marked done", operation.getId(), revalidation.reason(), row.getId());
            markOutbox(row.getId(), OUTBOX_DONE);
            return;
        }

        // Mint a fresh per-submission idempotency key for THIS attempt (Req 5.2/5.7) and persist it on
        // the Operation (so a later callback/poll correlates by it) and on the Outbox row, BEFORE the
        // platform call so the platform receives the key it can dedupe an exact re-delivery against.
        String submissionKey = hasText(operation.getSubmissionIdempotencyKey())
                ? operation.getSubmissionIdempotencyKey()
                : idempotencyService.newSubmissionIdempotencyKey();
        persistSubmissionKey(operation.getId(), row.getId(), submissionKey);
        operation.setSubmissionIdempotencyKey(submissionKey);

        // pending → submitting (Req 16.7). The transient SUBMITTING state represents "platform call
        // in progress"; only a confirmed acceptance advances to submitted, a retryable rejection
        // returns to pending, and a permanent rejection advances to failed.
        try {
            operationService.transition(operation.getId(), TransitionEvent.SUBMIT);
        } catch (RuntimeException ex) {
            // Another actor advanced the Operation between the read and here: idempotent no-op.
            log.debug("OutboxWorker: SUBMIT transition for Operation {} was a no-op: {}",
                    operation.getId(), ex.getMessage());
            markOutbox(row.getId(), OUTBOX_SUBMITTED);
            return;
        }

        // Submit to the live platform OUTSIDE any DB transaction (Req 6.3). The SPI contract treats a
        // transport/credential exception as a rejection carrying the exception message as the reason.
        PlatformWriteResult result;
        try {
            PlatformChange change = buildChange(operation, resolved.ctx().platform(), submissionKey);
            result = resolved.connector().submit(resolved.ctx(), change);
            if (result == null) {
                result = PlatformWriteResult.rejected("Connector returned no result");
            }
        } catch (RuntimeException ex) {
            // Transport/connection exceptions are treated as retryable (transient infrastructure
            // failure) unless the exception specifically indicates a permanent issue.
            result = PlatformWriteResult.retryable(rootMessage(ex), DEFAULT_RETRY_DELAY_SECONDS);
        }

        if (result.accepted()) {
            onAccepted(operation, row, result);
        } else if (result.retryable()) {
            onRetryableReject(operation, row, result);
        } else {
            onPermanentReject(operation, row, result);
        }
    }

    /**
     * The platform accepted the submission. Advance the Operation from {@code submitting} to
     * {@code submitted} (Req 16.7), record the platform reference, Amazon request ID, and external
     * entity ID (Req 1.11, 16.5) so the StatusPoller / VerificationWorker / callback can correlate
     * the FINAL platform result back to this Operation, and mark the Outbox row submitted. The
     * Operation's platform-final result (effective / failed) is resolved asynchronously, not here
     * (Req 6.8).
     */
    private void onAccepted(OperationEntity operation, OperationOutboxEntity row, PlatformWriteResult result) {
        // submitting → submitted (Req 16.7): only confirmed platform acceptance advances to submitted.
        try {
            operationService.transition(operation.getId(), TransitionEvent.PLATFORM_ACCEPTED);
        } catch (RuntimeException ex) {
            log.debug("OutboxWorker: PLATFORM_ACCEPTED transition for Operation {} was a no-op: {}",
                    operation.getId(), ex.getMessage());
        }
        // Persist platform reference, Amazon request ID, and external entity ID as structured fields.
        persistAcceptanceMetadata(operation.getId(), result);
        markOutbox(row.getId(), OUTBOX_SUBMITTED);
        submittedCount.incrementAndGet();
        // Structured write-back submission log (Req 51.6) — no secret values are included.
        log.info("write-back outcome=submitted operation={} store={} platform={} attemptCount={} amazonRequestId={} externalEntityId={}",
                operation.getId(), operation.getStoreId(), row.getPlatform(),
                row.getAttemptCount(), result.amazonRequestId(), result.externalEntityId());
    }

    /**
     * The platform returned a retryable/transient rejection (429, 5xx, or transient exception). The
     * Outbox is the single retry owner (Req 16.7, 30.1, 30.7): it increments {@code attempt_count},
     * sets {@code next_attempt_at = now + retryAfterSeconds}, records the {@code last_error}, returns
     * the Outbox row to {@code pending}, and transitions the Operation from {@code submitting} →
     * {@code pending} (via RETRYABLE_REJECT). The connector NEVER retries internally — the Outbox row
     * will be re-claimed after the delay expires.
     */
    private void onRetryableReject(OperationEntity operation, OperationOutboxEntity row, PlatformWriteResult result) {
        String failureReason = hasText(result.message()) ? result.message() : "平台返回可重试错误";

        // submitting → pending (Req 16.7): retryable rejection returns the Operation pre-submission.
        try {
            operationService.transition(operation.getId(), TransitionEvent.RETRYABLE_REJECT);
        } catch (RuntimeException ex) {
            log.debug("OutboxWorker: RETRYABLE_REJECT transition for Operation {} was a no-op: {}",
                    operation.getId(), ex.getMessage());
        }

        // Compute the retry delay. Use the platform-provided retryAfterSeconds if available,
        // otherwise fall back to DEFAULT_RETRY_DELAY_SECONDS.
        long delaySec = result.retryAfterSeconds() != null && result.retryAfterSeconds() > 0
                ? result.retryAfterSeconds()
                : DEFAULT_RETRY_DELAY_SECONDS;
        LocalDateTime nextAttempt = LocalDateTime.now().plusSeconds(delaySec);

        // Update the Outbox row: increment attempt_count, set next_attempt_at for delayed re-claim,
        // record the last_error, and return status to pending so the row is eligible for re-claim
        // after the delay (Req 30.7).
        outboxMapper.update(null, new UpdateWrapper<OperationOutboxEntity>()
                .set("status", OUTBOX_PENDING)
                .set("next_attempt_at", nextAttempt)
                .set("last_error", truncate(failureReason, 500))
                .setSql("attempt_count = attempt_count + 1")
                .eq("id", row.getId().toString()));

        // Record rate limit event for adaptive backoff (Req 15.6) when it was a 429.
        if ("amazon_ads".equals(row.getPlatform()) && failureReason.contains("429")) {
            String profileId = resolveProfileId(operation.getStoreId());
            if (profileId != null) {
                rateLimiter.recordRateLimit(profileId);
            }
        }

        log.info("write-back outcome=retryable_reject operation={} store={} platform={} attemptCount={} nextAttemptAt={} reason={}",
                operation.getId(), operation.getStoreId(), row.getPlatform(),
                row.getAttemptCount() != null ? row.getAttemptCount() + 1 : 1, nextAttempt, failureReason);
    }

    /**
     * The platform returned a permanent rejection (4xx, invalid token, etc.) that will not succeed on
     * retry. Transition the Operation from {@code submitting} → {@code failed} via PERMANENT_REJECT
     * (Req 16.7), mark the Outbox row failed, and record the error.
     */
    private void onPermanentReject(OperationEntity operation, OperationOutboxEntity row, PlatformWriteResult result) {
        String failureReason = hasText(result.message()) ? result.message() : "平台永久拒绝了本次提交";
        try {
            operationService.transition(operation.getId(), TransitionEvent.PERMANENT_REJECT);
        } catch (RuntimeException ex) {
            log.debug("OutboxWorker: PERMANENT_REJECT transition for Operation {} was a no-op: {}",
                    operation.getId(), ex.getMessage());
        }

        // Record the error and mark the Outbox row as failed — no further re-claims.
        outboxMapper.update(null, new UpdateWrapper<OperationOutboxEntity>()
                .set("status", OUTBOX_FAILED)
                .set("last_error", truncate(failureReason, 500))
                .eq("id", row.getId().toString()));

        failedCount.incrementAndGet();
        // Structured write-back submission log (Req 51.6).
        log.info("write-back outcome=permanent_reject operation={} store={} platform={} attemptCount={} reason={} platformErrorCode={}",
                operation.getId(), operation.getStoreId(), row.getPlatform(),
                row.getAttemptCount(), failureReason, result.platformErrorCode());
    }

    // ---------------------------------------------------------------------------------------------
    // Persistence helpers (each its own committed statement — never inside a platform-call txn)
    // ---------------------------------------------------------------------------------------------

    private void persistSubmissionKey(UUID operationId, UUID outboxId, String submissionKey) {
        operationMapper.update(null, new UpdateWrapper<OperationEntity>()
                .set("submission_idempotency_key", submissionKey)
                .eq("id", operationId.toString()));
        outboxMapper.update(null, new UpdateWrapper<OperationOutboxEntity>()
                .set("submission_idempotency_key", submissionKey)
                .eq("id", outboxId.toString()));
    }

    /**
     * Persist the acceptance metadata from the platform on the Operation (Req 1.11, 16.5):
     * the Amazon request ID, external entity ID, and platform reference. These fields enable
     * the VerificationWorker and StatusPoller to correlate the submission with the platform entity.
     */
    private void persistAcceptanceMetadata(UUID operationId, PlatformWriteResult result) {
        UpdateWrapper<OperationEntity> update = new UpdateWrapper<OperationEntity>()
                .eq("id", operationId.toString());
        if (hasText(result.platformReference()) || hasText(result.amazonRequestId())) {
            update.set("platform_reference",
                    hasText(result.amazonRequestId()) ? result.amazonRequestId() : result.platformReference());
        }
        // Store structured acceptance metadata (amazon request id + external entity id) as JSON in
        // platform_result so the VerificationWorker has the submission context (Req 16.5).
        if (hasText(result.amazonRequestId()) || hasText(result.externalEntityId())) {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("amazonRequestId", result.amazonRequestId());
            metadata.put("externalEntityId", result.externalEntityId());
            try {
                update.set("platform_result", objectMapper.writeValueAsString(metadata));
            } catch (Exception e) {
                log.warn("OutboxWorker: failed to serialize acceptance metadata for Operation {}: {}",
                        operationId, e.getMessage());
            }
        }
        operationMapper.update(null, update);
    }

    private void persistPlatformReference(UUID operationId, String platformReference) {
        operationMapper.update(null, new UpdateWrapper<OperationEntity>()
                .set("platform_reference", platformReference)
                .eq("id", operationId.toString()));
    }

    private void markOutbox(UUID outboxId, String status) {
        outboxMapper.update(null, new UpdateWrapper<OperationOutboxEntity>()
                .set("status", status)
                .eq("id", outboxId.toString()));
    }

    /**
     * Build the normalized {@link PlatformChange} for the connector from the Operation's recorded
     * change. The before/after values are stored as JSON text on the Operation, so they are decoded
     * back to a domain value and rendered as a plain string for the platform-agnostic change. The
     * per-attempt {@code submissionIdempotencyKey} is carried so the platform can dedupe an exact
     * re-delivery of this submission (Req 5.2/5.7).
     */
    private PlatformChange buildChange(OperationEntity operation, String platform, String submissionKey) {
        String changeType = hasText(operation.getField()) ? operation.getField() : operation.getEntityType();
        return new PlatformChange(
                platform,
                operation.getStoreId(),
                changeType,
                operation.getEntityType(),
                operation.getEntityId() != null ? operation.getEntityId().toString() : null,
                renderValue(operation.getBeforeValue()),
                renderValue(operation.getAfterValue()),
                operation.getOperationSource(),
                operation.getId() != null ? operation.getId().toString() : null,
                submissionKey);
    }

    /** Decode a stored JSON value column back to its plain string form for the connector change. */
    private String renderValue(String json) {
        if (!hasText(json)) {
            return null;
        }
        Object value = jsonCodec.fromJson(json, Object.class);
        return value == null ? null : String.valueOf(value);
    }

    // ---------------------------------------------------------------------------------------------
    // Connector resolution (mirrors StatusPoller)
    // ---------------------------------------------------------------------------------------------

    /**
     * Resolve the Store's connector and a {@link ConnectionContext} for a platform submission. A Store
     * that is NOT write-capable, or has no registered connector, is never submitted — so a
     * not-write-capable Store can never be advanced into a platform Sync_State by the worker
     * (Req 53.2, Property 12).
     *
     * @return the resolved connector + context, or {@code null} when the Store cannot be submitted to
     */
    private ResolvedConnector resolveConnector(UUID storeId) {
        if (storeId == null || !writeCapabilityService.isWriteCapable(storeId)) {
            return null;
        }
        PlatformConnectionEntity connection = platformConnectionMapper.selectList(
                        new LambdaQueryWrapper<PlatformConnectionEntity>()
                                .eq(PlatformConnectionEntity::getStoreId, storeId)
                                .eq(PlatformConnectionEntity::getStatus, STATUS_CONNECTED)
                                .orderByDesc(PlatformConnectionEntity::getUpdatedAt))
                .stream()
                .findFirst()
                .orElse(null);
        if (connection == null || !hasText(connection.getPlatform())) {
            return null;
        }
        PlatformWriteConnector connector = writeConnectors.get(connection.getPlatform());
        if (connector == null) {
            return null;
        }
        ConnectionContext ctx = new ConnectionContext(
                connection.getId(), storeId, connection.getPlatform(),
                decryptConfig(connection.getConfigEncrypted()));
        return new ResolvedConnector(connector, ctx);
    }

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
            log.warn("OutboxWorker failed to read platform config: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Resolve the Amazon Ads profile ID for the Store's active platform connection. The profile ID
     * is used as the rate limiter key (rate limits are per-profile).
     *
     * @return the profile ID string, or {@code null} if no connected amazon_ads connection exists
     */
    private String resolveProfileId(UUID storeId) {
        if (storeId == null) {
            return null;
        }
        PlatformConnectionEntity connection = platformConnectionMapper.selectList(
                        new LambdaQueryWrapper<PlatformConnectionEntity>()
                                .eq(PlatformConnectionEntity::getStoreId, storeId)
                                .eq(PlatformConnectionEntity::getPlatform, "amazon_ads")
                                .eq(PlatformConnectionEntity::getStatus, STATUS_CONNECTED)
                                .orderByDesc(PlatformConnectionEntity::getUpdatedAt))
                .stream()
                .findFirst()
                .orElse(null);
        return connection != null ? connection.getProfileId() : null;
    }

    /** Truncate a string to the given maximum length. */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // ---------------------------------------------------------------------------------------------
    // Monitoring accessors (Req 51.6)
    // ---------------------------------------------------------------------------------------------

    /** @return the cumulative count of submissions the platform accepted since startup (Req 51.6). */
    public long getSubmittedCount() {
        return submittedCount.get();
    }

    /** @return the cumulative count of submissions that failed since startup (Req 51.6). */
    public long getFailedCount() {
        return failedCount.get();
    }

    /** A resolved platform connector together with the connection context to call it with. */
    private record ResolvedConnector(PlatformWriteConnector connector, ConnectionContext ctx) {
    }
}
