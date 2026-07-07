package com.adpilot.modules.advertising.hosting;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Hosting notification service that builds typed notifications and resolves
 * delivery via {@code FeishuService} (Requirements 9.1–9.7, 30.4).
 *
 * <p>Notification types:</p>
 * <ul>
 *   <li><b>approval-needed</b>: An AI decision requires human approval.</li>
 *   <li><b>effective-confirmed</b>: An operation has been verified effective on Amazon.</li>
 *   <li><b>failed</b>: An operation failed on Amazon.</li>
 *   <li><b>emergency</b>: Emergency stop triggered — sent immediately.</li>
 *   <li><b>data-gap</b>: Data gap detected in report coverage.</li>
 * </ul>
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>Emergency notifications are sent immediately via FeishuService.</li>
 *   <li>Non-urgent notifications are queued for digest batching (default 30 min).</li>
 *   <li>Delivery failures retry up to 3× with exponential backoff.</li>
 *   <li>All delivery attempts are recorded in {@code notification_delivery_log}.</li>
 *   <li>When Feishu is unreachable, notifications queue without blocking (Req 30.4).</li>
 * </ul>
 */
public interface HostingNotificationService {

    /**
     * Notify that an AI decision requires human approval (Req 9.1).
     *
     * @param storeId      the store that owns the campaign
     * @param campaignName the campaign name
     * @param decisionType the type of decision (e.g., "bid_adjustment", "budget_change")
     * @param proposedChange description of the proposed change
     * @param riskScore    the computed risk score (0.0–1.0)
     * @param approvalLink deep link to the approval page
     */
    void notifyApprovalNeeded(UUID storeId, String campaignName, String decisionType,
                              String proposedChange, BigDecimal riskScore, String approvalLink);

    /**
     * Notify that an operation has been verified effective (Req 9.2).
     *
     * @param storeId       the store that owns the campaign
     * @param campaignName  the campaign name
     * @param changeDescription description of the change applied
     * @param platformResult the confirmed platform result
     */
    void notifyEffective(UUID storeId, String campaignName, String changeDescription,
                         String platformResult);

    /**
     * Notify that an operation has failed (Req 9.3).
     *
     * @param storeId         the store that owns the campaign
     * @param campaignName    the campaign name
     * @param changeDescription description of the attempted change
     * @param failureReason   the reason for failure
     * @param willRetry       whether automatic retry will occur
     */
    void notifyFailed(UUID storeId, String campaignName, String changeDescription,
                      String failureReason, boolean willRetry);

    /**
     * Notify that an emergency stop has been triggered — sent immediately (Req 9.4).
     *
     * @param storeId          the store that owns the campaign
     * @param campaignName     the campaign name
     * @param triggeringMetric the metric that triggered the emergency (e.g., "ACoS", "daily_spend")
     * @param thresholdBreached description of the threshold breached
     * @param actionsTaken     actions taken by the system
     */
    void notifyEmergency(UUID storeId, String campaignName, String triggeringMetric,
                         String thresholdBreached, String actionsTaken);

    /**
     * Notify that a data gap has been detected in report coverage (Req 2.8, 3.6).
     *
     * @param storeId     the store affected
     * @param gapDays     the number of days of the gap
     * @param reportType  the report type with the gap
     * @param details     additional details about the gap
     */
    void notifyDataGap(UUID storeId, int gapDays, String reportType, String details);
}
