package com.adpilot.modules.advertising.hosting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Default implementation of the {@link DecisionRoutingPipeline} (Requirement 7.10).
 *
 * <p>Implements the single fixed-precedence routing logic. The method is PURE:
 * no I/O, no database calls, no side effects. All inputs (governance flags,
 * execution mode, risk score, high-risk classification) are pre-resolved and
 * provided via {@link CandidateDecisionContext}.
 *
 * <h3>Pipeline stages (fixed precedence, short-circuiting)</h3>
 * <ol>
 *   <li><b>Kill Switch</b> — if active for scope → NO_OP, reason KILL_SWITCH.</li>
 *   <li><b>Shadow Mode</b> — if on → AI_DECISIONS_ONLY, reason shadow_mode.</li>
 *   <li><b>Phase Gate</b> — if engine not enabled → NO_OP, reason phase_disabled.</li>
 *   <li><b>Execution Mode</b>:
 *       <ul>
 *         <li>observe_only → AI_DECISIONS_ONLY</li>
 *         <li>recommend_only → AI_DECISIONS_ONLY</li>
 *         <li>approval_required → AWAITING_APPROVAL_OPERATION</li>
 *         <li>auto_execute → proceed to high-risk gate</li>
 *       </ul>
 *   </li>
 *   <li><b>High-Risk Gate</b> — if high-risk → AWAITING_APPROVAL_OPERATION.</li>
 *   <li><b>Risk Threshold</b> — if score ≥ threshold → AWAITING_APPROVAL_OPERATION;
 *       otherwise → PENDING_OPERATION.</li>
 * </ol>
 *
 * <p><b>Critical invariant:</b> this pipeline NEVER applies SUBMIT. The
 * OutboxWorker is the sole submitter of {@code pending} Operations.
 *
 * <p>Validates: Requirements 7.3, 7.4, 7.5, 7.10, 35.1, 35.3, 37.2, 37.3.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionRoutingPipelineImpl implements DecisionRoutingPipeline {

    static final String REASON_KILL_SWITCH = "KILL_SWITCH";
    static final String REASON_SHADOW_MODE = "shadow_mode";
    static final String REASON_CANARY_ROLLOUT_EXCLUDED = "canary_rollout_excluded";
    static final String REASON_PHASE_DISABLED = "phase_disabled";
    static final String REASON_OBSERVE_ONLY = "observe_only";
    static final String REASON_RECOMMEND_ONLY = "recommend_only";
    static final String REASON_APPROVAL_REQUIRED_MODE = "approval_required_mode";
    static final String REASON_RISK_ABOVE_THRESHOLD = "risk_above_threshold";
    static final String REASON_AUTO_EXECUTE_BELOW_THRESHOLD = "auto_execute_below_threshold";

    private final HighRiskClassifier highRiskClassifier;

    @Override
    public RoutingResult route(CandidateDecisionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }

        // Stage 1: Kill Switch (highest precedence)
        if (context.killSwitchActive()) {
            log.debug("Routing decision for store={} campaign={}: KILL_SWITCH active → NO_OP",
                    context.storeId(), context.campaignId());
            return RoutingResult.noOp(REASON_KILL_SWITCH);
        }

        // Stage 2: Shadow Mode
        if (context.shadowModeActive()) {
            log.debug("Routing decision for store={} campaign={}: shadow mode → AI_DECISIONS_ONLY",
                    context.storeId(), context.campaignId());
            return RoutingResult.aiDecisionsOnly(REASON_SHADOW_MODE);
        }

        // Stage 3: Canary rollout gate
        if (!context.canaryStoreAllowed()) {
            log.debug("Routing decision for store={} campaign={}: canary rollout excluded → AI_DECISIONS_ONLY",
                    context.storeId(), context.campaignId());
            return RoutingResult.aiDecisionsOnly(REASON_CANARY_ROLLOUT_EXCLUDED);
        }

        // Stage 4: Phase Gate
        if (!context.phaseEnabled()) {
            log.debug("Routing decision for store={} campaign={}: phase disabled → NO_OP",
                    context.storeId(), context.campaignId());
            return RoutingResult.noOp(REASON_PHASE_DISABLED);
        }

        // Stage 5: Execution Mode
        ExecutionMode mode = context.executionMode();
        switch (mode) {
            case OBSERVE_ONLY:
                log.debug("Routing decision for store={} campaign={}: observe_only → AI_DECISIONS_ONLY",
                        context.storeId(), context.campaignId());
                return RoutingResult.aiDecisionsOnly(REASON_OBSERVE_ONLY);

            case RECOMMEND_ONLY:
                log.debug("Routing decision for store={} campaign={}: recommend_only → AI_DECISIONS_ONLY",
                        context.storeId(), context.campaignId());
                return RoutingResult.aiDecisionsOnly(REASON_RECOMMEND_ONLY);

            case APPROVAL_REQUIRED:
                log.debug("Routing decision for store={} campaign={}: approval_required → AWAITING_APPROVAL",
                        context.storeId(), context.campaignId());
                return RoutingResult.awaitingApproval(REASON_APPROVAL_REQUIRED_MODE);

            case AUTO_EXECUTE:
                // Fall through to high-risk gate and risk threshold
                break;

            default:
                // Defensive: unknown mode treated as observe_only (safest)
                log.warn("Unknown execution mode '{}' for store={} campaign={}, defaulting to AI_DECISIONS_ONLY",
                        mode, context.storeId(), context.campaignId());
                return RoutingResult.aiDecisionsOnly(REASON_OBSERVE_ONLY);
        }

        // Stage 5: High-Risk Gate (only reached in auto_execute mode)
        if (highRiskClassifier.isHighRisk(context)) {
            String highRiskReason = highRiskClassifier.classificationReason(context);
            log.debug("Routing decision for store={} campaign={}: high-risk ({}) → AWAITING_APPROVAL",
                    context.storeId(), context.campaignId(), highRiskReason);
            return RoutingResult.awaitingApproval(highRiskReason);
        }

        // Stage 6: Risk Threshold (only reached in auto_execute mode, not high-risk)
        BigDecimal riskScore = context.riskScore();
        BigDecimal threshold = context.riskThreshold();
        if (riskScore.compareTo(threshold) >= 0) {
            log.debug("Routing decision for store={} campaign={}: risk {} >= threshold {} → AWAITING_APPROVAL",
                    context.storeId(), context.campaignId(), riskScore, threshold);
            return RoutingResult.awaitingApproval(REASON_RISK_ABOVE_THRESHOLD);
        }

        // Stage 6 (else): Below threshold → pending operation
        log.debug("Routing decision for store={} campaign={}: risk {} < threshold {} → PENDING_OPERATION",
                context.storeId(), context.campaignId(), riskScore, threshold);
        return RoutingResult.pending(REASON_AUTO_EXECUTE_BELOW_THRESHOLD);
    }
}
