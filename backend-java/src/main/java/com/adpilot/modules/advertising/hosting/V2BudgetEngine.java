package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.support.SafetyBoundary;

import java.util.List;

/**
 * V2 Budget Optimization Engine (Requirement 4).
 *
 * <p>Computes candidate daily budget adjustments for hosted campaigns based on:
 * target ACoS from the campaign's goal, actual ACoS over the personality policy's
 * lookbackDays, current daily spend velocity, and available inventory days.</p>
 *
 * <p>Emits {@link CandidateDecision} objects to the {@link OptimizationCoordinator}
 * rather than creating Operations directly. Only runs when the active hosting
 * phase is V2 or higher.</p>
 *
 * <p>Validates: Requirements 4.1, 4.2, 4.3, 4.5, 4.6, 4.7, 4.8, 4.9.</p>
 */
public interface V2BudgetEngine {

    /**
     * Produce budget adjustment candidates for a single campaign from the immutable
     * data snapshot.
     *
     * @param campaign         the hosted campaign to evaluate
     * @param snapshot         the immutable data snapshot captured at run start
     * @param resolvedBoundary the campaign's resolved safety boundary
     * @param inventoryDays    available inventory days for the campaign's products
     *                         (null or negative if unknown)
     * @return a list of candidate decisions (empty if skipped or no adjustment needed)
     */
    List<CandidateDecision> evaluate(CampaignEntity campaign,
                                     DataSnapshot snapshot,
                                     SafetyBoundary resolvedBoundary,
                                     Integer inventoryDays);

    /**
     * Result record that carries both the candidates and any skip reason when a
     * campaign is not eligible for budget optimization.
     *
     * @param candidates the produced candidate decisions (empty when skipped)
     * @param skipReason the reason the campaign was skipped, or null if evaluated
     */
    record EvaluationResult(List<CandidateDecision> candidates, String skipReason) {
        public static EvaluationResult skipped(String reason) {
            return new EvaluationResult(List.of(), reason);
        }

        public static EvaluationResult of(List<CandidateDecision> candidates) {
            return new EvaluationResult(candidates, null);
        }

        public boolean isSkipped() {
            return skipReason != null;
        }
    }

    /**
     * Produce budget adjustment candidates with full skip-reason reporting.
     *
     * @param campaign         the hosted campaign to evaluate
     * @param snapshot         the immutable data snapshot captured at run start
     * @param resolvedBoundary the campaign's resolved safety boundary
     * @param inventoryDays    available inventory days for the campaign's products
     *                         (null or negative if unknown)
     * @return evaluation result with candidates and optional skip reason
     */
    EvaluationResult evaluateWithReason(CampaignEntity campaign,
                                        DataSnapshot snapshot,
                                        SafetyBoundary resolvedBoundary,
                                        Integer inventoryDays);
}
