package com.adpilot.modules.advertising.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure, side-effect-free helpers for advertising efficiency metrics.
 *
 * <p>All methods are total for null and zero-denominator inputs: callers receive
 * a finite {@link BigDecimal} rather than an exception. Percentage metrics use
 * a common scale and ratio metrics use a common ratio scale so dashboards,
 * automation rules, exports, and future integrations share one definition.
 */
public final class AdMetrics {

    /** Value returned when a ratio denominator is zero. */
    public static final BigDecimal ZERO_DENOMINATOR_SENTINEL = BigDecimal.ZERO;

    /** Inclusive lower bound for AI coverage. */
    public static final BigDecimal MIN_COVERAGE = BigDecimal.ZERO;

    /** Inclusive upper bound for AI coverage. */
    public static final BigDecimal MAX_COVERAGE = new BigDecimal("100");

    /** Scale (decimal places) of percentage results. */
    public static final int PERCENT_SCALE = 2;

    /** Scale (decimal places) of non-percentage ratios such as ROAS and CPC. */
    public static final int RATIO_SCALE = 4;

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int DIVISION_SCALE = 8;

    private AdMetrics() {
        // Utility class — not instantiable.
    }

    /** Advertising Cost of Sales = spend / ad sales * 100. */
    public static BigDecimal acos(BigDecimal spend, BigDecimal adSales) {
        return percentageRatio(spend, adSales);
    }

    /** Total Advertising Cost of Sales = spend / total sales * 100. */
    public static BigDecimal tacos(BigDecimal spend, BigDecimal totalSales) {
        return percentageRatio(spend, totalSales);
    }

    /** Click-through rate = clicks / impressions * 100. */
    public static BigDecimal ctr(BigDecimal clicks, BigDecimal impressions) {
        return percentageRatio(clicks, impressions);
    }

    /** Conversion rate = orders / clicks * 100. */
    public static BigDecimal cvr(BigDecimal orders, BigDecimal clicks) {
        return percentageRatio(orders, clicks);
    }

    /** Return on ad spend = sales / spend. */
    public static BigDecimal roas(BigDecimal sales, BigDecimal spend) {
        return plainRatio(sales, spend);
    }

    /** Average cost per click = spend / clicks. */
    public static BigDecimal cpc(BigDecimal spend, BigDecimal clicks) {
        return plainRatio(spend, clicks);
    }

    /** Clamp a raw AI coverage percentage into [0, 100]. */
    public static BigDecimal aiCoverage(BigDecimal coveragePercent) {
        BigDecimal value = nvl(coveragePercent);
        if (value.compareTo(MIN_COVERAGE) < 0) {
            return scaledPercent(MIN_COVERAGE);
        }
        if (value.compareTo(MAX_COVERAGE) > 0) {
            return scaledPercent(MAX_COVERAGE);
        }
        return scaledPercent(value);
    }

    /** Compute AI coverage = AI-managed spend / total ad spend * 100. */
    public static BigDecimal aiCoverage(BigDecimal aiAdSpend, BigDecimal totalAdSpend) {
        return aiCoverage(percentageRatio(aiAdSpend, totalAdSpend));
    }

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

    private static BigDecimal plainRatio(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal num = nvl(numerator);
        BigDecimal den = nvl(denominator);
        if (den.compareTo(BigDecimal.ZERO) == 0) {
            return ZERO_DENOMINATOR_SENTINEL;
        }
        return num.divide(den, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaledPercent(BigDecimal value) {
        return value.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
