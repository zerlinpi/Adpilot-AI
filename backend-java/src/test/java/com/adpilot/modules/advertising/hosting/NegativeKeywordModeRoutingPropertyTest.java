package com.adpilot.modules.advertising.hosting;

import net.jqwik.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the {@link NegativeKeywordModeRouter} — the V3 engine's
 * negative-keyword candidate routing logic based on the
 * {@link NegativeKeywordMode} personality policy setting.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 31: V3 negative-keyword mode routing
 *
 * <p><b>Validates: Requirements 5.8, 5.9</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>When negativeKeywordMode = "suggest", the negative keyword candidate always routes
 *       to awaiting_approval (never auto-executes) within approval_required/auto_execute modes</li>
 *   <li>When negativeKeywordMode = "auto" AND execution mode = "auto_execute" AND store opts in,
 *       the candidate may auto-execute (routes to pending)</li>
 *   <li>Under observe_only or recommend_only, no Operation is created regardless of
 *       negativeKeywordMode (routes to AI_DECISIONS_ONLY)</li>
 *   <li>"suggest" mode never auto-executes regardless of risk score or execution mode</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 31: V3 negative-keyword mode routing")
class NegativeKeywordModeRoutingPropertyTest {

    private static final int MIN_ITERATIONS = 200;

    private final NegativeKeywordModeRouter router = new NegativeKeywordModeRouter();

    // ================================================================================
    // Property 1: SUGGEST mode always routes to awaiting_approval within executing modes
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 31: V3 negative-keyword mode routing
     *
     * <p><b>Validates: Requirements 5.8, 5.9</b></p>
     *
     * <p>When negativeKeywordMode = "suggest" and execution mode is approval_required
     * or auto_execute, the negative keyword candidate always routes to
     * awaiting_approval — never to pending (never auto-executes).</p>
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("suggest mode always routes to awaiting_approval within approval_required/auto_execute")
    void suggestModeAlwaysRoutesToAwaitingApproval(
            @ForAll("executingModes") ExecutionMode executionMode,
            @ForAll("anyBoolean") boolean storeOptsIn) {

        RoutingResult result = router.route(executionMode, NegativeKeywordMode.SUGGEST, storeOptsIn);

        assertThat(result.outcome())
                .as("SUGGEST mode with executing mode %s must produce AWAITING_APPROVAL_OPERATION",
                        executionMode)
                .isEqualTo(RoutingOutcome.AWAITING_APPROVAL_OPERATION);
    }

    // ================================================================================
    // Property 2: AUTO mode + auto_execute + store opts in → may auto-execute (pending)
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 31: V3 negative-keyword mode routing
     *
     * <p><b>Validates: Requirements 5.8, 5.9</b></p>
     *
     * <p>When negativeKeywordMode = "auto" AND execution mode = "auto_execute" AND
     * the store explicitly opts in to negative auto-execution, the candidate may
     * auto-execute (routes to pending).</p>
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("auto mode + auto_execute + store opts in → pending (may auto-execute)")
    void autoModeWithAutoExecuteAndOptInRoutesPending(
            @ForAll("alwaysTrue") boolean storeOptsIn) {

        RoutingResult result = router.route(
                ExecutionMode.AUTO_EXECUTE, NegativeKeywordMode.AUTO, true);

        assertThat(result.outcome())
                .as("AUTO mode + auto_execute + opted-in must produce PENDING_OPERATION")
                .isEqualTo(RoutingOutcome.PENDING_OPERATION);
    }

    // ================================================================================
    // Property 3: Under observe_only or recommend_only, no Operation is created
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 31: V3 negative-keyword mode routing
     *
     * <p><b>Validates: Requirements 5.8, 5.9</b></p>
     *
     * <p>Under observe_only or recommend_only execution modes, no Operation is
     * created regardless of negativeKeywordMode — only the ai_decisions row is
     * persisted.</p>
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("observe_only/recommend_only → AI_DECISIONS_ONLY regardless of negativeKeywordMode")
    void observeOrRecommendNeverCreatesOperation(
            @ForAll("nonExecutingModes") ExecutionMode executionMode,
            @ForAll("anyNegativeKeywordMode") NegativeKeywordMode negativeKeywordMode,
            @ForAll("anyBoolean") boolean storeOptsIn) {

        RoutingResult result = router.route(executionMode, negativeKeywordMode, storeOptsIn);

        assertThat(result.outcome())
                .as("observe_only/recommend_only with mode=%s must produce AI_DECISIONS_ONLY",
                        negativeKeywordMode)
                .isEqualTo(RoutingOutcome.AI_DECISIONS_ONLY);
    }

