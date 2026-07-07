package com.adpilot.modules.alert.service;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.modules.alert.model.AlertCondition;
import com.adpilot.modules.alert.vo.AlertVo;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Unified alert engine (Req 10.1). Generates stockout, ACoS-over-threshold,
 * BuyBox-loss, and negative-review alerts; deduplicates to a single open alert per
 * (store, type, subject); resolves alerts when their condition clears; pushes new
 * alerts to Feishu where configured (recording failures without losing the alert);
 * and exposes alerts only to users permitted to access the affected store.
 */
public interface AlertEngine {

    /**
     * Evaluate a single business condition and reconcile it with the alert center
     * (Req 10.1.7 / 10.1.8).
     *
     * <ul>
     *   <li>If the condition is active and no open alert exists, create one and
     *       attempt a Feishu push (Req 10.1.1&ndash;10.1.4, 10.1.6).</li>
     *   <li>If the condition is active and an open alert already exists, update that
     *       alert in place rather than creating a duplicate (Req 10.1.7).</li>
     *   <li>If the condition is no longer active, mark any existing open alert as
     *       resolved (Req 10.1.8).</li>
     * </ul>
     *
     * @return the open alert after evaluation, or empty when the condition is
     *         inactive (there is no open alert to return)
     */
    Optional<AlertVo> evaluate(AlertCondition condition);

    /**
     * Evaluate a product's inventory against its stockout threshold (Req 10.1.1).
     * An alert is active while {@code available <= threshold}.
     */
    Optional<AlertVo> evaluateStockout(UUID storeId, String productId, Integer available, Integer threshold);

    /**
     * Evaluate a campaign's ACoS against its threshold (Req 10.1.2).
     * An alert is active while {@code acos > threshold}.
     */
    Optional<AlertVo> evaluateAcos(UUID storeId, String campaignId, BigDecimal acos, BigDecimal threshold);

    /**
     * Evaluate BuyBox ownership for a product (Req 10.1.3).
     * An alert is active while the BuyBox is lost.
     */
    Optional<AlertVo> evaluateBuyBoxLoss(UUID storeId, String productId, boolean buyBoxLost);

    /**
     * Evaluate a review for a product (Req 10.1.4).
     * An alert is active while {@code rating <= negativeRatingThreshold}.
     */
    Optional<AlertVo> evaluateNegativeReview(UUID storeId, String productId, Integer rating,
                                             Integer negativeRatingThreshold);

    /**
     * List alerts the given user is permitted to see, scoped to the stores they may
     * access (Req 10.1.5). A {@code null} status returns all statuses.
     */
    java.util.List<AlertVo> listForUser(CurrentUser user, String status);
}
