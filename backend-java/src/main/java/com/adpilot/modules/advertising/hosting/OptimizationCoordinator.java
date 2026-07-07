package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.support.SafetyBoundary;

import java.util.List;
import java.util.UUID;

/**
 * The Optimization Coordinator (Requirement 23).
 *
 * <p>After all engines (V1/V2/V3) produce {@link CandidateDecision} objects against
 * the same immutable {@link DataSnapshot}, this coordinator:
 * <ol>
 *   <li><b>De-conflicts</b> — skips candidates that collide with an unsettled
 *       Operation on the same entity/field (Req 23.4, 20.5, 36.5).</li>
 *   <li><b>Models cross-engine interactions</b> — e.g., a budget decrease
 *       constrains bid increases (Req 23.2).</li>
 *   <li><b>Prioritizes</b> — sorts candidates by risk score (lowest-risk first)
 *       for execution (Req 23.3).</li>
 *   <li><b>Enforces operation caps</b> — respects {@code maxOperationsPerRun} and
 *       {@code maxOperationsPerDay} from the resolved safety boundaries (Req 23.3).</li>
 *   <li><b>Applies safety-boundary clipping</b> — clamps proposed values to
 *       resolved boundaries before handing survivors to the pipeline (Req 6.4).</li>
 *   <li><b>Routes survivors</b> — hands surviving candidates to the
 *       {@link DecisionRoutingPipeline} (Req 7.10).</li>
 * </ol>
 *
 * <p>Validates: Requirements 23.2, 23.3, 23.4, 20.5, 36.5.</p>
 */
public interface OptimizationCoordinator {

    /**
     * Coordinate all candidate decisions produced by engines for a single
     * optimization run targeting one campaign.
     *
     * @param candidates         candidate decisions from all engines for this campaign
     * @param resolvedBoundary   the resolved safety boundary for this campaign
     * @param campaignId         the campaign being optimized
     * @param storeId            the store the campaign belongs to
     * @param operationsToday    count of operations already created today for this campaign
     * @param killSwitchActive   whether the kill switch is active for this scope
     * @param shadowModeActive   whether shadow mode is on
     * @param phaseEnabled       whether the engines are enabled for the current phase
     * @param executionMode      the resolved execution mode for this campaign
     * @return the coordination result containing surviving candidates and skip reasons
     */
    default CoordinationResult coordinate(
            List<CandidateDecision> candidates,
            SafetyBoundary resolvedBoundary,
            UUID campaignId,
            UUID storeId,
            int operationsToday,
            boolean killSwitchActive,
            boolean shadowModeActive,
            boolean phaseEnabled,
            ExecutionMode executionMode
    ) {
        return coordinate(candidates, resolvedBoundary, campaignId, storeId, operationsToday,
                killSwitchActive, shadowModeActive, phaseEnabled, true, executionMode);
    }

    /**
     * Coordinate candidates with an already resolved canary gate decision.
     */
    CoordinationResult coordinate(
            List<CandidateDecision> candidates,
            SafetyBoundary resolvedBoundary,
            UUID campaignId,
            UUID storeId,
            int operationsToday,
            boolean killSwitchActive,
            boolean shadowModeActive,
            boolean phaseEnabled,
            boolean canaryStoreAllowed,
            ExecutionMode executionMode
    );
}
