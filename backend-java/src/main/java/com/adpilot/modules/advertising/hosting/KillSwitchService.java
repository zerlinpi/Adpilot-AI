package com.adpilot.modules.advertising.hosting;

import java.util.UUID;

/**
 * Service interface for the AI Hosting Kill Switch (Requirement 35.3).
 *
 * <p>Kill switches can be activated at four levels: system, organization, store,
 * and campaign. When activated at any level, the kill switch immediately stops
 * new decision generation and submission for all scoped entities.</p>
 *
 * <p>On activation:</p>
 * <ol>
 *   <li>Cancel all {@code awaiting_approval} Operations in scope.</li>
 *   <li>Supersede/cancel all {@code pending} Operations in scope and close their
 *       Outbox rows.</li>
 *   <li>Close any linked open {@code approval_requests}.</li>
 *   <li>For already submitted/in-flight Operations, route through platform cancel
 *       or status reconciliation (not force-cancelled locally).</li>
 * </ol>
 *
 * <p>The Outbox and engines re-check the kill switch immediately before submitting
 * any Operation.</p>
 *
 * <p>Validates: Requirements 35.1, 35.2, 35.3, 35.4, 35.5, 35.6, 35.7, 35.8, 40.6.</p>
 */
public interface KillSwitchService {

    /**
     * Activate a kill switch at the specified scope.
     *
     * <p>Immediately cancels awaiting_approval Operations, supersedes/cancels pending
     * Operations, closes their Outbox rows, and closes linked approval requests.</p>
     *
     * @param scope    the scope level: "system", "organization", "store", or "campaign"
     * @param scopeId  the ID of the scoped entity (null for system scope)
     * @param reason   human-readable reason for activation
     * @param actorId  the user activating the kill switch
     */
    void activate(String scope, UUID scopeId, String reason, UUID actorId);

    /**
     * Deactivate a kill switch at the specified scope.
     *
     * @param scope    the scope level: "system", "organization", "store", or "campaign"
     * @param scopeId  the ID of the scoped entity (null for system scope)
     * @param actorId  the user deactivating the kill switch
     */
    void deactivate(String scope, UUID scopeId, UUID actorId);

    /**
     * Check if a kill switch is active at the specified scope level.
     *
     * @param scope    the scope level to check
     * @param scopeId  the scoped entity ID (null for system)
     * @return {@code true} if a kill switch is active at that exact level
     */
    boolean isActive(String scope, UUID scopeId);

    /**
     * Check if any kill switch is active for a given campaign, checking all
     * applicable levels in the hierarchy: system → organization → store → campaign.
     *
     * @param storeId    the store ID (used to resolve organization)
     * @param campaignId the campaign ID to check
     * @return {@code true} if any kill switch in the hierarchy is active
     */
    boolean isActiveForCampaign(UUID storeId, UUID campaignId);
}
