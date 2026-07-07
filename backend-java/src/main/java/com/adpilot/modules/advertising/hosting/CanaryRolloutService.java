package com.adpilot.modules.advertising.hosting;

import java.util.Set;
import java.util.UUID;

/**
 * Service interface for Canary Rollout (Requirement 35.2).
 *
 * <p>Canary rollout enables hosting for a designated subset of stores before
 * org-wide enablement. A store must be in the canary set to have its decisions
 * executed (in addition to passing all other governance checks).</p>
 *
 * <p>When canary mode is NOT enabled at the organization level, all stores
 * operate normally (canary is not a gate). When canary mode IS enabled,
 * only stores in the canary set proceed to execution.</p>
 *
 * <p>Validates: Requirements 35.2, 35.8.</p>
 */
public interface CanaryRolloutService {

    /**
     * Check if canary rollout is enabled for an organization.
     *
     * @param orgId the organization ID
     * @return {@code true} if canary mode is active for the org
     */
    boolean isCanaryEnabled(UUID orgId);

    /**
     * Check if a specific store is included in the canary set.
     *
     * <p>If canary is not enabled for the org, this returns {@code true}
     * (all stores are considered in the rollout).</p>
     *
     * @param orgId   the organization ID
     * @param storeId the store to check
     * @return {@code true} if the store is in the canary set or canary is not enabled
     */
    boolean isStoreInCanary(UUID orgId, UUID storeId);

    /**
     * Get the set of store IDs currently in the canary rollout for an organization.
     *
     * @param orgId the organization ID
     * @return the set of store IDs in the canary, or empty if canary is not enabled
     */
    Set<UUID> getCanaryStores(UUID orgId);

    /**
     * Add a store to the canary rollout set.
     *
     * @param orgId   the organization ID
     * @param storeId the store to add
     * @param actorId the user performing the action
     */
    void addStoreToCanary(UUID orgId, UUID storeId, UUID actorId);

    /**
     * Remove a store from the canary rollout set.
     *
     * @param orgId   the organization ID
     * @param storeId the store to remove
     * @param actorId the user performing the action
     */
    void removeStoreFromCanary(UUID orgId, UUID storeId, UUID actorId);

    /**
     * Enable canary mode for an organization.
     *
     * @param orgId   the organization ID
     * @param actorId the user enabling canary
     */
    void enableCanary(UUID orgId, UUID actorId);

    /**
     * Disable canary mode for an organization (promotes to full rollout).
     *
     * @param orgId   the organization ID
     * @param actorId the user disabling canary
     */
    void disableCanary(UUID orgId, UUID actorId);
}
