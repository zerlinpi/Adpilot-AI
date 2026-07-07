package com.adpilot.modules.advertising.support;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the pure ad-metric computation helper {@link AdMetrics}.
 *
 * Feature: app-functionality-completion, Property 3: Ad-metric computation (ACoS / TACoS / AI coverage).
 *
 * <p>For any non-negative spend / adSales / totalSales:
 * <ul>
 *   <li>ACoS and TACoS are computed correctly (spend / denominator, as a
 *       percentage) and are never negative;</li>
 *   <li>a zero denominator yields the {@link AdMetrics#ZERO_DENOMINATOR_SENTINEL}
 *       rather than a NaN or an exception;</li>
 *   <li>AI coverage is always clamped to the closed range {@code [0, 100]}.</li>
 * </ul>
 *
 * <p>Validates: Requirements 18.1, 18.3, 18.4.
 *
 * <p>These exercise the pure helper directly — no Spring context is needed
 * because each method deterministically maps its inputs to a {@link BigDecimal}.
 */
class AdMetricsPropertyTest {

    /** Tolerance for the independently-recomputed percentage comparison. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    /**
     * Feature: app-functionality-completion, Property 3: Ad-metric computation (ACoS / TACoS / AI coverage).
     *
     * Req 18.1 / 18.3: for any non-negative spend and a strictly positive
     * adSales, ACoS equals {@code spend / adSales * 100} (within rounding
     * tolerance) and is never negative.
     */
    @Property(tries = 200)
    void acosIsCorrectAndNonNegativeForPositiveDenominator(
            @ForAll("nonNegative") BigDecimal spend,
            @ForAll("positive") BigDecimal adSales) {

        BigDecimal result = AdMetrics.acos(spend, adSales);
        BigDecimal expected = expectedPercentage(spend, adSales);

        assertThat(result).isNotNull();
        assertThat(result.signum()).isGreaterThanOrEqualTo(0);
        assertThat(result.subtract(expected).abs()).isLessThanOrEqualTo(TOLERANCE);
    }

    /**
     * Feature: app-functionality-completion, Property 3: Ad-metric computation (ACoS / TACoS / AI coverage).
     *
     * Req 18.1 / 18.3: for any non-negative spend and a strictly positive
     * totalSales, TACoS equals {@code spend / totalSales * 100} (within rounding
     * tolerance) and is never negative.
     */
    @Property(tries = 200)
    void tacosIsCorrectAndNonNegativeForPositiveDenominator(
            @ForAll("nonNegative") BigDecimal spend,
            @ForAll("positive") BigDecimal totalSales) {

        BigDecimal result = AdMetrics.tacos(spend, totalSales);
        BigDecimal expected = expectedPercentage(spend, totalSales);

        assertThat(result).isNotNull();
        assertThat(result.signum()).isGreaterThanOrEqualTo(0);
        assertThat(result.subtract(expected).abs()).isLessThanOrEqualTo(TOLERANCE);
    }

    /**
     * Feature: app-functionality-completion, Property 3: Ad-metric computation (ACoS / TACoS / AI coverage).
     *
     * Req 18.3: a zero denominator yields the sentinel for both ACoS and TACoS —
     * never an exception and never a negative value — for any non-negative spend.
     */
    @Property(tries = 200)
    void zeroDenominatorYieldsSentinel(@ForAll("nonNegative") BigDecimal spend) {
        assertThat(AdMetrics.acos(spend, BigDecimal.ZERO))
                .isEqualByComparingTo(AdMetrics.ZERO_DENOMINATOR_SENTINEL);
        assertThat(AdMetrics.tacos(spend, BigDecimal.ZERO))
                .isEqualByComparingTo(AdMetrics.ZERO_DENOMINATOR_SENTINEL);
        assertThat(AdMetrics.ZERO_DENOMINATOR_SENTINEL.signum()).isGreaterThanOrEqualTo(0);
    }

    /**
     * Feature: app-functionality-completion, Property 3: Ad-metric computation (ACoS / TACoS / AI coverage).
     *
     * Req 18.4: a raw coverage percentage — including out-of-range and negative
     * values — is always clamped into the closed range {@code [0, 100]}.
     */
    @Property(tries = 200)
    void aiCoverageIsAlwaysClampedToRange(@ForAll("anyCoverage") BigDecimal coveragePercent) {
        BigDecimal result = AdMetrics.aiCoverage(coveragePercent);

        assertThat(result).isNotNull();
        assertThat(result).isBetween(AdMetrics.MIN_COVERAGE, AdMetrics.MAX_COVERAGE);
    }

    /**
     * Feature: app-functionality-completion, Property 3: Ad-metric computation (ACoS / TACoS / AI coverage).
     *
     * Req 18.4: coverage derived from {@code aiAdSpend / totalAdSpend} is always
     * within {@code [0, 100]} for any non-negative inputs, including a zero
     * total ad spend (which maps to the in-range sentinel).
     */
    @Property(tries = 200)
    void aiCoverageRatioIsAlwaysInRange(
            @ForAll("nonNegative") BigDecimal aiAdSpend,
            @ForAll("nonNegative") BigDecimal totalAdSpend) {

        BigDecimal result = AdMetrics.aiCoverage(aiAdSpend, totalAdSpend);

        assertThat(result).isNotNull();
        assertThat(result).isBetween(AdMetrics.MIN_COVERAGE, AdMetrics.MAX_COVERAGE);
    }

    // ---- Helpers ----

    /** Recomputes {@code numerator / denominator * 100} independently of the helper. */
    private static BigDecimal expectedPercentage(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    // ---- Generators (constrained to the realistic metric input space) ----

    /** Non-negative monetary amounts with up to 2 decimal places. */
    @Provide
    Arbitrary<BigDecimal> nonNegative() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("1000000"))
                .ofScale(2);
    }

    /** Strictly positive monetary amounts (valid denominators). */
    @Provide
    Arbitrary<BigDecimal> positive() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("1000000"))
                .ofScale(2)
                .filter(v -> v.signum() > 0);
    }

    /** Coverage candidates spanning below, within, and above {@code [0, 100]}. */
    @Provide
    Arbitrary<BigDecimal> anyCoverage() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("-500"), new BigDecimal("500"))
                .ofScale(2);
    }
}
