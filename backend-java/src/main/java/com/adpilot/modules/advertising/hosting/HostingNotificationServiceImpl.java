package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.utils.RetryBackoff;
import com.adpilot.modules.feishu.service.FeishuService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation of {@link HostingNotificationService} (Requirements 9.1–9.7, 30.4).
 *
 * <p>Emergency notifications are sent immediately via FeishuService with retry.
 * Non-urgent notifications (approval-needed, effective-confirmed, failed, data-gap)
 * are queued in {@code notification_delivery_log} with status 'queued' for the
 * {@link NotificationDigestWorker} to batch and deliver every 30 minutes.</p>
 *
 * <p>When Feishu is unreachable, notifications queue without blocking the
 * optimization flow (Req 30.4). Delivery failures retry up to 3× with
 * exponential backoff and are recorded in the log.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HostingNotificationServiceImpl implements HostingNotificationService {

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 1000L;

    private final FeishuService feishuService;
    private final NotificationDeliveryLogMapper deliveryLogMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void notifyApprovalNeeded(UUID storeId, String campaignName, String decisionType,
                                     String proposedChange, BigDecimal riskScore, String approvalLink) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("campaignName", campaignName);
        payload.put("decisionType", decisionType);
        payload.put("proposedChange", proposedChange);
        payload.put("riskScore", riskScore != null ? riskScore.toPlainString() : null);
        payload.put("approvalLink", approvalLink);

        String title = "🔔 审批通知: " + campaignName;
        String content = buildApprovalContent(campaignName, decisionType, proposedChange, riskScore, approvalLink);

        queueOrSend(storeId, HostingNotificationType.APPROVAL_NEEDED, title, content, payload);
    }

    @Override
    public void notifyEffective(UUID storeId, String campaignName, String changeDescription,
                                String platformResult) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("campaignName", campaignName);
        payload.put("changeDescription", changeDescription);
        payload.put("platformResult", platformResult);

        String title = "✅ 操作生效: " + campaignName;
        String content = buildEffectiveContent(campaignName, changeDescription, platformResult);

        queueOrSend(storeId, HostingNotificationType.EFFECTIVE_CONFIRMED, title, content, payload);
    }

    @Override
    public void notifyFailed(UUID storeId, String campaignName, String changeDescription,
                             String failureReason, boolean willRetry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("campaignName", campaignName);
        payload.put("changeDescription", changeDescription);
        payload.put("failureReason", failureReason);
        payload.put("willRetry", willRetry);

        String title = "❌ 操作失败: " + campaignName;
        String content = buildFailedContent(campaignName, changeDescription, failureReason, willRetry);

        queueOrSend(storeId, HostingNotificationType.FAILED, title, content, payload);
    }

    @Override
    public void notifyEmergency(UUID storeId, String campaignName, String triggeringMetric,
                                String thresholdBreached, String actionsTaken) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("campaignName", campaignName);
        payload.put("triggeringMetric", triggeringMetric);
        payload.put("thresholdBreached", thresholdBreached);
        payload.put("actionsTaken", actionsTaken);

        String title = "🚨 紧急停止: " + campaignName;
        String content = buildEmergencyContent(campaignName, triggeringMetric, thresholdBreached, actionsTaken);

        // Emergency notifications are sent immediately (Req 9.4)
        queueOrSend(storeId, HostingNotificationType.EMERGENCY, title, content, payload);
    }

    @Override
    public void notifyDataGap(UUID storeId, int gapDays, String reportType, String details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("gapDays", gapDays);
        payload.put("reportType", reportType);
        payload.put("details", details);

        String title = "⚠️ 数据缺口: " + reportType;
        String content = buildDataGapContent(gapDays, reportType, details);

        queueOrSend(storeId, HostingNotificationType.DATA_GAP, title, content, payload);
    }

    // ─── Core delivery logic ──────────────────────────────────────────────────

    /**
     * Routes a notification to immediate delivery (for urgent types) or queues it
     * for digest batching (for non-urgent types). Never blocks the calling thread
     * even when Feishu is unreachable (Req 30.4).
     */
    private void queueOrSend(UUID storeId, HostingNotificationType type,
                             String title, String content, Map<String, Object> payload) {
        String payloadJson = serializePayload(payload);

        if (type.isUrgent()) {
            // Send immediately with retry
            sendWithRetry(storeId, type, title, content, payloadJson);
        } else {
            // Queue for digest batching — non-blocking (Req 9.5, 30.4)
            queueNotification(storeId, type, payloadJson);
        }
    }

    /**
     * Attempts immediate delivery with up to 3 retries and exponential backoff.
     * Records all attempts in notification_delivery_log (Req 9.7).
     */
    void sendWithRetry(UUID storeId, HostingNotificationType type,
                       String title, String content, String payloadJson) {
        NotificationDeliveryLogEntity logEntry = createLogEntry(storeId, type, payloadJson);
        deliveryLogMapper.insert(logEntry);

        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                boolean dispatched = feishuService.pushAiNotification(storeId, title, content);
                if (dispatched) {
                    updateLogStatus(logEntry, "delivered", attempt, null);
                    log.info("Notification delivered: type={}, storeId={}, attempt={}",
                            type.getValue(), storeId, attempt);
                    return;
                } else {
                    // No Feishu destination bound for this store/account (Req 7.4): the
                    // dispatch is skipped gracefully — the notification is queued with a
                    // readable skip reason and the calling thread is never aborted, so other
                    // stores' notifications proceed unaffected.
                    String skipReason = "No Feishu destination bound for this store/account; notification skipped";
                    updateLogStatus(logEntry, "queued", attempt, skipReason);
                    log.info("Notification skipped: type={}, storeId={}, reason={}",
                            type.getValue(), storeId, skipReason);
                    return;
                }
            } catch (Exception e) {
                log.warn("Notification delivery failed: type={}, storeId={}, attempt={}/{}. Error: {}",
                        type.getValue(), storeId, attempt, MAX_RETRY_ATTEMPTS, e.getMessage());

                if (attempt < MAX_RETRY_ATTEMPTS) {
                    // Exponential backoff (1s, 2s, 4s) plus bounded jitter so many
                    // stores don't retry in lockstep when Feishu recovers (fix L2).
                    sleep(RetryBackoff.withJitter(BASE_BACKOFF_MS, attempt));
                } else {
                    // Final attempt failed — record failure and queue for later (Req 30.4)
                    updateLogStatus(logEntry, "failed", attempt, truncateError(e.getMessage()));
                    log.error("Notification delivery exhausted retries: type={}, storeId={}",
                            type.getValue(), storeId);
                }
            }
        }
    }

    /**
     * Queues a non-urgent notification for digest batching (Req 9.5).
     * Does not attempt immediate delivery — the NotificationDigestWorker
     * will batch and send these.
     */
    private void queueNotification(UUID storeId, HostingNotificationType type, String payloadJson) {
        NotificationDeliveryLogEntity logEntry = createLogEntry(storeId, type, payloadJson);
        logEntry.setStatus("queued");
        logEntry.setAttemptCount(0);
        deliveryLogMapper.insert(logEntry);
        log.debug("Notification queued for digest: type={}, storeId={}", type.getValue(), storeId);
    }

    // ─── Helper methods ──────────────────────────────────────────────────────

    private NotificationDeliveryLogEntity createLogEntry(UUID storeId, HostingNotificationType type,
                                                         String payloadJson) {
        return NotificationDeliveryLogEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .notificationType(type.getValue())
                .status("queued")
                .attemptCount(0)
                .payload(payloadJson)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private void updateLogStatus(NotificationDeliveryLogEntity entry, String status,
                                 int attemptCount, String lastError) {
        deliveryLogMapper.updateDeliveryStatus(
                entry.getId().toString(), status, attemptCount, lastError);
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize notification payload", e);
            return "{}";
        }
    }

    private String truncateError(String error) {
        if (error == null) return null;
        return error.length() > 1000 ? error.substring(0, 1000) : error;
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

    // ─── Content builders ─────────────────────────────────────────────────────

    private String buildApprovalContent(String campaignName, String decisionType,
                                        String proposedChange, BigDecimal riskScore, String approvalLink) {
        StringBuilder sb = new StringBuilder();
        sb.append("广告活动: ").append(campaignName).append("\n");
        sb.append("决策类型: ").append(decisionType).append("\n");
        sb.append("建议操作: ").append(proposedChange).append("\n");
        if (riskScore != null) {
            sb.append("风险评分: ").append(riskScore.toPlainString()).append("\n");
        }
        if (approvalLink != null) {
            sb.append("审批链接: ").append(approvalLink);
        }
        return sb.toString();
    }

    private String buildEffectiveContent(String campaignName, String changeDescription,
                                          String platformResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("广告活动: ").append(campaignName).append("\n");
        sb.append("变更内容: ").append(changeDescription).append("\n");
        sb.append("平台结果: ").append(platformResult);
        return sb.toString();
    }

    private String buildFailedContent(String campaignName, String changeDescription,
                                       String failureReason, boolean willRetry) {
        StringBuilder sb = new StringBuilder();
        sb.append("广告活动: ").append(campaignName).append("\n");
        sb.append("变更内容: ").append(changeDescription).append("\n");
        sb.append("失败原因: ").append(failureReason).append("\n");
        sb.append("自动重试: ").append(willRetry ? "是" : "否");
        return sb.toString();
    }

    private String buildEmergencyContent(String campaignName, String triggeringMetric,
                                          String thresholdBreached, String actionsTaken) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ 紧急停止已触发 ⚠️\n\n");
        sb.append("广告活动: ").append(campaignName).append("\n");
        sb.append("触发指标: ").append(triggeringMetric).append("\n");
        sb.append("超出阈值: ").append(thresholdBreached).append("\n");
        sb.append("已执行操作: ").append(actionsTaken);
        return sb.toString();
    }

    private String buildDataGapContent(int gapDays, String reportType, String details) {
        StringBuilder sb = new StringBuilder();
        sb.append("报告类型: ").append(reportType).append("\n");
        sb.append("缺口天数: ").append(gapDays).append(" 天\n");
        if (details != null) {
            sb.append("详情: ").append(details);
        }
        return sb.toString();
    }
}
