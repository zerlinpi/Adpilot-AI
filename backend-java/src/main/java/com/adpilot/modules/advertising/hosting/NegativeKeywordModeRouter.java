package com.adpilot.modules.advertising.hosting;

import org.springframework.stereotype.Component;

/**
 * Pure routing logic for negative-keyword candidates based on the
 * {@link NegativeKeywordMode} personality policy setting (Requirements 5.8, 5.9).
 *
 * <p>This router is applied by the V3 Keyword Engine AFTER the general
 * {@link DecisionRoutingPipeline} stages of Kill Switch, Shadow, and Phase
 * have passed (i.e., the candidate reaches the execution-mode stage). It
 * overrides the default high-risk/threshold routing for negative-keyword
 * candidates when {@code negativeKeywordMode == SUGGEST}.
 *
 * <h3>Routing rules</h3>
 * <ul>
 *   <li><b>observe_only / recommend_only</b> (regardless of negativeKeywordMode):
 *       no Operation is created; the candidate is persisted in {@code ai_decisions}
 *       only.</li>
 *   <li><b>approval_required / auto_execute + SUGGEST mode</b>:
 *       the negative candidate always routes to {@code awaiting_approval} regardless
 *       of risk score. {@code suggest} mode NEVER auto-executes.</li>
 *   <li><b>auto_execute + AUTO mode + store opts in</b>:
 *       the negative candidate may auto-execute (routes to {@code pending}).</li>
 *   <li><b>auto_execute + AUTO mode + store does NOT opt in</b>:
 *       the negative candidate follows the normal high-risk gate (which classifies
 *       negatives as high-risk per Req 7.5), routing to {@code awaiting_approval}.</li>
 *   <li><b>approval_required + AUTO mode</b>:
 *       the candidate follows the execution-mode rule and routes to
 *       {@code awaiting_approval}.</li>
 * </ul>
 *
 * <p>This method is PURE — no I/O, no state, deterministic given the same inputs.
 *
 * <p>Validates: Requirements 5.8, 5.9.</p>
 */
@Component
public class NegativeKeywordModeRouter {

    static final String REASON_SUGGEST_MODE_APPROVAL = "negative_keyword_suggest_mode";
    static final String REASON_OBSERVE_RECOMMEND = "observe_recommend_no_operation";
    static final String REASON_AUTO_MODE_PENDING = "negative_keyword_auto_mode_opted_in";
    static final String REASON_AUTO_MODE_NO_OPT_IN = "negative_keyword_auto_mode_no_opt_in";
    static final String REASON_APPROVAL_REQUIRED_MODE = "approval_required_mode";

    /**
     * Routes a negative-keyword candidate based on the negativeKeywordMode, execution
     * mode, and store opt-in status.
     *
     * @param executionMode        the resolved execution mode for this campaign
     * @param negativeKeywordMode  the personality policy's negativeKeywordMode setting
     * @param storeOptsInNegativeAuto whether the store explicitly opts in to negative auto-execution
     * @return the routing result for this negative-keyword candidate
     * @throws IllegalArgumentException if executionMode or negativeKeywordMode is null
     */
    public RoutingResult route(ExecutionMode executionMode,
                               NegativeKeywordMode negativeKeywordMode,
                               boolean storeOptsInNegativeAuto) {
        if (executionMode == null) {
            throw new IllegalArgumentException("executionMode must not be null");
        }
        if (negativeKeywordMode == null) {
            throw new IllegalArgumentException("negativeKeywordMode must not be null");
        }

        // Rule 1: Under observe_only or recommend_only, no Operation is created
        // regardless of negativeKeywordMode (Req 5.8 / 5.9)
        if (executionMode == ExecutionMode.OBSERVE_ONLY
                || executionMode == ExecutionMode.RECOMMEND_ONLY) {
            return RoutingResult.aiDecisionsOnly(REASON_OBSERVE_RECOMMEND);
        }

        // Rule 2: SUGGEST mode — always routes to awaiting_approval within
        // approval_required or auto_execute modes (Req 5.8).
        // "suggest SHALL never auto-execute" regardless of risk score or execution mode.
        if (negativeKeywordMode == NegativeKeywordMode.SUGGEST) {
            return RoutingResult.awaitingApproval(REASON_SUGGEST_MODE_APPROVAL);
        }

        // Rule 3: AUTO mode handling (negativeKeywordMode == AUTO)
        // Under approval_required: follows the mode rule → awaiting_approval
        if (executionMode == ExecutionMode.APPROVAL_REQUIRED) {
            return RoutingResult.awaitingApproval(REASON_APPROVAL_REQUIRED_MODE);
        }

        // Under auto_execute + AUTO mode:
        // - If store opts in: may auto-execute → pending
        // - If store does NOT opt in: follows high-risk gate → awaiting_approval
        if (executionMode == ExecutionMode.AUTO_EXECUTE) {
            if (storeOptsInNegativeAuto) {
                return RoutingResult.pending(REASON_AUTO_MODE_PENDING);
            } else {
                return RoutingResult.awaitingApproval(REASON_AUTO_MODE_NO_OPT_IN);
            }
        }

        // Defensive fallback: should not be reachable given the enum values
        return RoutingResult.aiDecisionsOnly(REASON_OBSERVE_RECOMMEND);
    }
}
