package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.service.HostingAdjustmentType;
import net.jqwik.api.*;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the {@link DecisionRoutingPipelineImpl} fixed-precedence logic
 * using the real {@link HighRiskClassifierImpl}.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 21: Fixed-precedence routing pipeline
 *
 * <p><b>Validates: Requirements 7.3, 7.4, 7.5, 7.10, 35.1, 35.3, 37.2, 37.3</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>Kill switch active → always NO_OP regardless of other flags</li>
 *   <li>Shadow mode on (no kill switch) → always AI_DECISIONS_ONLY</li>
 *   <li>Phase disabled (no kill/shadow) → always NO_OP</li>
 *   <li>observe_only/recommend_only mode → always AI_DECISIONS_ONLY</li>
 *   <li>approval_required mode → always AWAITING_APPROVAL_OPERATION</li>
 *   <li>auto_execute + high-risk → AWAITING_APPROVAL_OPERATION</li>
 *   <li>auto_execute + risk >= threshold → AWAITING_APPROVAL_OPERATION</li>
 *   <li>auto_execute + risk < threshold + not high-risk → PENDING_OPERATION</li>
 *   <li>The pipeline NEVER produces a "submitted" outcome</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 21: Fixed-precedence routing pipeline")
class DecisionRoutingPipelinePropertyTest {

    private static final int MIN_ITERATIONS = 150;

    private final HighRiskClassifierImpl highRiskClassifier = new HighRiskClassifierImpl();
    private final DecisionRoutingPipelineImpl pipeline =
            new DecisionRoutingPipelineImpl(highRiskClassifier);

    // ================================================================================
    // Property 1: Kill switch active → always NO_OP regardless of other flags
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 21: Fixed-precedence routing pipeline.
     *
     * <p><b>Validates: Requirements 7.3, 7.4, 7.5, 7.10, 35.1, 35.3, 37.2, 37.3</b>
     *
     * <p>When the kill switch is active, the pipeline always produces NO_OP
     * regardless of shadow mode, phase, execution mode, or risk score.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Kill switch active → always NO_OP regardless of other flags")
    void killSwitchAlwaysProducesNoOp(
            @ForAll("randomContextWithKillSwitch") CandidateDecisionContext context) {

        RoutingResult result = pipeline.route(context);

        assertThat(result.outcome())
                .as("Kill switch active must always produce NO_OP")
                .isEqualTo(RoutingOutcome.NO_OP);
        assertThat(result.reason())
                .as("Reason should be KILL_SWITCH")
                .isEqualTo("KILL_SWITCH");
    }

    // ================================================================================
    // Property 2: Shadow mode on (no kill switch) → always AI_DECISIONS_ONLY
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 21: Fixed-precedence routing pipeline.
     *
     * <p><b>Validates: Requirements 7.3, 7.4, 7.5, 7.10, 35.1, 35.3, 37.2, 37.3</b>
     *
     * <p>When shadow mode is active and kill switch is NOT active, the pipeline
     * always produces AI_DECISIONS_ONLY regardless of phase, execution mode, or risk.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Shadow mode on (no kill switch) → always AI_DECISIONS_ONLY")
    void shadowModeAlwaysProducesAiDecisionsOnly(
            @ForAll("randomContextWithShadowMode") CandidateDecisionContext context) {

        RoutingResult result = pipeline.route(context);

        assertThat(result.outcome())
                .as("Shadow mode (no kill switch) must always produce AI_DECISIONS_ONLY")
                .isEqualTo(RoutingOutcome.AI_DECISIONS_ONLY);
        assertThat(result.reason())
                .as("Reason should be shadow_mode")
                .isEqualTo("shadow_mode");
    }

    @Example
    @Label("Canary rollout exclusion produces AI_DECISIONS_ONLY before phase/execution routing")
    void canaryExclusionProducesAiDecisionsOnly() {
        CandidateDecisionContext context = new CandidateDecisionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                HostingAdjustmentType.BID,
                "bid",
                false,
                false,
                true,
                false,
                ExecutionMode.AUTO_EXECUTE,
                new BigDecimal("0.01"),
                new BigDecimal("0.30"),
                BigDecimal.ZERO,
                CandidateDecisionContext.DEFAULT_MAX_BUDGET_DECREASE_RATIO
        );

