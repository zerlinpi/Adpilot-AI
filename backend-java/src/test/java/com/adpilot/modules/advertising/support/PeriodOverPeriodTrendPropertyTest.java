package com.adpilot.modules.advertising.support;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.adpilot.modules.advertising.support.PeriodTrend.PeriodRange;
import com.adpilot.modules.advertising.support.PeriodTrend.TrendResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the pure period-over-period trend arithmetic encoded by
 * {@link PeriodTrend} (the single source of truth consumed by the campaign trend / KPI panels,
 * task 14.10).
 *
 * <p>Feature: advertising-workspace-rework, Property 44: Period-over-period trend uses the
 * immediately preceding equal-length period.
 *
 * <p>Validates: Requirements 19.4, 19.5.
 *
 * <p><em>For any</em> selected period, the comparison window is EXACTLY the immediately preceding
 * period of equal length — contiguous with the selected period (ending the day before it starts) and
 * the same number of days long (Req 19.4) — and the growth value is computed against that window. When
 * the preceding period carries no data (or aggregates to a zero baseline), the growth is reported as
 * not-available rather than derived from a zero baseline (Req 19.5).
 *
 * <p>The oracle is independent of the production methods: the expected comparison window and the
 * expected growth ratio are recomputed directly from the requirement's definition (subtract one day
 * for the boundary, divide the delta by the baseline) rather than by re-invoking the methods under
 * test, so a regression in {@link PeriodTrend} cannot mask itself.
 *
 * <p>These exercise the pure helper directly — no Spring context is needed because every method
 * deterministically maps its inputs to a {@link PeriodRange} or {@link TrendResult}.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 44: Period-over-period trend uses the immediately preceding equal-length period")
class PeriodOverPeriodTrendPropertyTest {

    /**
     * Feature: advertising-workspace-rework, Property 44: Period-over-period trend uses the
     * immediately preceding equal-length period.
     *
     * <p>Validates: Requirements 19.4.
     *
     * <p>For any selected period, {@link PeriodTrend#precedingPeriod} returns EXACTLY the immediately
     * preceding equal-length window: it ends the day before the selected period starts (contiguous,
     * no gap, no overlap), it is the same number of days long, and it lies entirely before the
     * selected period.
     */
    @Property(tries = 200)
    void precedingPeriodIsTheImmediatelyPrecedingEqualLengthWindow(@ForAll("periods") PeriodRange selected) {
        PeriodRange preceding = PeriodTrend.precedingPeriod(selected);

        // Contiguous: the preceding window ends exactly the day before the selected window starts.
        assertThat(preceding.end()).isEqualTo(selected.start().minusDays(1));

        // Equal length: the comparison window spans the same number of days as the selected one.
        assertThat(preceding.lengthInDays()).isEqualTo(selected.lengthInDays());

        // Entirely before the selected period — never overlapping it.
        assertThat(preceding.end()).isBefore(selected.start());
        assertThat(preceding.start()).isBefore(selected.start());

        // Reconstruct the expected window straight from the definition: [s - n, s - 1].
        long n = selected.lengthInDays();
        assertThat(preceding.start()).isEqualTo(selected.start().minusDays(n));
        assertThat(ChronoUnit.DAYS.between(preceding.start(), preceding.end()) + 1).isEqualTo(n);
    }

    /**
     * Feature: advertising-workspace-rework, Property 44: Period-over-period trend uses the
     * immediately preceding equal-length period.
     *
     * <p>Validates: Requirements 19.4.
     *
     * <p>When the preceding period has data and a non-zero baseline, the growth is exactly
     * {@code (current - baseline) / baseline} at the canonical scale, and the result is marked
     * available with a non-null growth value.
     */
    @Property(tries = 200)
    void growthAgainstNonZeroBaselineEqualsTheDefinedRatio(
            @ForAll("aggregates") BigDecimal currentValue,
            @ForAll("nonZeroBaselines") BigDecimal baselineValue) {

        TrendResult result = PeriodTrend.growth(currentValue, baselineValue, true);

        BigDecimal expected = currentValue.subtract(baselineValue)
                .divide(baselineValue, PeriodTrend.GROWTH_SCALE, RoundingMode.HALF_UP);

        assertThat(result.available()).isTrue();
        assertThat(result.growth()).isNotNull();
        assertThat(result.growth().compareTo(expected)).isZero();
    }

    /**
     * Feature: advertising-workspace-rework, Property 44: Period-over-period trend uses the
     * immediately preceding equal-length period.
     *
     * <p>Validates: Requirements 19.5.
     *
     * <p>When the preceding comparison period has no data, the growth is reported as not-available
     * (no fabricated number), regardless of the current period's aggregate.
     */
    @Property(tries = 200)
    void noBaselineDataYieldsNotAvailable(@ForAll("aggregatesOrNull") BigDecimal currentValue,
                                          @ForAll("aggregatesOrNull") BigDecimal absentBaseline) {

        TrendResult result = PeriodTrend.growth(currentValue, absentBaseline, false);

        assertThat(result.available()).isFalse();
        assertThat(result.growth()).isNull();
    }

