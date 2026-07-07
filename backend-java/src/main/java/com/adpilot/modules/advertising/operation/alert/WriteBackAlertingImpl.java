package com.adpilot.modules.advertising.operation.alert;

import com.adpilot.modules.advertising.entity.AiNotificationEntity;
import com.adpilot.modules.advertising.mapper.AiNotificationMapper;
import com.adpilot.modules.advertising.support.AiNotificationDedup;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link WriteBackAlerting}.
 *
 * <p>Keeps a bounded, in-memory rolling view of recent write-back attempts per Store +
 * Write_Connector and, on every recorded outcome, evaluates the configured thresholds through the
 * pure {@link WriteBackFailureEvaluator} (Req 51.7). The notification it produces/closes obeys the
 * dedupe/lifecycle contract of Requirement 23.2/23.3:</p>
 *
 * <ul>
 *   <li><b>Dedupe (Req 23.2).</b> A write-back alert maps to an {@code ai_notifications} row in the
 *       {@code core_ops} category with {@code subject_id = "writeback:" + connector}. Its dedupe key
 *       is therefore {@code store:core_ops:writeback:connector}. A new notification is produced only
 *       when no open (pending) notification already shares that key, mirroring the database's
 *       {@code open_dedup_key} unique index ({@link AiNotificationDedup}).</li>
 *   <li><b>Lifecycle (Req 23.3).</b> When the underlying condition resolves — the windowed failure
 *       rate falls below threshold and the consecutive-failure streak is broken — the open
 *       notification is closed (resolution {@code dismissed}, {@code closed_at} set), dropping it out
 *       of the open-key uniqueness so a future recurrence can raise a fresh notification.</li>
 * </ul>
 *
 * <p>The notification reads/writes run in a {@link Propagation#REQUIRES_NEW} transaction and are
 * wrapped so an alerting-side failure can never roll back or break the Operation transition that
 * reported the outcome — alerting is strictly a side effect of write-back, not a precondition.</p>
 *
 * <p>All thresholds are configurable backend values (Req 51.11) under
 * {@code adpilot.advertising.writeback-alert.*}.</p>
 *
 * <p>Validates: Requirements 23.2, 23.3, 51.7, 51.11.</p>
 */
@Slf4j
@Service
public class WriteBackAlertingImpl implements WriteBackAlerting {

    /** The four-category vocabulary places an operational write-back alert under core-ops attention. */
    private static final String CATEGORY = "core_ops";

    /** Subject-id prefix so the dedupe key is distinct from other core-ops notifications. */
    private static final String SUBJECT_PREFIX = "writeback:";

    private static final String STATE_PENDING = "pending";
    private static final String STATE_CLOSED = "closed";
    /** Auto-resolution disposition used when alerting closes a stale notification (Req 23.3). */
    private static final String RESOLUTION_DISMISSED = "dismissed";

    private final AiNotificationMapper notificationMapper;
    private final ObjectMapper objectMapper;

    private final WriteBackAlertThresholds thresholds;
    /** Upper bound on retained attempts per Store + Write_Connector, keeping memory bounded. */
    private final int maxTrackedAttempts;

    /** Store|connector -> recent attempts (bounded). Guarded per-deque on mutation. */
    private final Map<String, Deque<WriteBackAttempt>> attemptsByKey = new ConcurrentHashMap<>();

    public WriteBackAlertingImpl(
            AiNotificationMapper notificationMapper,
            ObjectMapper objectMapper,
            @Value("${adpilot.advertising.writeback-alert.window-minutes:15}") long windowMinutes,
            @Value("${adpilot.advertising.writeback-alert.min-sample:10}") int minSample,
            @Value("${adpilot.advertising.writeback-alert.failure-rate-threshold:0.25}") double failureRateThreshold,
            @Value("${adpilot.advertising.writeback-alert.consecutive-failure-threshold:5}") int consecutiveFailureThreshold,
            @Value("${adpilot.advertising.writeback-alert.max-tracked-attempts:1000}") int maxTrackedAttempts) {
        this.notificationMapper = notificationMapper;
        this.objectMapper = objectMapper;
        this.thresholds = new WriteBackAlertThresholds(
                Duration.ofMinutes(windowMinutes > 0 ? windowMinutes : 15),
                Math.max(0, minSample),
                clampRate(failureRateThreshold),
                Math.max(0, consecutiveFailureThreshold));
        this.maxTrackedAttempts = Math.max(consecutiveFailureThreshold + 1, Math.max(1, maxTrackedAttempts));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WriteBackAlertDecision recordOutcome(UUID storeId, String connector, boolean success) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId must not be null");
        }
        if (connector == null || connector.isBlank()) {
            throw new IllegalArgumentException("connector must not be null or blank");
        }

        List<WriteBackAttempt> snapshot = appendAttempt(key(storeId, connector),
                new WriteBackAttempt(Instant.now(), success));
        WriteBackAlertDecision decision = WriteBackFailureEvaluator.evaluate(snapshot, Instant.now(), thresholds);

        try {
            if (decision.shouldAlert()) {
                raiseIfAbsent(storeId, connector, decision);
            } else {
                closeStaleIfPresent(storeId, connector);
            }
        } catch (RuntimeException e) {
            // Alerting is a side effect of write-back; never let it break the reporting caller.
            log.warn("Write-back alerting for store {} connector {} failed: {}", storeId, connector, e.getMessage());
        }
        return decision;
    }

    @Override
    public WriteBackAlertDecision currentDecision(UUID storeId, String connector) {
        Deque<WriteBackAttempt> deque = attemptsByKey.get(key(storeId, connector));
        List<WriteBackAttempt> snapshot;
        if (deque == null) {
            snapshot = List.of();
        } else {
            synchronized (deque) {
                snapshot = new ArrayList<>(deque);
            }
        }
        return WriteBackFailureEvaluator.evaluate(snapshot, Instant.now(), thresholds);
    }

    // ---------------------------------------------------------------------------------------------
    // In-memory rolling tracker
    // ---------------------------------------------------------------------------------------------

    private List<WriteBackAttempt> appendAttempt(String key, WriteBackAttempt attempt) {
        Deque<WriteBackAttempt> deque = attemptsByKey.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(attempt);
            // Bound memory: drop the oldest attempts beyond the retention cap. Trailing (recent)
            // failures that drive the consecutive-failure rule are never dropped.
            while (deque.size() > maxTrackedAttempts) {
                deque.removeFirst();
            }
            return new ArrayList<>(deque);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Notification dedupe + lifecycle (Req 23.2, 23.3)
    // ---------------------------------------------------------------------------------------------

    /**
     * Raise a write-back failure notification only when no open notification shares its dedupe key
     * (Req 23.2). The lookup mirrors the database's {@code open_dedup_key} uniqueness on
     * {@code (store_id, category, subject_id)} while {@code state = 'pending'}.
     */
    private void raiseIfAbsent(UUID storeId, String connector, WriteBackAlertDecision decision) {
        String subjectId = subjectId(connector);
        AiNotificationEntity existing = findOpen(storeId, subjectId);
        if (existing != null) {
            // A notification for this dedupe key is already open — fold this re-trip into it (Req 23.2).
            log.debug("Write-back alert already open for store {} connector {} (dedupKey={})",
                    storeId, connector, AiNotificationDedup.dedupKeyFor(storeId.toString(), CATEGORY, subjectId));
            return;
        }
        AiNotificationEntity notification = AiNotificationEntity.builder()
                .storeId(storeId)
                .category(CATEGORY)
                .title("广告写回失败告警：" + connector)
                .subjectId(subjectId)
                .state(STATE_PENDING)
                .detailJson(buildDetail(connector, decision))
                .build();
        notificationMapper.insert(notification);
        log.warn("Raised write-back failure alert for store {} connector {}: {}",
                storeId, connector, decision.reason());
    }

    /**
     * Close the open write-back notification for this dedupe key when the underlying condition has
     * resolved (Req 23.3). A no-op when nothing is open.
     */
    private void closeStaleIfPresent(UUID storeId, String connector) {
        String subjectId = subjectId(connector);
        AiNotificationEntity open = findOpen(storeId, subjectId);
        if (open == null) {
            return;
        }
        UpdateWrapper<AiNotificationEntity> update = new UpdateWrapper<AiNotificationEntity>()
                .set("state", STATE_CLOSED)
                .set("resolution", RESOLUTION_DISMISSED)
                .set("closed_at", LocalDateTime.now())
                .eq("id", open.getId().toString())
                .eq("state", STATE_PENDING);
        notificationMapper.update(null, update);
        log.info("Closed stale write-back failure alert for store {} connector {} (condition resolved)",
                storeId, connector);
    }

    /** The single open (pending) notification for the dedupe key, or {@code null}. */
    private AiNotificationEntity findOpen(UUID storeId, String subjectId) {
        QueryWrapper<AiNotificationEntity> wrapper = new QueryWrapper<AiNotificationEntity>()
                .eq("store_id", storeId.toString())
                .eq("category", CATEGORY)
                .eq("subject_id", subjectId)
                .eq("state", STATE_PENDING)
                .last("LIMIT 1");
        return notificationMapper.selectOne(wrapper);
    }

    private String buildDetail(String connector, WriteBackAlertDecision decision) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", "write_back_failure");
        detail.put("connector", connector);
        detail.put("reason", decision.reason());
        detail.put("failureRateBreached", decision.failureRateBreached());
        detail.put("consecutiveFailuresBreached", decision.consecutiveFailuresBreached());
        detail.put("windowedAttempts", decision.windowedAttempts());
        detail.put("windowedFailures", decision.windowedFailures());
        detail.put("windowedFailureRate", decision.windowedFailureRate());
        detail.put("trailingConsecutiveFailures", decision.trailingConsecutiveFailures());
        detail.put("windowMinutes", thresholds.window().toMinutes());
        detail.put("minSample", thresholds.minSample());
        detail.put("failureRateThreshold", thresholds.failureRateThreshold());
        detail.put("consecutiveFailureThreshold", thresholds.consecutiveFailureThreshold());
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            log.warn("Failed to serialize write-back alert detail: {}", e.getMessage());
            return null;
        }
    }

    private static String subjectId(String connector) {
        return SUBJECT_PREFIX + connector;
    }

    private static String key(UUID storeId, String connector) {
        return storeId + "|" + connector;
    }

    private static double clampRate(double rate) {
        if (rate < 0.0d) {
            return 0.0d;
        }
        return Math.min(rate, 1.0d);
    }
}
