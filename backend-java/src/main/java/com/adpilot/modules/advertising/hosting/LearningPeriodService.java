package com.adpilot.modules.advertising.hosting;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Service for tracking and enforcing the learning period per campaign (Requirement 19).
 *
 * <p>The learning period is a safety window during which the AI is conservative:
 * <ul>
 *   <li>Bid changes are capped at 10% magnitude regardless of personality (Req 19.2)</li>
 *   <li>No budget changes are proposed (Req 19.2)</li>
 *   <li>The period restarts when the campaign's personality changes (Req 19.4)</li>
 *   <li>Days remaining is exposed for the dashboard (Req 19.5)</li>
 * </ul>
 *
 * <p>The learning period starts when:
 * <ul>
 *   <li>A campaign is first hosted (AI_Hosting_Status changes to hosted) (Req 19.1)</li>
 *   <li>A campaign's personality changes (Req 19.4)</li>
 * </ul>
 *
 * <p>Duration is resolved from the {@code learningPeriodDays} safety boundary value
 * (default 3 days per Req 19.1).
 */
public interface LearningPeriodService {

    /** Default learning period duration when no safety boundary value is configured. */
    int DEFAULT_LEARNING_PERIOD_DAYS = 3;

    /** Maximum bid change ratio during the learning period (10% = 0.10). */
    BigDecimal LEARNING_PERIOD_MAX_BID_CHANGE_RATIO = new BigDecimal("0.10");

    /** Skip reason used when budget changes are blocked during learning period. */
    String SKIP_REASON_LEARNING_PERIOD = "LEARNING_PERIOD";

    /**
     * Get the learning period status for a campaign.
     *
     * @param campaignId the campaign id
     * @return the current learning-period status; never null
     */
    LearningPeriodStatus getStatus(UUID campaignId);

    /**
     * Check whether a campaign is currently in its learning period.
     *
     * @param campaignId the campaign id
     * @return true if the campaign is within its learning period
     */
    boolean isInLearningPeriod(UUID campaignId);

    /**
     * Start or restart the learning period for a campaign.
     *
     * <p>Called when:
     * <ul>
     *   <li>A campaign's hosting is first enabled (Req 19.1)</li>
     *   <li>A campaign's personality changes (Req 19.4)</li>
     * </ul>
     *
     * @param campaignId       the campaign id
     * @param storeId          the store id (for boundary resolution context)
     * @param currentPersonality the personality active at the start of the period
     */
    void startOrRestart(UUID campaignId, UUID storeId, String currentPersonality);

    /**
     * Start or restart the learning period for a campaign with an explicit duration.
     *
     * @param campaignId         the campaign id
     * @param storeId            the store id
     * @param currentPersonality the personality active at the start of the period
     * @param learningPeriodDays the duration from the resolved safety boundary
     */
    void startOrRestart(UUID campaignId, UUID storeId, String currentPersonality, int learningPeriodDays);

    /**
     * Check if the campaign's personality has changed since the learning period started,
     * and if so, restart the period with the new personality.
     *
     * @param campaignId         the campaign id
     * @param storeId            the store id
     * @param currentPersonality the campaign's current effective personality
     * @return true if the period was restarted due to a personality change
     */
    boolean checkAndRestartOnPersonalityChange(UUID campaignId, UUID storeId, String currentPersonality);

    /**
     * Apply learning-period bid clamping to a proposed bid change.
     *
     * <p>During the learning period, the bid change magnitude is capped at 10%
     * regardless of the personality-allowed ratio or safety boundary's
     * maxBidAdjustmentRatio (Req 19.2).
     *
     * @param currentBid  the current bid value
     * @param proposedBid the proposed (already clamped by engine) bid value
     * @return the bid clamped to at most 10% change from currentBid; or proposedBid if not in period
     */
    BigDecimal clampBidForLearningPeriod(BigDecimal currentBid, BigDecimal proposedBid);

    /**
     * Determine whether budget changes should be blocked for a campaign.
     *
     * <p>Returns true if the campaign is in its learning period, meaning
     * the V2 budget engine should skip this campaign entirely (Req 19.2).
     *
     * @param campaignId the campaign id
     * @return true if budget changes should be blocked
     */
    boolean shouldBlockBudgetChanges(UUID campaignId);

    /**
     * Get the learning period status for multiple campaigns (batch query for dashboard).
     *
     * @param campaignIds the campaign ids to check
     * @return list of statuses, one per campaign (in the same order)
     */
    List<LearningPeriodStatus> getStatuses(List<UUID> campaignIds);
}
