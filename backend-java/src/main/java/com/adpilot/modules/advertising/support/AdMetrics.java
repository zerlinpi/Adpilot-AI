package com.adpilot.modules.advertising.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure, side-effect-free helpers that compute the AI advertising dashboard
 * efficiency metrics (Requirement 18.1, 18.3, 18.4).
 *
 * <p>All methods are <strong>total</strong>: every input — including
 * {@code null} and a zero denominator — produces a defined, finite,
 * non-negative {@link BigDecimal} result. No method throws and no method ever
 * returns {@code NaN} (a concept {@link BigDecimal} does not even support; the
 * point is that the corresponding {@code double} value is always finite).
 *
 * <p>Definitions (mirroring the requirements glossary):
 * <ul>
 *   <li><strong>ACoS</strong> — Advertising Cost of Sales = ad spend / ad
 *       sales, expressed as a percentage.</li>
 *   <li><strong>TACoS</strong> — Total Advertising Cost of Sales = ad spend /
 *       total sales, expressed as a percentage.</li>
 *   <li><strong>AI coverage</strong> — a percentage clamped to the closed
 *       range {@code [0, 100]}.</li>
 * </ul>
 *
 * <p>When a metric's denominator is zero (no sales to attribute spend to) the
 * methods return {@link #ZERO_DENOMINATOR_SENTINEL} rather than dividing by
 * zero. The sentinel is itself non-negative, so the non-negativity guarantee
 * holds uniformly.
 *
 * <p>This class is the single source of truth targeted by the ad-metric
 * computation property test (Property 3, task 9.3) and consumed by the
 * dashboard aggregation endpoints (task 9.4).
 */
public final class AdMetrics {

    /**
     * Value returned by {@link #acos} / {@link #tacos} when the denominator is
     * zero. Chosen to be non-negative and finite so callers never observe a
     * {@code NaN} or an exception for the "no sales" case.
     */
    public static final BigDecimal ZERO_DENOMINATOR_SENTINEL = BigDecimal.ZERO;

    /** Inclusive lower bound for AI coverage. */
    public static final BigDecimal MIN_COVERAGE = BigDecimal.ZERO;

    /** Inclusive upper bound for AI coverage. */
    public static final BigDecimal MAX_COVERAGE = new BigDecimal("100");

    /** Scale (decimal places) of every percentage result. */
    public static final int PERCENT_SCALE = 2;

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** Intermediate division scale, kept high so the final percentage rounds cleanly. */
    private static final int DIVISION_SCALE = 8;

    private AdMetrics() {
        // Utility class — not instantiable.
    }

    /**
     * Advertising Cost of Sales = {@code spend / adSales}, as a percentage.
     *
     * @param spend   ad spend (treated as {@code 0} when {@code null})
     * @param adSales ad sales (treated as {@code 0} when {@code null})
     * @return the ACoS percentage, or {@link #ZERO_DENOMINATOR_SENTINEL} when
     *         {@code adSales} is zero; never negative for non-negative inputs
     */
    public static BigDecimal acos(BigDecimal spend, BigDecimal adSales) {
        return percentageRatio(spend, adSales);
    }

    /**
     * Total Advertising Cost of Sales = {@code spend / totalSales}, as a
     * percentage.
     *
     * @param spend      ad spend (treated as {@code 0} when {@code null})
     * @param totalSales total sales (treated as {@code 0} when {@code null})
     * @return the TACoS percentage, or {@link #ZERO_DENOMINATOR_SENTINEL} when
     *         {@code totalSales} is zero; never negative for non-negative inputs
     */
    public static BigDecimal tacos(BigDecimal spend, BigDecimal totalSales) {
        return percentageRatio(spend, totalSales);
    }

    /**
     * Clamps a raw AI coverage percentage into the closed range
     * {@code [0, 100]}.
     *
     * @param coveragePercent a coverage percentage (treated as {@code 0} when
     *                        {@code null})
     * @return the value clamped to {@code [0, 100]} at {@link #PERCENT_SCALE}
     *         decimal places
     */
    public static BigDecimal aiCoverage(BigDecimal coveragePercent) {
        BigDecimal value = nvl(coveragePercent);
        if (value.compareTo(MIN_COVERAGE) < 0) {
            return scaled(MIN_COVERAGE);
        }
        if (value.compareTo(MAX_COVERAGE) > 0) {
            return scaled(MAX_COVERAGE);
        }
        return scaled(value);
    }

    /**
     * Computes AI coverage as {@code aiAdSpend / totalAdSpend} expressed as a
     * percentage and clamped to {@code [0, 100]}. Convenience for the AI Usage
     * panel (Requirement 18.4).
     *
     * @param aiAdSpend    ad spend managed by AI (treated as {@code 0} when {@code null})
     * @param totalAdSpend total ad spend (treated as {@code 0} when {@code null})
     * @return the coverage percentage clamped to {@code [0, 100]}; the
     *         {@link #ZERO_DENOMINATOR_SENTINEL} (which is in range) when
     *         {@code totalAdSpend} is zero
     */
    public static BigDecimal aiCoverage(BigDecimal aiAdSpend, BigDecimal totalAdSpend) {
        return aiCoverage(percentageRatio(aiAdSpend, totalAdSpend));
    }

    /**
     * Core ratio: {@code numerator / denominator * 100}, rounded to
     * {@link #PERCENT_SCALE} decimals. Returns {@link #ZERO_DENOMINATOR_SENTINEL}
     * when the denominator is zero so the result is always finite.
     */
    private static BigDecimal percentageRatio(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal num = nvl(numerator);
        BigDecimal den = nvl(denominator);
        if (den.compareTo(BigDecimal.ZERO) == 0) {
            return ZERO_DENOMINATOR_SENTINEL;
        }
        return num.divide(den, DIVISION_SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
