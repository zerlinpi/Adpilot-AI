package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.support.SafetyBoundary;

import java.util.List;

/**
 * V3 Search Term Harvest and Keyword Engine (Requirement 5).
 *
 * <p>Analyzes search term performance data from {@code search_term_daily},
 * scores each term's confidence based on clicks, orders, ACoS, and statistical
 * significance, and proposes:
 * <ul>
 *   <li>Exact-match keyword additions when: orders &gt; 0 AND ACoS &lt; target AND confidence &gt; threshold</li>
 *   <li>Negative keyword additions when: clicks &gt; minClicks AND orders = 0 AND confidence &gt; threshold</li>
 * </ul>
 *
 * <p>Respects personality policy modes ({@code keywordExpansionMode}, {@code negativeKeywordMode}),
 * per-day caps ({@code maxKeywordsAddedPerDay}, {@code maxNegativesAddedPerDay}), and brand-word
 * protection for negatives.</p>
 *
 * <p>Emits {@link CandidateDecision} objects to the {@link OptimizationCoordinator}
 * rather than creating Operations directly. Only runs when the active hosting phase
 * is V3 ({@code phase.supports(HostingAdjustmentType.KEYWORD)}).</p>
 *
 * <p>Validates: Requirements 5.1, 5.2, 5.3, 5.5, 5.6, 5.7, 5.10, 5.11, 5.12.</p>
 */
public interface V3SearchTermEngine {

    /**
     * Produce keyword and negative-keyword candidates for a single campaign from the
     * immutable data snapshot.
     *
     * @param campaign         the hosted campaign to evaluate
     * @param snapshot         the immutable data snapshot captured at run start
     * @param resolvedBoundary the campaign's resolved safety boundary
     * @return a list of candidate decisions (empty if skipped or no proposals)
     */
    List<CandidateDecision> evaluate(CampaignEntity campaign,
                                     DataSnapshot snapshot,
                                     SafetyBoundary resolvedBoundary);

    /**
     * Produce keyword and negative-keyword candidates with full skip-reason reporting.
     *
     * @param campaign         the hosted campaign to evaluate
     * @param snapshot         the immutable data snapshot captured at run start
     * @param resolvedBoundary the campaign's resolved safety boundary
     * @return evaluation result with candidates and optional skip reason
     */
    EvaluationResult evaluateWithReason(CampaignEntity campaign,
                                        DataSnapshot snapshot,
                                        SafetyBoundary resolvedBoundary);

    /**
     * Result record carrying both the candidates and any skip reason when a
     * campaign is not eligible for keyword optimization.
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
}