        RoutingResult result = pipeline.route(context);

        assertThat(result.outcome()).isEqualTo(RoutingOutcome.AI_DECISIONS_ONLY);
        assertThat(result.reason()).isEqualTo("canary_rollout_excluded");
    }

    // ================================================================================
    // Property 3: Phase disabled (no kill/shadow) → always NO_OP
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 21: Fixed-precedence routing pipeline.
     *
     * <p><b>Validates: Requirements 7.3, 7.4, 7.5, 7.10, 35.1, 35.3, 37.2, 37.3</b>
     *
     * <p>When the phase is disabled (and kill switch + shadow mode are off), the
     * pipeline always produces NO_OP regardless of execution mode or risk.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Phase disabled (no kill/shadow) → always NO_OP")
    void phaseDisabledAlwaysProducesNoOp(
            @ForAll("randomContextWithPhaseDisabled") CandidateDecisionContext context) {

        RoutingResult result = pipeline.route(context);

        assertThat(result.outcome())
                .as("Phase disabled (no kill/shadow) must always produce NO_OP")
                .isEqualTo(RoutingOutcome.NO_OP);
        assertThat(result.reason())
                .as("Reason should be phase_disabled")
                .isEqualTo("phase_disabled");
    }

    // ================================================================================
    // Property 4: observe_only/recommend_only mode → always AI_DECISIONS_ONLY
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 21: Fixed-precedence routing pipeline.
     *
     * <p><b>Validates: Requirements 7.3, 7.4, 7.5, 7.10, 35.1, 35.3, 37.2, 37.3</b>
     *
     * <p>When kill switch/shadow/phase are not blocking and execution mode is
     * observe_only or recommend_only, the pipeline produces AI_DECISIONS_ONLY.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("observe_only/recommend_only mode → always AI_DECISIONS_ONLY")
    void observeOrRecommendProducesAiDecisionsOnly(
            @ForAll("randomContextWithObserveOrRecommend") CandidateDecisionContext context) {

        RoutingResult result = pipeline.route(context);

        assertThat(result.outcome())
                .as("observe_only/recommend_only must produce AI_DECISIONS_ONLY")
                .isEqualTo(RoutingOutcome.AI_DECISIONS_ONLY);
    }

    // ================================================================================
    // Property 5: approval_required mode → always AWAITING_APPROVAL_OPERATION
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 21: Fixed-precedence routing pipeline.
     *
     * <p><b>Validates: Requirements 7.3, 7.4, 7.5, 7.10, 35.1, 35.3, 37.2, 37.3</b>
     *
     * <p>When kill switch/shadow/phase are not blocking and execution mode is
     * approval_required, the pipeline always produces AWAITING_APPROVAL_OPERATION.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("approval_required mode → always AWAITING_APPROVAL_OPERATION")
    void approvalRequiredProducesAwaitingApproval(
            @ForAll("randomContextWithApprovalRequired") CandidateDecisionContext context) {

        RoutingResult result = pipeline.route(context);

        assertThat(result.outcome())
                .as("approval_required must produce AWAITING_APPROVAL_OPERATION")
                .isEqualTo(RoutingOutcome.AWAITING_APPROVAL_OPERATION);
    }

    // ================================================================================
    // Property 6: auto_execute + high-risk → AWAITING_APPROVAL_OPERATION
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 21: Fixed-precedence routing pipeline.
     *
     * <p><b>Validates: Requirements 7.3, 7.4, 7.5, 7.10, 35.1, 35.3, 37.2, 37.3</b>
     *
     * <p>When in auto_execute mode (no kill/shadow/phase blocking) with a high-risk
     * action, the pipeline produces AWAITING_APPROVAL_OPERATION.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("auto_execute + high-risk → AWAITING_APPROVAL_OPERATION")
    void autoExecuteHighRiskProducesAwaitingApproval(
            @ForAll("randomContextAutoExecuteHighRisk") CandidateDecisionContext context) {

        // Precondition: verify the classifier actually classifies this as high-risk
        assertThat(highRiskClassifier.isHighRisk(context))
                .as("Precondition: context must be classified as high-risk")
                .isTrue();

        RoutingResult result = pipeline.route(context);

        assertThat(result.outcome())
                .as("auto_execute + high-risk must produce AWAITING_APPROVAL_OPERATION")
                .isEqualTo(RoutingOutcome.AWAITING_APPROVAL_OPERATION);
    }

    // ================================================================================
    // Property 7: auto_execute + risk >= threshold → AWAITING_APPROVAL_OPERATION
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 21: Fixed-precedence routing pipeline.
     *
     * <p><b>Validates: Requirements 7.3, 7.4, 7.5, 7.10, 35.1, 35.3, 37.2, 37.3</b>
     *
     * <p>When in auto_execute mode (no kill/shadow/phase blocking), not high-risk,
     * but risk score >= threshold, the pipeline produces AWAITING_APPROVAL_OPERATION.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("auto_execute + risk >= threshold → AWAITING_APPROVAL_OPERATION")
    void autoExecuteRiskAboveThresholdProducesAwaitingApproval(
            @ForAll("randomContextAutoExecuteRiskAboveThreshold") CandidateDecisionContext context) {

        // Precondition: not high risk, risk >= threshold
        assertThat(highRiskClassifier.isHighRisk(context))
                .as("Precondition: context must NOT be high-risk")
                .isFalse();
        assertThat(context.riskScore().compareTo(context.riskThreshold()) >= 0)
                .as("Precondition: riskScore >= riskThreshold")
                .isTrue();

        RoutingResult result = pipeline.route(context);

        assertThat(result.outcome())
                .as("auto_execute + risk >= threshold must produce AWAITING_APPROVAL_OPERATION")
                .isEqualTo(RoutingOutcome.AWAITING_APPROVAL_OPERATION);
    }

    // ================================================================================
    // Property 8: auto_execute + risk < threshold + not high-risk → PENDING_OPERATION
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 21: Fixed-precedence routing pipeline.
     *
     * <p><b>Validates: Requirements 7.3, 7.4, 7.5, 7.10, 35.1, 35.3, 37.2, 37.3</b>
     *
     * <p>When in auto_execute mode (no kill/shadow/phase blocking), not high-risk,
     * and risk score < threshold, the pipeline produces PENDING_OPERATION.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("auto_execute + risk < threshold + not high-risk → PENDING_OPERATION")
    void autoExecuteLowRiskProducesPendingOperation(
            @ForAll("randomContextAutoExecuteLowRisk") CandidateDecisionContext context) {

        // Precondition: not high risk, risk < threshold
        assertThat(highRiskClassifier.isHighRisk(context))
                .as("Precondition: context must NOT be high-risk")
                .isFalse();
        assertThat(context.riskScore().compareTo(context.riskThreshold()) < 0)
                .as("Precondition: riskScore < riskThreshold")
                .isTrue();

        RoutingResult result = pipeline.route(context);

        assertThat(result.outcome())
                .as("auto_execute + risk < threshold + not high-risk must produce PENDING_OPERATION")
                .isEqualTo(RoutingOutcome.PENDING_OPERATION);
    }

    // ================================================================================
    // Property 9: The pipeline NEVER produces a "submitted" outcome
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 21: Fixed-precedence routing pipeline.
     *
     * <p><b>Validates: Requirements 7.3, 7.4, 7.5, 7.10, 35.1, 35.3, 37.2, 37.3</b>
     *
     * <p>For ANY combination of inputs, the pipeline never returns an outcome
     * containing "submitted" — submission is the sole responsibility of the OutboxWorker.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("The pipeline NEVER produces a submitted outcome")
    void pipelineNeverProducesSubmitted(
            @ForAll("randomContext") CandidateDecisionContext context) {

        RoutingResult result = pipeline.route(context);

        assertThat(result).isNotNull();
        assertThat(result.outcome()).isNotNull();
        assertThat(result.outcome().value())
                .as("Pipeline must never produce a 'submitted' outcome")
                .doesNotContain("submitted");
        // Also ensure the outcome is one of the four valid values
        assertThat(result.outcome())
                .as("Outcome must be one of the four defined routing outcomes")
                .isIn(RoutingOutcome.NO_OP,
                        RoutingOutcome.AI_DECISIONS_ONLY,
                        RoutingOutcome.PENDING_OPERATION,
                        RoutingOutcome.AWAITING_APPROVAL_OPERATION);
    }

    // ================================================================================
    // Generators
    // ================================================================================

    /** Generates a fully random CandidateDecisionContext with killSwitch = true. */
    @Provide
    Arbitrary<CandidateDecisionContext> randomContextWithKillSwitch() {
        return Combinators.combine(
                Arbitraries.of(true, false), // shadowModeActive
                Arbitraries.of(true, false), // phaseEnabled
                Arbitraries.of(ExecutionMode.values()),
                riskScoreArbitrary(),
                riskThresholdArbitrary()
        ).flatAs((shadow, phase, mode, risk, threshold) ->
                changeArbitrary().map(change -> new CandidateDecisionContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        change.adjustmentType(),
                        change.changeType(),
                        true,   // killSwitchActive
                        shadow,
                        phase,
                        mode,
                        risk,
                        threshold,
                        change.budgetDecreaseRatio(),
                        change.maxBudgetDecreaseRatio()
                ))
        );
    }

    /** Generates context with shadowMode = true, killSwitch = false. */
    @Provide
    Arbitrary<CandidateDecisionContext> randomContextWithShadowMode() {
        return Combinators.combine(
                Arbitraries.of(true, false), // phaseEnabled
                Arbitraries.of(ExecutionMode.values()),
                riskScoreArbitrary(),
                riskThresholdArbitrary()
        ).flatAs((phase, mode, risk, threshold) ->
                changeArbitrary().map(change -> new CandidateDecisionContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        change.adjustmentType(),
                        change.changeType(),
                        false,  // killSwitchActive
                        true,   // shadowModeActive
                        phase,
                        mode,
                        risk,
                        threshold,
                        change.budgetDecreaseRatio(),
                        change.maxBudgetDecreaseRatio()
                ))
        );
    }

    /** Generates context with phase disabled, kill switch off, shadow off. */
    @Provide
    Arbitrary<CandidateDecisionContext> randomContextWithPhaseDisabled() {
        return Combinators.combine(
                Arbitraries.of(ExecutionMode.values()),
                riskScoreArbitrary(),
                riskThresholdArbitrary()
        ).flatAs((mode, risk, threshold) ->
                changeArbitrary().map(change -> new CandidateDecisionContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        change.adjustmentType(),
                        change.changeType(),
                        false,  // killSwitchActive
                        false,  // shadowModeActive
                        false,  // phaseEnabled (disabled)
                        mode,
                        risk,
                        threshold,
                        change.budgetDecreaseRatio(),
                        change.maxBudgetDecreaseRatio()
                ))
        );
    }

    /** Generates context in observe_only or recommend_only mode (no kill/shadow, phase enabled). */
    @Provide
    Arbitrary<CandidateDecisionContext> randomContextWithObserveOrRecommend() {
        return Combinators.combine(
                Arbitraries.of(ExecutionMode.OBSERVE_ONLY, ExecutionMode.RECOMMEND_ONLY),
                riskScoreArbitrary(),
                riskThresholdArbitrary()
        ).flatAs((mode, risk, threshold) ->
                changeArbitrary().map(change -> new CandidateDecisionContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        change.adjustmentType(),
                        change.changeType(),
                        false,  // killSwitchActive
                        false,  // shadowModeActive
                        true,   // phaseEnabled
                        mode,
                        risk,
                        threshold,
                        change.budgetDecreaseRatio(),
                        change.maxBudgetDecreaseRatio()
                ))
        );
    }

    /** Generates context in approval_required mode (no kill/shadow, phase enabled). */
    @Provide
    Arbitrary<CandidateDecisionContext> randomContextWithApprovalRequired() {
        return Combinators.combine(
                riskScoreArbitrary(),
                riskThresholdArbitrary()
        ).flatAs((risk, threshold) ->
                changeArbitrary().map(change -> new CandidateDecisionContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        change.adjustmentType(),
                        change.changeType(),
                        false,  // killSwitchActive
                        false,  // shadowModeActive
                        true,   // phaseEnabled
                        ExecutionMode.APPROVAL_REQUIRED,
                        risk,
                        threshold,
                        change.budgetDecreaseRatio(),
                        change.maxBudgetDecreaseRatio()
                ))
        );
    }

    /**
     * Generates context in auto_execute mode that IS high-risk.
     * High-risk scenarios: state change, keyword addition, negative keyword addition,
     * or large budget decrease (ratio > max threshold).
     */
    @Provide
    Arbitrary<CandidateDecisionContext> randomContextAutoExecuteHighRisk() {
        Arbitrary<CandidateDecisionContext> stateChange = Combinators.combine(
                riskScoreArbitrary(),
                riskThresholdArbitrary()
        ).as((risk, threshold) -> new CandidateDecisionContext(
                UUID.randomUUID(), UUID.randomUUID(),
                HostingAdjustmentType.BID, "state",
                false, false, true, ExecutionMode.AUTO_EXECUTE,
                risk, threshold, BigDecimal.ZERO,
                CandidateDecisionContext.DEFAULT_MAX_BUDGET_DECREASE_RATIO
        ));

        Arbitrary<CandidateDecisionContext> keywordAddition = Combinators.combine(
                riskScoreArbitrary(),
                riskThresholdArbitrary()
        ).as((risk, threshold) -> new CandidateDecisionContext(
                UUID.randomUUID(), UUID.randomUUID(),
                HostingAdjustmentType.KEYWORD, "keyword",
                false, false, true, ExecutionMode.AUTO_EXECUTE,
                risk, threshold, BigDecimal.ZERO,
                CandidateDecisionContext.DEFAULT_MAX_BUDGET_DECREASE_RATIO
        ));

        Arbitrary<CandidateDecisionContext> negativeKeyword = Combinators.combine(
                riskScoreArbitrary(),
                riskThresholdArbitrary()
        ).as((risk, threshold) -> new CandidateDecisionContext(
                UUID.randomUUID(), UUID.randomUUID(),
                HostingAdjustmentType.NEGATIVE, "negative_keyword",
                false, false, true, ExecutionMode.AUTO_EXECUTE,
                risk, threshold, BigDecimal.ZERO,
                CandidateDecisionContext.DEFAULT_MAX_BUDGET_DECREASE_RATIO
        ));

        // Large budget decrease: ratio strictly greater than the maxRatio threshold
        Arbitrary<CandidateDecisionContext> largeBudgetDecrease = Combinators.combine(
                riskScoreArbitrary(),
                riskThresholdArbitrary(),
                Arbitraries.bigDecimals().between(new BigDecimal("0.10"), new BigDecimal("0.30")),
                Arbitraries.bigDecimals().between(new BigDecimal("0.31"), new BigDecimal("0.95"))
        ).as((risk, riskThreshold, maxRatio, decreaseRatio) -> new CandidateDecisionContext(
                UUID.randomUUID(), UUID.randomUUID(),
                HostingAdjustmentType.BUDGET, "budget",
                false, false, true, ExecutionMode.AUTO_EXECUTE,
                risk, riskThreshold, decreaseRatio, maxRatio
        ));

        return Arbitraries.oneOf(stateChange, keywordAddition, negativeKeyword, largeBudgetDecrease);
    }

    /**
     * Generates context in auto_execute mode that is NOT high-risk and risk >= threshold.
     * Not high-risk means: BID adjustment with changeType "bid" and zero budget decrease.
     */
    @Provide
    Arbitrary<CandidateDecisionContext> randomContextAutoExecuteRiskAboveThreshold() {
        return Combinators.combine(
                Arbitraries.bigDecimals().between(new BigDecimal("0.10"), new BigDecimal("0.50")).ofScale(2),
                Arbitraries.bigDecimals().between(new BigDecimal("0.00"), new BigDecimal("0.10")).ofScale(2)
        ).as((threshold, offset) -> {
            // riskScore = threshold + offset (ensures >= threshold)
            BigDecimal riskScore = threshold.add(offset).min(BigDecimal.ONE);
            return new CandidateDecisionContext(
                    UUID.randomUUID(), UUID.randomUUID(),
                    HostingAdjustmentType.BID, "bid",
                    false, false, true, ExecutionMode.AUTO_EXECUTE,
                    riskScore, threshold, BigDecimal.ZERO,
                    CandidateDecisionContext.DEFAULT_MAX_BUDGET_DECREASE_RATIO
            );
        });
    }

    /**
     * Generates context in auto_execute mode that is NOT high-risk and risk < threshold.
     * Not high-risk means: BID adjustment with changeType "bid" and zero budget decrease.
     */
    @Provide
    Arbitrary<CandidateDecisionContext> randomContextAutoExecuteLowRisk() {
        return Combinators.combine(
                Arbitraries.bigDecimals().between(new BigDecimal("0.20"), new BigDecimal("0.80")).ofScale(2),
                Arbitraries.bigDecimals().between(new BigDecimal("0.01"), new BigDecimal("0.19")).ofScale(2)
        ).as((threshold, offset) -> {
            // riskScore = threshold - offset (ensures < threshold)
            BigDecimal riskScore = threshold.subtract(offset).max(BigDecimal.ZERO);
            return new CandidateDecisionContext(
                    UUID.randomUUID(), UUID.randomUUID(),
                    HostingAdjustmentType.BID, "bid",
                    false, false, true, ExecutionMode.AUTO_EXECUTE,
                    riskScore, threshold, BigDecimal.ZERO,
                    CandidateDecisionContext.DEFAULT_MAX_BUDGET_DECREASE_RATIO
            );
        });
    }

    /** Generates a completely random context (all flags randomized). */
    @Provide
    Arbitrary<CandidateDecisionContext> randomContext() {
        return Combinators.combine(
                Arbitraries.of(true, false), // killSwitch
                Arbitraries.of(true, false), // shadowMode
                Arbitraries.of(true, false), // phaseEnabled
                Arbitraries.of(ExecutionMode.values()),
                riskScoreArbitrary(),
                riskThresholdArbitrary()
        ).flatAs((ks, sm, pe, mode, risk, threshold) ->
                changeArbitrary().map(change -> new CandidateDecisionContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        change.adjustmentType(),
                        change.changeType(),
                        ks,
                        sm,
                        pe,
                        mode,
                        risk,
                        threshold,
                        change.budgetDecreaseRatio(),
                        change.maxBudgetDecreaseRatio()
                ))
        );
    }

    // ================================================================================
    // Helper types and methods for generators
    // ================================================================================

    /** Simple holder for change-related fields generated together. */
    private record ChangeData(
            HostingAdjustmentType adjustmentType,
            String changeType,
            BigDecimal budgetDecreaseRatio,
            BigDecimal maxBudgetDecreaseRatio
    ) {}

    /** Generates consistent change data (adjustment type + change type + ratios). */
    private Arbitrary<ChangeData> changeArbitrary() {
        return Arbitraries.of(HostingAdjustmentType.values())
                .flatMap(adjustmentType -> {
                    String changeType = switch (adjustmentType) {
                        case BID -> "bid";
                        case BUDGET -> "budget";
                        case KEYWORD -> "keyword";
                        case NEGATIVE -> "negative_keyword";
                    };
                    return Combinators.combine(
                            Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("0.80")).ofScale(2),
                            Arbitraries.bigDecimals().between(new BigDecimal("0.10"), new BigDecimal("0.50")).ofScale(2)
                    ).as((decreaseRatio, maxRatio) ->
                            new ChangeData(adjustmentType, changeType, decreaseRatio, maxRatio));
                });
    }

    private Arbitrary<BigDecimal> riskScoreArbitrary() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, BigDecimal.ONE)
                .ofScale(2);
    }

    private Arbitrary<BigDecimal> riskThresholdArbitrary() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.05"), new BigDecimal("0.95"))
                .ofScale(2);
    }
}
