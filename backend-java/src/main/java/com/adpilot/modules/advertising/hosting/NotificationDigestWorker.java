package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.utils.RetryBackoff;
import com.adpilot.modules.feishu.service.FeishuService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Scheduled worker that batches non-urgent notifications into a per-store
 * digest and delivers them via FeishuService (Requirements 9.5, 9.7, 30.4).
 *
 * <p>Runs every 30 minutes (configurable via {@code hosting.notification.digest-interval-ms}).
 * Groups all queued notifications by store, builds a consolidated digest message,
 * delivers via Feishu with retry, and clears the queue on success.</p>
 *
 * <p>Key behaviors:</p>
 * <ul>
 *   <li>Batches non-urgent notifications per store into a single Feishu message.</li>
 *   <li>Clears the queue after successful delivery.</li>
 *   <li>On failure, retries up to 3× with exponential backoff.</li>
 *   <li>Records delivery attempts in {@code notification_delivery_log}.</li>
 *   <li>When Feishu is unreachable, leaves notifications queued for next run.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDigestWorker {

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 1000L;

    private final NotificationDeliveryLogMapper deliveryLogMapper;
    private final FeishuService feishuService;
    private final ObjectMapper objectMapper;

    /**
     * Scheduled digest delivery — runs every 30 minutes by default.
     * Configurable via property {@code hosting.notification.digest-interval-ms}.
     */
    @Scheduled(fixedDelayString = "${hosting.notification.digest-interval-ms:1800000}")
    public void processDigest() {
        log.debug("NotificationDigestWorker: starting digest processing");

        List<NotificationDeliveryLogEntity> queued = deliveryLogMapper.findAllQueued();
        if (queued.isEmpty()) {
            log.debug("NotificationDigestWorker: no queued notifications");
            return;
        }

        // Group by store
        Map<UUID, List<NotificationDeliveryLogEntity>> byStore = queued.stream()
                .filter(e -> e.getStoreId() != null)
                .collect(Collectors.groupingBy(NotificationDeliveryLogEntity::getStoreId,
                        LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<UUID, List<NotificationDeliveryLogEntity>> entry : byStore.entrySet()) {
            UUID storeId = entry.getKey();
            List<NotificationDeliveryLogEntity> storeNotifications = entry.getValue();

            // Per-store fault isolation: an unexpected failure while processing one
            // store's digest (e.g. a delivery-log write error) must not abort the
            // remaining stores' digests. Record the failure and continue.
            try {
                processStoreDigest(storeId, storeNotifications);
            } catch (Exception e) {
                log.error("Digest processing failed for storeId={}; continuing with remaining stores: {}",
                        storeId, e.getMessage(), e);
                markStoreDigestFailed(storeId, storeNotifications, e);
            }
        }

        log.debug("NotificationDigestWorker: digest processing complete, processed {} stores",
                byStore.size());
    }

    /**
     * Best-effort record of a store-level digest failure in
     * {@code notification_delivery_log} with status {@code failed}. Each update is
     * itself isolated so a persistence error while recording one failure cannot
     * abort recording the rest (or the outer store loop).
     */
    private void markStoreDigestFailed(UUID storeId, List<NotificationDeliveryLogEntity> notifications,
                                       Exception cause) {
        String reason = "Digest processing error: "
                + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
        for (NotificationDeliveryLogEntity notification : notifications) {
            try {
                deliveryLogMapper.updateDeliveryStatus(
                        notification.getId().toString(), "failed",
                        notification.getAttemptCount() + 1, reason);
            } catch (Exception ex) {
                log.warn("Failed to record digest failure for notification {} (storeId={}): {}",
                        notification.getId(), storeId, ex.getMessage());
            }
        }
    }

    /**
     * Processes the digest for a single store: builds the digest message,
     * delivers via Feishu, and updates statuses.
     */
    void processStoreDigest(UUID storeId, List<NotificationDeliveryLogEntity> notifications) {
        String title = buildDigestTitle(notifications);
        String content = buildDigestContent(notifications);

        boolean delivered = deliverWithRetry(storeId, title, content);

        // Update all notification statuses based on delivery result
        String newStatus = delivered ? "delivered" : "queued";
        for (NotificationDeliveryLogEntity notification : notifications) {
            int attemptCount = notification.getAttemptCount() + 1;
            if (delivered) {
                deliveryLogMapper.updateDeliveryStatus(
                        notification.getId().toString(), "delivered", attemptCount, null);
            } else {
                // Leave queued for next digest cycle; increment attempt count
                deliveryLogMapper.updateDeliveryStatus(
                        notification.getId().toString(), "queued", attemptCount,
                        "Digest delivery failed, will retry next cycle");
            }
        }

        if (delivered) {
            log.info("Digest delivered for storeId={}, notifications={}", storeId, notifications.size());
        } else {
            log.warn("Digest delivery failed for storeId={}, {} notifications remain queued",
                    storeId, notifications.size());
        }
    }

    /**
     * Attempts to deliver a digest message with up to 3 retries and exponential backoff.
     *
     * @return true if delivery succeeded, false otherwise
     */
    private boolean deliverWithRetry(UUID storeId, String title, String content) {
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                boolean dispatched = feishuService.pushAiNotification(storeId, title, content);
                if (dispatched) {
                    return true;
                } else {
                    // No Feishu destination configured — no point retrying
                    log.debug("No Feishu destination for storeId={}, digest skipped", storeId);
                    return false;
                }
            } catch (Exception e) {
                log.warn("Digest delivery attempt {}/{} failed for storeId={}: {}",
                        attempt, MAX_RETRY_ATTEMPTS, storeId, e.getMessage());

                if (attempt < MAX_RETRY_ATTEMPTS) {
                    // Exponential backoff plus bounded jitter so many stores don't
                    // retry in lockstep when Feishu recovers (fix L2).
                    sleep(RetryBackoff.withJitter(BASE_BACKOFF_MS, attempt));
                }
            }
        }
        return false;
    }

    // ─── Digest content builders ──────────────────────────────────────────────

    private String buildDigestTitle(List<NotificationDeliveryLogEntity> notifications) {
        int count = notifications.size();
        return "📋 AI托管通知摘要 (" + count + " 条)";
    }

    String buildDigestContent(List<NotificationDeliveryLogEntity> notifications) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是过去一段时间的AI托管通知汇总:\n\n");

        // Group by notification type for clean formatting
        Map<String, List<NotificationDeliveryLogEntity>> byType = notifications.stream()
                .collect(Collectors.groupingBy(NotificationDeliveryLogEntity::getNotificationType,
                        LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<NotificationDeliveryLogEntity>> typeEntry : byType.entrySet()) {
            String type = typeEntry.getKey();
            List<NotificationDeliveryLogEntity> items = typeEntry.getValue();

            sb.append(getTypeLabel(type)).append(" (").append(items.size()).append(")\n");

            for (NotificationDeliveryLogEntity item : items) {
                String summary = extractSummaryFromPayload(item.getPayload(), type);
                sb.append("  • ").append(summary).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    private String getTypeLabel(String type) {
        return switch (type) {
            case "approval_needed" -> "🔔 需要审批";
            case "effective_confirmed" -> "✅ 已生效";
            case "failed" -> "❌ 执行失败";
            case "emergency" -> "🚨 紧急通知";
            case "data_gap" -> "⚠️ 数据缺口";
            default -> "📌 " + type;
        };
    }

    private String extractSummaryFromPayload(String payloadJson, String type) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return "(详情不可用)";
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson,
                    new TypeReference<Map<String, Object>>() {});

            return switch (type) {
                case "approval_needed" -> {
                    String campaign = getStr(payload, "campaignName");
                    String change = getStr(payload, "proposedChange");
                    yield campaign + " - " + change;
                }
                case "effective_confirmed" -> {
                    String campaign = getStr(payload, "campaignName");
                    String change = getStr(payload, "changeDescription");
                    yield campaign + " - " + change;
                }
                case "failed" -> {
                    String campaign = getStr(payload, "campaignName");
                    String reason = getStr(payload, "failureReason");
                    yield campaign + " - " + reason;
                }
                case "data_gap" -> {
                    String reportType = getStr(payload, "reportType");
                    Object gapDays = payload.get("gapDays");
                    yield reportType + " 缺口 " + gapDays + " 天";
                }
                default -> payloadJson.length() > 100 ? payloadJson.substring(0, 100) + "..." : payloadJson;
            };
        } catch (JsonProcessingException e) {
            return "(解析失败)";
        }
    }

    private String getStr(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : "(未知)";
    }

    /**
     * Sleep for backoff — extracted for testability.
     */
    void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
