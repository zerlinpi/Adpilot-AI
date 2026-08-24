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
 * Property-based tests for the shared ad-metric computation helper.
 *
 * <p>The intent is to keep dashboards and automation on one numerical contract:
 * percentage metrics use the same denominator-zero behavior and ROAS/CPC use a
 * finite ratio sentinel instead of ad-hoc fallback divisors.
 */
class AdMetricsPropertyTest {

    private static final BigDecimal PERCENT_TOLERANCE = new BigDecimal("0.01");
    private static final BigDecimal RATIO_TOLERANCE = new BigDecimal("0.0001");

    @Property(tries = 200)
    void acosIsCorrectAndNonNegativeForPositiveDenominator(
            @ForAll("nonNegative") BigDecimal spend,
            @ForAll("positive") BigDecimal adSales) {

        BigDecimal result = AdMetrics.acos(spend, adSales);
        BigDecimal expected = expectedPercentage(spend, adSales);

        assertThat(result).isNotNull();
        assertThat(result.signum()).isGreaterThanOrEqualTo(0);
        assertThat(result.subtract(expected).abs()).isLessThanOrEqualTo(PERCENT_TOLERANCE);
    }

    @Property(tries = 200)
    void tacosIsCorrectAndNonNegativeForPositiveDenominator(
            @ForAll("nonNegative") BigDecimal spend,
            @ForAll("positive") BigDecimal totalSales) {

        BigDecimal result = AdMetrics.tacos(spend, totalSales);
        BigDecimal expected = expectedPercentage(spend, totalSales);

        assertThat(result).isNotNull();
        assertThat(result.signum()).isGreaterThanOrEqualTo(0);
        assertThat(result.subtract(expected).abs()).isLessThanOrEqualTo(PERCENT_TOLERANCE);
    }

    @Property(tries = 200)
    void ctrAndCvrUseTheSamePercentageContract(
            @ForAll("nonNegative") BigDecimal numerator,
            @ForAll("positive") BigDecimal denominator) {

        BigDecimal expected = expectedPercentage(numerator, denominator);

        assertThat(AdMetrics.ctr(numerator, denominator).subtract(expected).abs())
                .isLessThanOrEqualTo(PERCENT_TOLERANCE);
        assertThat(AdMetrics.cvr(numerator, denominator).subtract(expected).abs())
                .isLessThanOrEqualTo(PERCENT_TOLERANCE);
    }

    @Property(tries = 200)
    void roasAndCpcUseFinitePlainRatios(
            @ForAll("nonNegative") BigDecimal numerator,
            @ForAll("positive") BigDecimal denominator) {

        BigDecimal expected = expectedRatio(numerator, denominator);

        assertThat(AdMetrics.roas(numerator, denominator).subtract(expected).abs())
                .isLessThanOrEqualTo(RATIO_TOLERANCE);
        assertThat(AdMetrics.cpc(numerator, denominator).subtract(expected).abs())
                .isLessThanOrEqualTo(RATIO_TOLERANCE);
    }

    @Property(tries = 200)
    void zeroDenominatorYieldsSentinelForEveryRatio(@ForAll("nonNegative") BigDecimal numerator) {
        assertThat(AdMetrics.acos(numerator, BigDecimal.ZERO))
                .isEqualByComparingTo(AdMetrics.ZERO_DENOMINATOR_SENTINEL);
        assertThat(AdMetrics.tacos(numerator, BigDecimal.ZERO))
                .isEqualByComparingTo(AdMetrics.ZERO_DENOMINATOR_SENTINEL);
        assertThat(AdMetrics.ctr(numerator, BigDecimal.ZERO))
                .isEqualByComparingTo(AdMetrics.ZERO_DENOMINATOR_SENTINEL);
        assertThat(AdMetrics.cvr(numerator, BigDecimal.ZERO))
                .isEqualByComparingTo(AdMetrics.ZERO_DENOMINATOR_SENTINEL);
        assertThat(AdMetrics.roas(numerator, BigDecimal.ZERO))
                .isEqualByComparingTo(AdMetrics.ZERO_DENOMINATOR_SENTINEL);
        assertThat(AdMetrics.cpc(numerator, BigDecimal.ZERO))
                .isEqualByComparingTo(AdMetrics.ZERO_DENOMINATOR_SENTINEL);
    }

    @Property(tries = 200)
    void aiCoverageIsAlwaysClampedToRange(@ForAll("anyCoverage") BigDecimal coveragePercent) {
        BigDecimal result = AdMetrics.aiCoverage(coveragePercent);

        assertThat(result).isNotNull();
        assertThat(result).isBetween(AdMetrics.MIN_COVERAGE, AdMetrics.MAX_COVERAGE);
    }

    @Property(tries = 200)
    void aiCoverageRatioIsAlwaysInRange(
            @ForAll("nonNegative") BigDecimal aiAdSpend,
            @ForAll("nonNegative") BigDecimal totalAdSpend) {

        BigDecimal result = AdMetrics.aiCoverage(aiAdSpend, totalAdSpend);

        assertThat(result).isNotNull();
        assertThat(result).isBetween(AdMetrics.MIN_COVERAGE, AdMetrics.MAX_COVERAGE);
    }

    private static BigDecimal expectedPercentage(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(AdMetrics.PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal expectedRatio(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, AdMetrics.RATIO_SCALE, RoundingMode.HALF_UP);
    }

    @Provide
    Arbitrary<BigDecimal> nonNegative() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("1000000"))
                .ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> positive() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("1000000"))
                .ofScale(2)
                .filter(v -> v.signum() > 0);
    }

    @Provide
    Arbitrary<BigDecimal> anyCoverage() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("-500"), new BigDecimal("500"))
                .ofScale(2);
    }
}
