package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.service.HostingPhase;

import java.util.UUID;

/**
 * Service for managing the active hosting phase per store, including validation
 * of adjacent-only transitions (V1↔V2↔V3) and downgrade cleanup of operations
 * that belong to capabilities disabled by the new phase.
 *
 * <p>Phase transitions are constrained to adjacent phases only:</p>
 * <ul>
 *   <li>V1 → V2 (upgrade)</li>
 *   <li>V2 → V3 (upgrade)</li>
 *   <li>V2 → V1 (downgrade)</li>
 *   <li>V3 → V2 (downgrade)</li>
 * </ul>
 *
 * <p>Direct jumps (V1→V3, V3→V1) are rejected.</p>
 *
 * <p>On downgrade, the service cleans up operations for disabled capabilities:</p>
 * <ul>
 *   <li>Cancels {@code awaiting_approval} operations in scope</li>
 *   <li>Supersedes/cancels {@code pending} operations and closes their Outbox rows</li>
 *   <li>Stops generating disabled capabilities (handled by phase gate in engines)</li>
 *   <li>Leaves {@code submitted} operations to platform cancel/reconciliation</li>
 * </ul>
 *
 * <p>Validates: Requirements 17.1, 17.2, 17.3, 17.4, 17.5, 17.6.</p>
 */
public interface PhaseConfigurationService {

    /**
     * Returns the current hosting phase for the given store.
     *
     * @param storeId the store identifier
     * @return the currently active hosting phase; defaults to V1 if not configured
     */
    HostingPhase getCurrentPhase(UUID storeId);

    /**
     * Validates whether a transition from the current phase to the new phase is legal
     * (adjacent only).
     *
     * @param currentPhase the current active phase
     * @param newPhase     the desired target phase
     * @return {@code true} if the transition is adjacent (legal), {@code false} otherwise
     */
    boolean validateTransition(HostingPhase currentPhase, HostingPhase newPhase);

    /**
     * Transitions the store to a new hosting phase after validating that the transition
     * is adjacent. On downgrade, performs cleanup of in-scope operations for disabled
     * capabilities.
     *
     * @param storeId  the store identifier
     * @param newPhase the target phase to transition to
     * @param actorId  the user performing the transition (for audit)
     * @throws com.adpilot.common.exception.BusinessException if the transition is not
     *         adjacent (illegal) or if the store is already at the target phase
     */
    void transitionPhase(UUID storeId, HostingPhase newPhase, UUID actorId);
}
