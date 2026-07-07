package com.adpilot.modules.advertising.hosting;

import java.util.UUID;

/**
 * Service interface for Shadow Mode (Requirement 35.1).
 *
 * <p>Shadow mode means engines generate and persist decisions in {@code ai_decisions}
 * but never submit to Amazon (no Operations are created). This allows validating
 * behavior against production data without any platform side effects.</p>
 *
 * <p>Validates: Requirements 35.1, 35.8.</p>
 */
public interface ShadowModeService {

    /**
     * Check if shadow mode is active for a given store.
     *
     * <p>When shadow mode is active, the decision routing pipeline persists decisions
     * in {@code ai_decisions} but does NOT create Operations or Outbox rows.</p>
     *
     * @param storeId the store to check
     * @return {@code true} if shadow mode is active for the store
     */
    boolean isInShadowMode(UUID storeId);

    /**
     * Enable shadow mode for a store.
     *
     * @param storeId the store to enable shadow mode for
     * @param actorId the user enabling shadow mode
     */
    void enableShadowMode(UUID storeId, UUID actorId);

    /**
     * Disable shadow mode for a store.
     *
     * @param storeId the store to disable shadow mode for
     * @param actorId the user disabling shadow mode
     */
    void disableShadowMode(UUID storeId, UUID actorId);
}
