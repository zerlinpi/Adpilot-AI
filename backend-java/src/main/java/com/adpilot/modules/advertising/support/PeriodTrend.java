package com.adpilot.modules.advertising.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Pure, side-effect-free period-over-period trend arithmetic
 * (Requirement 19.4, 19.5).
 *
 * <p>The single rule, stated once here and reused everywhere, is: a
 * period-over-period growth value is always computed against the
 * <strong>immediately preceding period of equal length</strong>, and is
 * reported as <em>not-available</em> rather than as a value derived from a zero
 * (or absent) baseline when the preceding comparison period has no data.</p>
 *
 * <p>All methods are <strong>total</strong>: every input — including
 * {@code null} current values and an absent baseline — produces a defined
 * result and no method divides by zero. The {@link #precedingPeriod} helper is
 * the single source of truth for the comparison window, so growth can never be
 * accidentally compared against the wrong span.</p>
 *
 * <p>This class is the single source of truth targeted by the
 * period-over-period trend property test (Property 44, task 14.22) and consumed
 * by the campaign trend / KPI panels (task 14.10).</p>
 */
public final class PeriodTrend {

    /** Scale (decimal places) of every growth-ratio result. */
    public static final int GROWTH_SCALE = 4;

    private PeriodTrend() {
        // Utility class — not instantiable.
    }

    /**
     * An inclusive {@code [start, end]} date range describing a reporting
     * period. Both ends are inclusive, so a single day is a range whose
     * {@code start} equals its {@code end} and whose length is {@code 1}.
     *
     * @param start the first day of the period (inclusive)
     * @param end   the last day of the period (inclusive)
     */
    public record PeriodRange(LocalDate start, LocalDate end) {

        public PeriodRange {
            if (start == null || end == null) {
                throw new IllegalArgumentException("period start and end are required");
            }
            if (end.isBefore(start)) {
                throw new IllegalArgumentException("period end must not be before start");
            }
        }

        /** The inclusive length of the period in days (always {@code >= 1}). */
        public long lengthInDays() {
            return ChronoUnit.DAYS.between(start, end) + 1;
        }
    }

    /**
     * Outcome of a period-over-period growth computation. When
     * {@link #available()} is {@code false} the {@link #growth()} value is
     * {@code null}: there is no baseline to grow from, so no number is
     * fabricated (Requirement 19.5).
     *
     * @param available whether a growth value could be computed against a
     *                  non-empty, non-zero baseline
     * @param growth    the growth ratio (e.g. {@code 0.25} for +25%), or
     *                  {@code null} when {@link #available()} is {@code false}
     */
    public record TrendResult(boolean available, BigDecimal growth) {

        /** The single not-available instance (no baseline / zero baseline). */
        public static final TrendResult NOT_AVAILABLE = new TrendResult(false, null);

        /** Builds an available result carrying the computed growth ratio. */
        public static TrendResult of(BigDecimal growth) {
            return new TrendResult(true, growth);
        }
    }

    /**
     * The immediately preceding period of equal length, ending the day before
     * the selected period starts (Requirement 19.4).
     *
     * <p>For a selected range of {@code n} days {@code [s, e]} this returns
     * {@code [s - n, s - 1]}, which is contiguous with and exactly as long as
     * the selected period.</p>
     *
     * @param selected the selected reporting period
     * @return the equal-length period immediately before {@code selected}
     */
    public static PeriodRange precedingPeriod(PeriodRange selected) {
        if (selected == null) {
            throw new IllegalArgumentException("selected period is required");
        }
        long length = selected.lengthInDays();
        LocalDate precedingEnd = selected.start().minusDays(1);
        LocalDate precedingStart = precedingEnd.minusDays(length - 1);
        return new PeriodRange(precedingStart, precedingEnd);
    }

    /**
     * Period-over-period growth of {@code currentValue} relative to
     * {@code baselineValue}, where {@code baselineValue} is the aggregate of the
     * {@link #precedingPeriod} (Requirement 19.4).
     *
     * <p>The growth is {@code (current - baseline) / baseline}. When the
     * preceding period has no data ({@code baselineHasData} is {@code false} or
     * {@code baselineValue} is {@code null}) <em>or</em> the baseline aggregate
     * is exactly zero, the result is {@link TrendResult#NOT_AVAILABLE} rather
     * than a value derived from a zero baseline (Requirement 19.5).</p>
     *
     * @param currentValue    the selected period's aggregate (treated as
     *                        {@code 0} when {@code null})
     * @param baselineValue   the preceding period's aggregate, or {@code null}
     *                        when that period has no data
     * @param baselineHasData whether the preceding period actually has any data
     *                        backing {@code baselineValue}
     * @return the growth ratio, or {@link TrendResult#NOT_AVAILABLE} when there
     *         is no usable baseline
     */
    public static TrendResult growth(BigDecimal currentValue,
                                     BigDecimal baselineValue,
                                     boolean baselineHasData) {
        if (!baselineHasData || baselineValue == null) {
            return TrendResult.NOT_AVAILABLE;
        }
        if (baselineValue.compareTo(BigDecimal.ZERO) == 0) {
            // A zero baseline would force a divide-by-zero or an infinite growth;
            // report not-available instead of fabricating a number (Req 19.5).
            return TrendResult.NOT_AVAILABLE;
        }
        BigDecimal current = currentValue == null ? BigDecimal.ZERO : currentValue;
        BigDecimal growth = current.subtract(baselineValue)
                .divide(baselineValue, GROWTH_SCALE, RoundingMode.HALF_UP);
        return TrendResult.of(growth);
    }
}
