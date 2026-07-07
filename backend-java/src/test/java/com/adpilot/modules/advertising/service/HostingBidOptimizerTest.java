package com.adpilot.modules.advertising.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Example-based unit tests for {@link HostingBidOptimizer}.
 *
 * <p>The exhaustive across-all-inputs guarantees (Property 4) are covered by the
 * separate property-based test (task 11.2); these tests pin down concrete edge
 * cases and the documented contract.
 */
class HostingBidOptimizerTest {

    private static final BigDecimal MIN = new BigDecimal("0.20");
    private static final BigDecimal MAX = new BigDecimal("5.00");
    private static final BigDecimal MAX_PCT = new BigDecimal("20"); // +/-20%

    @Test
    void noOpWhenAcosEqualsTarget() {
        BigDecimal current = new BigDecimal("1.0000");
        BigDecimal result = HostingBidOptimizer.adjustBid(
                current, new BigDecimal("25"), new BigDecimal("25"), MIN, MAX, MAX_PCT);
        assertThat(result).isEqualByComparingTo(current);
    }

    @Test
    void decreasesWhenAcosAboveTarget() {
        BigDecimal current = new BigDecimal("1.00");
        BigDecimal result = HostingBidOptimizer.adjustBid(
                current, new BigDecimal("40"), new BigDecimal("25"), MIN, MAX, MAX_PCT);
        assertThat(result).isLessThan(current);
    }

    @Test
    void increasesWhenAcosBelowTarget() {
        BigDecimal current = new BigDecimal("1.00");
        BigDecimal result = HostingBidOptimizer.adjustBid(
                current, new BigDecimal("10"), new BigDecimal("25"), MIN, MAX, MAX_PCT);
        assertThat(result).isGreaterThan(current);
    }

    @Test
    void resultStaysWithinPercentBand() {
        BigDecimal current = new BigDecimal("1.00");
        BigDecimal result = HostingBidOptimizer.adjustBid(
                current, BigDecimal.ZERO, new BigDecimal("25"), MIN, MAX, MAX_PCT);
        // ACoS far below target -> increase, but never beyond +20% of 1.00 = 1.20
        assertThat(result).isLessThanOrEqualTo(new BigDecimal("1.20"));
        assertThat(result).isGreaterThanOrEqualTo(current);
    }

    @Test
    void resultNeverExceedsMaxBid() {
        // Wide percent band but tight absolute max: absolute range must win.
        BigDecimal current = new BigDecimal("4.90");
        BigDecimal result = HostingBidOptimizer.adjustBid(
                current, BigDecimal.ZERO, new BigDecimal("25"), MIN, MAX, new BigDecimal("100"));
        assertThat(result).isLessThanOrEqualTo(MAX);
    }

    @Test
    void resultNeverBelowMinBid() {
        BigDecimal current = new BigDecimal("0.25");
        BigDecimal result = HostingBidOptimizer.adjustBid(
                current, new BigDecimal("90"), new BigDecimal("25"), MIN, MAX, new BigDecimal("100"));
        assertThat(result).isGreaterThanOrEqualTo(MIN);
    }

    @Test
    void zeroMaxChangePctIsAlwaysNoOp() {
        BigDecimal current = new BigDecimal("1.00");
        BigDecimal result = HostingBidOptimizer.adjustBid(
                current, new BigDecimal("90"), new BigDecimal("25"), MIN, MAX, BigDecimal.ZERO);
        assertThat(result).isEqualByComparingTo(current);
    }

    @Test
    void zeroTargetWithPositiveAcosDecreasesTowardLowerBound() {
        BigDecimal current = new BigDecimal("1.00");
        BigDecimal result = HostingBidOptimizer.adjustBid(
                current, new BigDecimal("30"), BigDecimal.ZERO, MIN, MAX, MAX_PCT);
        // target 0, recent 30 -> above target -> decrease by full step (20%) -> 0.80
        assertThat(result).isLessThan(current);
        assertThat(result).isGreaterThanOrEqualTo(new BigDecimal("0.80"));
    }

    @Test
    void clampToRangeKeepsPlacementLockBidWithinWindow() {
        BigDecimal lo = new BigDecimal("0.50");
        BigDecimal hi = new BigDecimal("2.00");
        assertThat(HostingBidOptimizer.clampToRange(new BigDecimal("3.00"), lo, hi)).isEqualByComparingTo(hi);
        assertThat(HostingBidOptimizer.clampToRange(new BigDecimal("0.10"), lo, hi)).isEqualByComparingTo(lo);
        assertThat(HostingBidOptimizer.clampToRange(new BigDecimal("1.25"), lo, hi))
                .isEqualByComparingTo(new BigDecimal("1.25"));
    }

    @Test
    void rejectsNegativeMaxChangePct() {
        assertThatThrownBy(() -> HostingBidOptimizer.adjustBid(
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, MIN, MAX, new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullArguments() {
        assertThatThrownBy(() -> HostingBidOptimizer.adjustBid(
                null, BigDecimal.TEN, BigDecimal.TEN, MIN, MAX, MAX_PCT))
                .isInstanceOf(NullPointerException.class);
    }
}
