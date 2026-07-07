package com.adpilot.modules.advertising.hosting;

/**
 * The single fixed-precedence routing pipeline for AI hosting decisions
 * (Requirement 7.10).
 *
 * <p>Every candidate decision is evaluated through this pipeline, which produces
 * exactly one {@link RoutingResult} determining whether the decision becomes:
 * <ul>
 *   <li>{@link RoutingOutcome#NO_OP} — nothing persisted, nothing created.</li>
 *   <li>{@link RoutingOutcome#AI_DECISIONS_ONLY} — persisted in {@code ai_decisions}
 *       but no Operation is created.</li>
 *   <li>{@link RoutingOutcome#PENDING_OPERATION} — a {@code pending} Operation is
 *       created with an Outbox row.</li>
 *   <li>{@link RoutingOutcome#AWAITING_APPROVAL_OPERATION} — an
 *       {@code awaiting_approval} Operation is created with a linked
 *       {@code approval_requests} record.</li>
 * </ul>
 *
 * <h3>Fixed precedence (highest to lowest)</h3>
 * <ol>
 *   <li><b>Kill Switch</b> — active → NO_OP (log KILL_SWITCH)</li>
 *   <li><b>Shadow Mode</b> — on → AI_DECISIONS_ONLY</li>
 *   <li><b>Phase Gate</b> — engine not enabled → NO_OP (log phase-disabled)</li>
 *   <li><b>Execution Mode</b> — observe_only/recommend_only → AI_DECISIONS_ONLY;
 *       approval_required → AWAITING_APPROVAL_OPERATION</li>
 *   <li><b>High-Risk Gate</b> — high-risk action → AWAITING_APPROVAL_OPERATION</li>
 *   <li><b>Risk Threshold</b> — score ≥ threshold → AWAITING_APPROVAL_OPERATION;
 *       below → PENDING_OPERATION</li>
 * </ol>
 *
 * <p><b>Critical invariant:</b> the pipeline NEVER applies the SUBMIT event.
 * Submission is the sole responsibility of the {@code OutboxWorker}.
 *
 * <p>Validates: Requirements 7.3, 7.4, 7.5, 7.10, 35.1, 35.3, 37.2, 37.3.</p>
 */
public interface DecisionRoutingPipeline {

    /**
     * Route a candidate decision through the fixed-precedence pipeline.
     *
     * <p>This method is PURE — it performs no I/O, has no side effects, and is
     * deterministic given the same input context. All governance flags, execution
     * modes, and risk scores must be resolved beforehand and provided in the context.
     *
     * @param context the fully-resolved candidate decision context
     * @return the routing result (outcome + reason); never {@code null}
     * @throws IllegalArgumentException if context is {@code null}
     */
    RoutingResult route(CandidateDecisionContext context);
}