    /**
     * Feature: advertising-workspace-rework, Property 44: Period-over-period trend uses the
     * immediately preceding equal-length period.
     *
     * <p>Validates: Requirements 19.5.
     *
     * <p>A zero (or null) baseline aggregate is never used to derive a growth value: even when the
     * preceding period is flagged as having data, a zero baseline yields not-available rather than an
     * infinite or divide-by-zero growth.
     */
    @Property(tries = 200)
    void zeroOrNullBaselineYieldsNotAvailable(@ForAll("aggregates") BigDecimal currentValue,
                                              @ForAll("zeroOrNullBaselines") BigDecimal degenerateBaseline) {

        TrendResult result = PeriodTrend.growth(currentValue, degenerateBaseline, true);

        assertThat(result.available()).isFalse();
        assertThat(result.growth()).isNull();
    }

    // --- Worked examples from the requirement (Req 19.4, 19.5) ------------------------------------

    /** A selected 7-day period compares against the contiguous 7 days immediately before it (Req 19.4). */
    @Example
    void sevenDayPeriodComparesAgainstThePrecedingSevenDays() {
        PeriodRange selected = new PeriodRange(LocalDate.of(2024, 1, 8), LocalDate.of(2024, 1, 14));
        PeriodRange preceding = PeriodTrend.precedingPeriod(selected);

        assertThat(preceding.start()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(preceding.end()).isEqualTo(LocalDate.of(2024, 1, 7));
        assertThat(preceding.lengthInDays()).isEqualTo(7L);
    }

    /** A doubling from a 100 baseline to 200 current is +100% growth, i.e. ratio 1.0000 (Req 19.4). */
    @Example
    void doublingYieldsOneHundredPercentGrowth() {
        TrendResult result = PeriodTrend.growth(new BigDecimal("200"), new BigDecimal("100"), true);

        assertThat(result.available()).isTrue();
        assertThat(result.growth().compareTo(new BigDecimal("1.0000"))).isZero();
    }

    /** No baseline data means not-available, never a growth derived from a zero baseline (Req 19.5). */
    @Example
    void absentBaselineIsNotAvailable() {
        assertThat(PeriodTrend.growth(new BigDecimal("500"), null, false))
                .isEqualTo(TrendResult.NOT_AVAILABLE);
        assertThat(PeriodTrend.growth(new BigDecimal("500"), BigDecimal.ZERO, true))
                .isEqualTo(TrendResult.NOT_AVAILABLE);
    }

    // --- Generators -------------------------------------------------------------------------------

    /**
     * Inclusive reporting periods built from a start date and a length in days, so the preceding
     * window arithmetic is exercised across single-day and multi-day windows alike. Uses two
     * arbitraries (well within the {@code <= 8} Combinators limit).
     */
    @Provide
    Arbitrary<PeriodRange> periods() {
        Arbitrary<Long> startEpochDay = Arbitraries.longs()
                .between(LocalDate.of(2000, 1, 1).toEpochDay(), LocalDate.of(2100, 12, 31).toEpochDay());
        Arbitrary<Integer> lengthDays = Arbitraries.integers().between(1, 365);

        return Combinators.combine(startEpochDay, lengthDays).as((epochDay, length) -> {
            LocalDate start = LocalDate.ofEpochDay(epochDay);
            return new PeriodRange(start, start.plusDays(length - 1L));
        });
    }

    /** Non-negative monetary/metric aggregates at two decimals, spanning 0 to 10,000,000.00. */
    @Provide
    Arbitrary<BigDecimal> aggregates() {
        return Arbitraries.longs()
                .between(0L, 1_000_000_000L)
                .map(hundredths -> BigDecimal.valueOf(hundredths, 2));
    }

    /** Aggregates that may be {@code null}, to confirm a null current value is treated as zero. */
    @Provide
    Arbitrary<BigDecimal> aggregatesOrNull() {
        return Arbitraries.oneOf(
                aggregates(),
                Arbitraries.just(null));
    }

    /** Strictly positive baselines (never zero) at two decimals, so the growth ratio is defined. */
    @Provide
    Arbitrary<BigDecimal> nonZeroBaselines() {
        return Arbitraries.longs()
                .between(1L, 1_000_000_000L)
                .map(hundredths -> BigDecimal.valueOf(hundredths, 2));
    }

    /** Degenerate baselines that must never produce a growth value: exact zero (any scale) or null. */
    @Provide
    Arbitrary<BigDecimal> zeroOrNullBaselines() {
        return Arbitraries.oneOf(
                Arbitraries.of(
                        BigDecimal.ZERO,
                        new BigDecimal("0.00"),
                        new BigDecimal("0.0000"),
                        BigDecimal.valueOf(0L, 2)),
                Arbitraries.just(null));
    }
}
