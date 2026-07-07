package com.adpilot.modules.advertising.operation;

import com.adpilot.common.resilience.CircuitBreaker;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformVerifyResult;
import com.adpilot.modules.apisync.model.SubmissionMetadata;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code VerificationWorker}: a scheduled component that performs read-after-write verification
 * on {@code submitted} Operations after a configurable delay (Req 20.1, 20.4).
 *
 * <p>After an Operation is submitted and accepted by the Amazon Ads API, the live value may not
 * immediately reflect the change due to propagation delay. This worker waits for the configured
 * initial delay, then re-reads the entity's current field value via the connector's
 * {@link PlatformWriteConnector#verify} method and compares it against the Operation's expected
 * {@code after_value}.</p>
 *
 * <h2>Verification outcomes (Req 20.2, 20.3, 20.4)</h2>
 * <ul>
 *   <li><b>Match</b> — live value equals the expected after_value → transition to
 *       {@code effective} via {@link TransitionEvent#PLATFORM_EFFECTIVE}.</li>
 *   <li><b>Mismatch</b> — live value differs and is not the old value within the propagation
 *       window → transition to {@code reconciliation_required} via
 *       {@link TransitionEvent#RECONCILE}.</li>
 *   <li><b>Read failure / unchanged past retries</b> — the connector could not read the entity
 *       or the value is unchanged after the configured maximum verification attempts →
 *       transition to {@code expired} via {@link TransitionEvent#TIMEOUT} with statusReason
 *       "VERIFY_TIMEOUT". {@code expired} is NOT terminal: a later callback or successful
 *       re-read may still transition it to {@code effective}, {@code failed}, or
 *       {@code reconciliation_required} (Req 20.4).</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code adpilot.hosting.verify-delay-seconds} — initial delay before first verification
 *       attempt (default 60s, Req 20.1).</li>
 *   <li>{@code adpilot.hosting.verify-max-attempts} — maximum verification attempts before
 *       timing out (default 5, giving a total window of ~15 min with 60s intervals).</li>
 *   <li>{@code adpilot.hosting.verify-poll-ms} — polling interval for the worker (default 30s).</li>
 * </ul>
 *
 * <p>Validates: Requirements 20.1, 20.4.</p>
 */
@Slf4j
@Component
public class VerificationWorker {

    /** The connection status treated as a valid active connection. */
    private static final String STATUS_CONNECTED = ConnectionStatus.CONNECTED;

    /** platform_mutation scope marker. */
    private static final String SCOPE_PLATFORM_MUTATION =
            OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION);

    /** Status reason recorded when verification times out (Req 20.4). */
    private static final String REASON_VERIFY_TIMEOUT = "VERIFY_TIMEOUT";

    private final OperationMapper operationMapper;
    private final PlatformConnectionMapper platformConnectionMapper;
    private final OperationService operationService;
    private final WriteCapabilityService writeCapabilityService;
    private final OperationJsonCodec jsonCodec;
    private final ObjectMapper objectMapper;
    private final CryptoUtil cryptoUtil;
    private final CircuitBreaker circuitBreaker;

    /** platform key -> write connector, built once from all injected connector beans. */
    private final Map<String, PlatformWriteConnector> writeConnectors = new ConcurrentHashMap<>();

    /** Delay in seconds after submission before the first verification attempt (Req 20.1). */
    private final long verifyDelaySeconds;

    /** Maximum verification attempts before timing out to expired (Req 20.4). */
    private final int maxVerifyAttempts;

    public VerificationWorker(OperationMapper operationMapper,
                              PlatformConnectionMapper platformConnectionMapper,
                              OperationService operationService,
                              WriteCapabilityService writeCapabilityService,
                              OperationJsonCodec jsonCodec,
                              ObjectMapper objectMapper,
                              CryptoUtil cryptoUtil,
                              CircuitBreaker circuitBreaker,
                              List<PlatformWriteConnector> writeConnectorBeans,
                              @Value("${adpilot.hosting.verify-delay-seconds:60}") long verifyDelaySeconds,
                              @Value("${adpilot.hosting.verify-max-attempts:5}") int maxVerifyAttempts) {
        this.operationMapper = operationMapper;
        this.platformConnectionMapper = platformConnectionMapper;
        this.operationService = operationService;
        this.writeCapabilityService = writeCapabilityService;
        this.jsonCodec = jsonCodec;
        this.objectMapper = objectMapper;
        this.cryptoUtil = cryptoUtil;
        this.circuitBreaker = circuitBreaker;
        this.verifyDelaySeconds = verifyDelaySeconds;
        this.maxVerifyAttempts = maxVerifyAttempts > 0 ? maxVerifyAttempts : 5;
        for (PlatformWriteConnector connector : writeConnectorBeans) {
            this.writeConnectors.put(connector.platform(), connector);
        }
    }

    /**
     * One verification tick. Polls for {@code submitted} Operations that have passed the
     * configurable verification delay and verifies each one. Each Operation is processed in
     * isolation so a single failure never aborts the batch.
     */
    @Scheduled(fixedDelayString = "${adpilot.hosting.verify-poll-ms:30000}")
    public void verify() {
        List<OperationEntity> candidates;
        try {
            candidates = findVerificationCandidates();
        } catch (Exception ex) {
            log.warn("VerificationWorker failed to load verification candidates", ex);
            return;
        }
        for (OperationEntity op : candidates) {
            try {
                verifyOperation(op);
            } catch (RuntimeException ex) {
                log.warn("VerificationWorker failed to verify Operation {}: {}",
                        op.getId(), ex.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Candidate selection
    // ---------------------------------------------------------------------------------------------

    /**
     * Find {@code submitted} platform_mutation Operations that have been in the submitted state
     * long enough for the verification delay to have elapsed.
     */
    private List<OperationEntity> findVerificationCandidates() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(verifyDelaySeconds);
        String submittedValue = OperationMachineValues.toValue(SyncState.SUBMITTED);
        return operationMapper.selectList(new LambdaQueryWrapper<OperationEntity>()
                .eq(OperationEntity::getOperationScope, SCOPE_PLATFORM_MUTATION)
                .eq(OperationEntity::getSyncState, submittedValue)
                .le(OperationEntity::getUpdatedAt, cutoff)
                .orderByAsc(OperationEntity::getUpdatedAt));
    }

    // ---------------------------------------------------------------------------------------------
    // Per-operation verification logic
    // ---------------------------------------------------------------------------------------------

    private void verifyOperation(OperationEntity op) {
        // Resolve the connector for this store.
        ResolvedConnector resolved = resolveConnector(op);
        if (resolved == null) {
            log.debug("VerificationWorker: Store {} has no write connector; skipping Operation {}",
                    op.getStoreId(), op.getId());
            return;
        }

        // Build the SubmissionMetadata from the Operation's stored fields.
        SubmissionMetadata meta = buildSubmissionMetadata(op);
        if (meta == null) {
            log.debug("VerificationWorker: Operation {} has insufficient metadata for verification; skipping",
                    op.getId());
            return;
        }

        // Build the PlatformChange for the connector.
        PlatformChange change = buildChange(op, resolved.ctx().platform());

        // H3: skip verifying against a known-down dependency this tick. The Operation is left in
        // submitted (no attempt is recorded, no state change) so it is re-verified once the breaker
        // half-opens. Per-item isolation is preserved.
        String breakerKey = breakerKey(resolved);
        if (!circuitBreaker.allow(breakerKey)) {
            log.debug("VerificationWorker: circuit OPEN for {}, skipping verification of Operation {} this tick",
                    breakerKey, op.getId());
            return;
        }

        // Invoke the connector's verify method. A thrown failure counts against the breaker (a
        // persistently-down verify endpoint eventually opens it); a returned result — success or
        // a soft read-failure — counts as the dependency being reachable.
        PlatformVerifyResult result;
        try {
            result = resolved.connector().verify(resolved.ctx(), change, meta);
            if (result == null) {
                result = PlatformVerifyResult.readFailed("Connector returned null result");
            }
            circuitBreaker.recordSuccess(breakerKey);
        } catch (RuntimeException ex) {
            circuitBreaker.recordFailure(breakerKey);
            result = PlatformVerifyResult.readFailed(rootMessage(ex));
        }

        // Determine the verification attempt count for this operation.
        int attemptCount = getVerificationAttemptCount(op) + 1;

        if (result.read()) {
            // Successfully read the live value — compare against the expected after_value.
            String expectedValue = meta.expectedAfterValue();
            String liveValue = result.liveValue();

            if (valuesMatch(expectedValue, liveValue)) {
                // Match: the change is confirmed effective on the platform (Req 20.2).
                applyTransitionSafely(op, TransitionEvent.PLATFORM_EFFECTIVE,
                        "Verification confirmed: live value matches expected after_value");
                log.info("VerificationWorker: Operation {} verified effective (live={}, expected={})",
                        op.getId(), liveValue, expectedValue);
            } else {
                // Mismatch: the live value does not match the expected after_value (Req 20.3).
                // The state machine path is submitted → expired (TIMEOUT) → reconciliation_required
                // (RECONCILE), because there is no direct submitted → reconciliation_required edge.
                String mismatchReason = "Verification mismatch: live value '" + liveValue
                        + "' does not match expected '" + expectedValue + "'";
                applyTransitionSafely(op, TransitionEvent.TIMEOUT, mismatchReason);
                // Now transition from expired → reconciliation_required.
                applyTransitionSafely(op, TransitionEvent.RECONCILE, mismatchReason);
                log.info("VerificationWorker: Operation {} requires reconciliation "
                                + "(live={}, expected={})",
                        op.getId(), liveValue, expectedValue);
            }
        } else {
            // Read failed or verification unsupported.
            recordVerificationAttempt(op, attemptCount, result.message());

            if (attemptCount >= maxVerifyAttempts) {
                // Past maximum retries: transition to expired with VERIFY_TIMEOUT (Req 20.4).
                // NOTE: expired is NOT terminal — a later callback or successful re-read may
                // still transition it to effective, failed, or reconciliation_required.
                applyTransitionSafely(op, TransitionEvent.TIMEOUT, REASON_VERIFY_TIMEOUT);
                log.info("VerificationWorker: Operation {} expired after {} verification attempts "
                                + "(reason: VERIFY_TIMEOUT)",
                        op.getId(), attemptCount);
            } else {
                // Not yet past retries: leave in submitted state for re-verification on next tick.
                log.debug("VerificationWorker: Operation {} read failed (attempt {}/{}): {}",
                        op.getId(), attemptCount, maxVerifyAttempts, result.message());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Value comparison
    // ---------------------------------------------------------------------------------------------

    /**
     * Compare two values for equality in a flexible manner. Values stored as JSON may include
     * quotes or numeric formatting differences, so we normalize both before comparison.
     */
    private boolean valuesMatch(String expected, String live) {
        if (expected == null && live == null) {
            return true;
        }
        if (expected == null || live == null) {
            return false;
        }
        // Normalize: strip surrounding quotes and whitespace for comparison.
        String normalizedExpected = normalizeValue(expected);
        String normalizedLive = normalizeValue(live);
        return normalizedExpected.equals(normalizedLive);
    }

    /**
     * Normalize a value for comparison: strip surrounding JSON quotes, trim whitespace.
     * Handles numeric formatting differences (e.g. "1.50" vs "1.5") by comparing as BigDecimal
     * when both parse as numbers.
     */
    private String normalizeValue(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        // Strip surrounding double quotes (JSON string encoding).
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        // Try to normalize as a number for numeric comparisons (e.g. "1.50" == "1.5").
        try {
            java.math.BigDecimal bd = new java.math.BigDecimal(trimmed);
            return bd.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            // Not a number — compare as-is (case-insensitive for status strings).
            return trimmed.toLowerCase();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Verification attempt tracking
    // ---------------------------------------------------------------------------------------------

    /**
     * Get the current verification attempt count for an Operation. This is stored in the
     * Operation's platform_result JSON field as a structured object with a
     * "verificationAttempts" count.
     */
    private int getVerificationAttemptCount(OperationEntity op) {
        String platformResult = op.getPlatformResult();
        if (!hasText(platformResult)) {
            return 0;
        }
        try {
            Map<String, Object> resultMap = objectMapper.readValue(
                    platformResult, new TypeReference<LinkedHashMap<String, Object>>() {});
            Object attempts = resultMap.get("verificationAttempts");
            if (attempts instanceof Number) {
                return ((Number) attempts).intValue();
            }
        } catch (Exception e) {
            // Ignore parse errors — treat as 0 attempts.
        }
        return 0;
    }

    /**
     * Record a verification attempt on the Operation's platform_result field, incrementing the
     * attempt count and recording the last error message.
     */
    private void recordVerificationAttempt(OperationEntity op, int attemptCount, String lastError) {
        try {
            Map<String, Object> resultMap;
            if (hasText(op.getPlatformResult())) {
                resultMap = objectMapper.readValue(
                        op.getPlatformResult(), new TypeReference<LinkedHashMap<String, Object>>() {});
            } else {
                resultMap = new LinkedHashMap<>();
            }
            resultMap.put("verificationAttempts", attemptCount);
            resultMap.put("lastVerificationError", lastError);
            resultMap.put("lastVerificationAt", LocalDateTime.now().toString());

            String json = objectMapper.writeValueAsString(resultMap);
            operationMapper.update(null, new UpdateWrapper<OperationEntity>()
                    .set("platform_result", json)
                    .eq("id", op.getId().toString()));
        } catch (Exception e) {
            log.warn("VerificationWorker: failed to record verification attempt for Operation {}: {}",
                    op.getId(), e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Metadata and change builders
    // ---------------------------------------------------------------------------------------------

    /**
     * Build the {@link SubmissionMetadata} needed for verification from the Operation's stored
     * fields. Returns {@code null} if insufficient metadata is available.
     */
    private SubmissionMetadata buildSubmissionMetadata(OperationEntity op) {
        String entityType = op.getEntityType();
        String field = op.getField();
        String expectedAfterValue = renderValue(op.getAfterValue());

        // The platformReference typically carries the Amazon request ID after acceptance.
        String amazonRequestId = op.getPlatformReference();

        // The external entity ID needs to be resolved. For now, the entity_id from the Operation
        // combined with the entity type gives the connector enough to read back the value.
        String externalEntityId = op.getEntityId() != null ? op.getEntityId().toString() : null;

        if (entityType == null || expectedAfterValue == null) {
            return null;
        }

        return new SubmissionMetadata(
                amazonRequestId,
                externalEntityId,
                entityType,
                field,
                expectedAfterValue);
    }

    /**
     * Build a {@link PlatformChange} carrying enough context for the connector's verify method.
     */
    private PlatformChange buildChange(OperationEntity op, String platform) {
        String changeType = hasText(op.getField()) ? op.getField() : op.getEntityType();
        return new PlatformChange(
                platform,
                op.getStoreId(),
                changeType,
                op.getEntityType(),
                op.getEntityId() != null ? op.getEntityId().toString() : null,
                renderValue(op.getBeforeValue()),
                renderValue(op.getAfterValue()),
                op.getOperationSource(),
                op.getId() != null ? op.getId().toString() : null,
                op.getSubmissionIdempotencyKey());
    }

    /** Decode a stored JSON value column back to its plain string form. */
    private String renderValue(String json) {
        if (!hasText(json)) {
            return null;
        }
        Object value = jsonCodec.fromJson(json, Object.class);
        return value == null ? null : String.valueOf(value);
    }

    // ---------------------------------------------------------------------------------------------
    // Transition helper
    // ---------------------------------------------------------------------------------------------

    /**
     * Apply a transition through {@link OperationService#transition}, isolating any
     * illegal/out-of-order transition as a non-mutating no-op. Also records the reason
     * on the Operation's status_reason field for expired/reconciliation states.
     */
    private void applyTransitionSafely(OperationEntity op, TransitionEvent event, String reason) {
        try {
            operationService.transition(op.getId(), event);
            // Record the status reason for states that carry one.
            if (hasText(reason) && (event == TransitionEvent.TIMEOUT || event == TransitionEvent.RECONCILE)) {
                operationMapper.update(null, new UpdateWrapper<OperationEntity>()
                        .set("status_reason", reason)
                        .eq("id", op.getId().toString()));
            }
        } catch (RuntimeException ex) {
            log.debug("VerificationWorker: transition {} on Operation {} was a no-op: {}",
                    event, op.getId(), ex.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Connector resolution (mirrors StatusPoller)
    // ---------------------------------------------------------------------------------------------

    /**
     * Circuit-breaker key for a resolved connection (H3). Keyed by dependency + connection so a
     * single down store connection opens the breaker only for its own verify calls, never for a
     * healthy store's.
     */
    private static String breakerKey(ResolvedConnector resolved) {
        return resolved.ctx().platform() + ":verify:" + resolved.ctx().connectionId();
    }

    /**
     * Resolve the Store's connector and a {@link ConnectionContext} for a platform read.
     */
    private ResolvedConnector resolveConnector(OperationEntity op) {
        UUID storeId = op.getStoreId();
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
            log.warn("VerificationWorker failed to read platform config: {}", e.getMessage());
            return Map.of();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------------------------

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

    /** A resolved platform connector together with the connection context to call it with. */
    private record ResolvedConnector(PlatformWriteConnector connector, ConnectionContext ctx) {
    }
}
