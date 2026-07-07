package com.adpilot.modules.advertising.support;

import com.adpilot.modules.advertising.support.AiEstimate.Confidence;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link AiEstimate}.
 *
 * <p>Feature: advertising-workspace-rework, Property 46: AI estimates carry their
 * method and degrade honestly.
 *
 * <p>Validates: Requirements 19.6, 19.7, 19.8, 50.12.
 *
 * <p>{@link AiEstimate} is the single source of truth for an AI sales-change /
 * spend-savings figure. A single property exercises both branches of the honest
 * estimate over arbitrary inputs:
 * <ul>
 *   <li><b>Honest degradation</b> — when no valid baseline exists (a {@code null}
 *       baseline), the figure is signaled not-estimable: {@code estimable()} is
 *       false, it carries <em>no</em> fabricated number (value/baseline/confidence/
 *       algorithm version are absent and the window is zero), and it exposes
 *       exactly the fixed {@link AiEstimate#NOT_ESTIMABLE_SIGNAL} verbatim for the
 *       frontend to display (Req 19.8, 50.12).</li>
 *   <li><b>Method-carrying estimate</b> — when a valid baseline is present, the
 *       result is estimable and carries its full estimation method: the baseline,
 *       a positive attribution window, a confidence indicator, and a non-blank
 *       algorithm version (Req 19.7). The figure is an estimate measured against
 *       the baseline (observed - baseline), never a naive raw before/after delta,
 *       and it carries no not-estimable signal (Req 19.6).</li>
 * </ul>
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 46: AI estimates carry their method and degrade honestly")
class AiEstimateHonestPropertyTest {

    /**
     * Feature: advertising-workspace-rework, Property 46: AI estimates carry their
     * method and degrade honestly.
     *
     * <p>Validates: Requirements 19.6, 19.7, 19.8, 50.12.
     */
    @Property(tries = 200)
    void aiEstimatesCarryTheirMethodAndDegradeHonestly(
            @ForAll("estimateInputs") EstimateInput input) {

        AiEstimate estimate = AiEstimate.of(
                input.observed(),
                input.baseline(),
                input.attributionWindowDays(),
                input.confidence(),
                input.algorithmVersion());

        if (input.baseline() == null) {
            // Req 19.8, 50.12: no valid baseline -> honest not-estimable signal, no
            // fabricated number, the fixed indication exposed verbatim.
            assertThat(estimate.estimable()).isFalse();
            assertThat(estimate.signal()).isEqualTo(AiEstimate.NOT_ESTIMABLE_SIGNAL);
            assertThat(estimate.signal()).isEqualTo("暂不可估算：缺少有效基线");
            assertThat(estimate.value()).isNull();
            assertThat(estimate.baseline()).isNull();
            assertThat(estimate.confidence()).isNull();
            assertThat(estimate.algorithmVersion()).isNull();
            assertThat(estimate.attributionWindowDays()).isZero();
            // It must equal the canonical not-estimable result.
            assertThat(estimate).isEqualTo(AiEstimate.notEstimable());
            return;
        }

        // Req 19.6, 19.7: a valid baseline -> an estimate that carries its full method.
        assertThat(estimate.estimable()).isTrue();
        assertThat(estimate.signal()).isNull();

        // The estimation method components are all recorded (Req 19.7).
        assertThat(estimate.baseline()).isEqualByComparingTo(input.baseline());
        assertThat(estimate.attributionWindowDays())
                .isEqualTo(input.attributionWindowDays())
                .isPositive();
        assertThat(estimate.confidence()).isNotNull().isEqualTo(input.confidence());
        assertThat(estimate.algorithmVersion()).isNotBlank()
                .isEqualTo(input.algorithmVersion().trim());

        // The figure is an estimate measured against the baseline, never a naive
        // before/after delta of raw totals (Req 19.6): value == observed - baseline,
        // where a null observed is treated as zero.
        BigDecimal observedValue = input.observed() == null ? BigDecimal.ZERO : input.observed();
        BigDecimal expected = observedValue.subtract(input.baseline());
        assertThat(estimate.value()).isNotNull().isEqualByComparingTo(expected);
    }

    // --- generators --------------------------------------------------------

    /** A bundle of estimate inputs so Combinators.combine stays well under 8 arbitraries. */
    record EstimateInput(BigDecimal observed,
                         BigDecimal baseline,
                         int attributionWindowDays,
                         Confidence confidence,
                         String algorithmVersion) {
    }

    private Arbitrary<BigDecimal> money() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.valueOf(-1_000_000), BigDecimal.valueOf(1_000_000))
                .ofScale(2);
    }

    /** Observed value may be null (treated as zero by the computation). */
    private Arbitrary<BigDecimal> observedValues() {
        return Arbitraries.oneOf(money(), Arbitraries.just(null));
    }

    /**
     * Baseline may be null (drives the honest not-estimable branch) or a real value
     * (drives the method-carrying estimable branch).
     */
    private Arbitrary<BigDecimal> baselineValues() {
        return Arbitraries.oneOf(money(), Arbitraries.just(null));
    }

    private Arbitrary<Integer> windows() {
        return Arbitraries.integers().between(1, 90);
    }

    private Arbitrary<Confidence> confidences() {
        return Arbitraries.of(Confidence.values());
    }

    private Arbitrary<String> algorithmVersions() {
        return Arbitraries.of("v1.0.0", "v1.2.3", "v2.0.0-rc1", "estimate-2024.06");
    }

    /**
     * An arbitrary mix of inputs whose baseline is null (not-estimable branch) or
     * present with a full, valid method (estimable branch). Five arbitraries are
     * combined into one bundle — well within the 8-arbitrary limit.
     */
    @Provide
    Arbitrary<EstimateInput> estimateInputs() {
        return Combinators.combine(
                        observedValues(),
                        baselineValues(),
                        windows(),
                        confidences(),
                        algorithmVersions())
                .as(EstimateInput::new);
    }
}