    // ================================================================================
    // Property 4: SUGGEST mode NEVER produces PENDING_OPERATION (never auto-executes)
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 31: V3 negative-keyword mode routing
     *
     * <p><b>Validates: Requirements 5.8, 5.9</b></p>
     *
     * <p>"suggest" mode SHALL never auto-execute regardless of risk score or execution
     * mode. For ANY combination of execution mode and store opt-in, SUGGEST mode
     * never produces PENDING_OPERATION.</p>
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("suggest mode NEVER produces PENDING_OPERATION for any execution mode")
    void suggestModeNeverAutoExecutes(
            @ForAll("allExecutionModes") ExecutionMode executionMode,
            @ForAll("anyBoolean") boolean storeOptsIn) {

        RoutingResult result = router.route(executionMode, NegativeKeywordMode.SUGGEST, storeOptsIn);

        assertThat(result.outcome())
                .as("SUGGEST mode with mode=%s, storeOptsIn=%s must NEVER produce PENDING_OPERATION",
                        executionMode, storeOptsIn)
                .isNotEqualTo(RoutingOutcome.PENDING_OPERATION);
    }

    // ================================================================================
    // Additional property: AUTO mode + auto_execute + store does NOT opt in → awaiting
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 31: V3 negative-keyword mode routing
     *
     * <p><b>Validates: Requirements 5.8, 5.9</b></p>
     *
     * <p>When negativeKeywordMode = "auto" AND execution mode = "auto_execute" but
     * the store has NOT opted in, the candidate routes to awaiting_approval (the
     * high-risk gate for negatives applies).</p>
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("auto mode + auto_execute + store NOT opted in → awaiting_approval")
    void autoModeAutoExecuteNoOptInRoutesAwaiting(
            @ForAll("alwaysFalse") boolean storeOptsIn) {

        RoutingResult result = router.route(
                ExecutionMode.AUTO_EXECUTE, NegativeKeywordMode.AUTO, false);

        assertThat(result.outcome())
                .as("AUTO mode + auto_execute + NOT opted-in must produce AWAITING_APPROVAL_OPERATION")
                .isEqualTo(RoutingOutcome.AWAITING_APPROVAL_OPERATION);
    }

    // ================================================================================
    // Additional property: AUTO mode + approval_required → awaiting_approval
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 31: V3 negative-keyword mode routing
     *
     * <p><b>Validates: Requirements 5.8, 5.9</b></p>
     *
     * <p>When negativeKeywordMode = "auto" and execution mode = "approval_required",
     * the candidate follows the execution-mode rule and routes to awaiting_approval.</p>
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("auto mode + approval_required → awaiting_approval")
    void autoModeApprovalRequiredRoutesAwaiting(
            @ForAll("anyBoolean") boolean storeOptsIn) {

        RoutingResult result = router.route(
                ExecutionMode.APPROVAL_REQUIRED, NegativeKeywordMode.AUTO, storeOptsIn);

        assertThat(result.outcome())
                .as("AUTO mode + approval_required must produce AWAITING_APPROVAL_OPERATION")
                .isEqualTo(RoutingOutcome.AWAITING_APPROVAL_OPERATION);
    }

    // ================================================================================
    // Generators
    // ================================================================================

    /** Generates executing modes: approval_required and auto_execute. */
    @Provide
    Arbitrary<ExecutionMode> executingModes() {
        return Arbitraries.of(ExecutionMode.APPROVAL_REQUIRED, ExecutionMode.AUTO_EXECUTE);
    }

    /** Generates non-executing modes: observe_only and recommend_only. */
    @Provide
    Arbitrary<ExecutionMode> nonExecutingModes() {
        return Arbitraries.of(ExecutionMode.OBSERVE_ONLY, ExecutionMode.RECOMMEND_ONLY);
    }

    /** Generates all execution modes. */
    @Provide
    Arbitrary<ExecutionMode> allExecutionModes() {
        return Arbitraries.of(ExecutionMode.values());
    }

    /** Generates all NegativeKeywordMode values. */
    @Provide
    Arbitrary<NegativeKeywordMode> anyNegativeKeywordMode() {
        return Arbitraries.of(NegativeKeywordMode.values());
    }

    /** Generates random booleans. */
    @Provide
    Arbitrary<Boolean> anyBoolean() {
        return Arbitraries.of(true, false);
    }

    /** Always true — used for tests that need a fixed opt-in value. */
    @Provide
    Arbitrary<Boolean> alwaysTrue() {
        return Arbitraries.just(true);
    }

    /** Always false — used for tests that need a fixed opt-out value. */
    @Provide
    Arbitrary<Boolean> alwaysFalse() {
        return Arbitraries.just(false);
    }
}
