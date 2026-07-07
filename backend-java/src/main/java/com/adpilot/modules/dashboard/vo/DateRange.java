package com.adpilot.modules.dashboard.vo;

import java.time.LocalDate;

/**
 * An inclusive date range used to bound cross-store aggregation queries
 * (Req 5.1.1). Either bound may be {@code null} to leave that side unbounded.
 *
 * @param start the inclusive start date, or {@code null} for no lower bound
 * @param end   the inclusive end date, or {@code null} for no upper bound
 */
public record DateRange(LocalDate start, LocalDate end) {

    /** A range covering the most recent {@code days} days up to today (inclusive). */
    public static DateRange lastDays(int days) {
        LocalDate today = LocalDate.now();
        return new DateRange(today.minusDays(Math.max(0, days)), today);
    }

    /** The date used to look up an exchange rate: the end bound, or today when absent. */
    public LocalDate rateDate() {
        return end != null ? end : LocalDate.now();
    }
}
