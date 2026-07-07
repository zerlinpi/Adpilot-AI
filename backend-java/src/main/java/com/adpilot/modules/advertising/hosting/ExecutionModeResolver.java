package com.adpilot.modules.advertising.hosting;

import java.util.UUID;

/**
 * Resolves the effective {@link ExecutionMode} for a campaign (Req 7.2).
 *
 * <p>Resolution follows the inheritance chain: campaign override → goal override →
 * store override. The first level that carries a recognized execution mode wins.
 * When no level defines a mode, the system default {@link ExecutionMode#OBSERVE_ONLY}
 * applies — new stores start safe.
 *
 * <p>Validates: Requirements 7.2, 7.3.</p>
 */
public interface ExecutionModeResolver {

    /**
     * Pure resolution of the effective execution mode from three raw level values,
     * applying the precedence: campaign → goal → store → default (observe_only).
     *
     * @param campaignMode the campaign-level execution mode override (may be {@code null})
     * @param goalMode     the goal-level execution mode override (may be {@code null})
     * @param storeMode    the store-level execution mode override (may be {@code null})
     * @return the effective {@link ExecutionMode}; never {@code null}
     */
    ExecutionMode resolve(String campaignMode, String goalMode, String storeMode);

    /**
     * Resolve the effective execution mode for a campaign by looking up the
     * hosting configs at campaign, goal, and store levels.
     *
     * @param campaignId the campaign id
     * @param goalId     the campaign's goal id (may be {@code null} if unassigned)
     * @param storeId    the store id (required)
     * @return the effective {@link ExecutionMode}; never {@code null}
     */
    ExecutionMode resolveForCampaign(UUID campaignId, UUID goalId, UUID storeId);
}
