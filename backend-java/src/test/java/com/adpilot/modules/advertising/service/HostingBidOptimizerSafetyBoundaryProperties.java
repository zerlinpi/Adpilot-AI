package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.service.HostingBidOptimizer.SafeBidAdjustment;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryResolver;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link HostingBidOptimizer#adjustBidWithinBoundary}.
 *
 * <p>Feature: advertising-workspace-rework, Property 49: Safety_Boundary always
 * bounds the applied magnitude.
 *
 * <p>Validates: Requirements 22.3, 22.4, 22.6, 49.4, 49.10, 49.11.
 *
 * <p>For any AI hosting bid adjustment, the actual applied magnitude is at most
 * the minimum of the personality-allowed maximum magnitude
 * ({@code currentBid * maxChangePct/100}) and the resolved Safety_Boundary limit;
 * the adjusted value stays within the resolved {@code [minBid, maxBid]} window
 * (when the current bid starts in range); and no Safety_Boundary limit is ever
 * widened to admit an out-of-range value (when the current bid starts out of
 * range the result is clamped on the safe side of the violated boundary and the
 * Campaign is flagged).
 *
 * <p>The effective {@code [minBid, maxBid]} window is resolved through the
 * Safety_Boundary hierarchy: a limit defined on the boundary wins, otherwise the
 * System-default floor/ceiling applies (Req 49.11). Both branches are exercised.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 49: Safety_Boundary always bounds the applied magnitude")
class HostingBidOptimizerSafetyBoundaryProperties {

    /** Mirrors the helper's intermediate precision for the magnitude tolerance. */
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** Tiny tolerance to absorb HALF_UP rounding in the magnitude cap. */
    private static final BigDecimal EPSILON = new BigDecimal("0.0000000001");

    /**
     * Feature: advertising-workspace-rework, Property 49: Safety_Boundary always
     * bounds the applied magnitude.
     *
     * <p>Validates: Requirements 22.3, 22.4, 22.6, 49.4, 49.10, 49.11.
     */
    @Property(tries = 200)
    void safetyBoundaryAlwaysBoundsAppliedMagnitude(@ForAll("scenarios") Scenario s) {
        SafeBidAdjustment outcome = HostingBidOptimizer.adjustBidWithinBoundary(
                s.currentBid(), s.recentAcos(), s.targetAcos(),
                s.maxChangePct(), s.safetyBoundary(), s.defaultMinBid(), s.defaultMaxBid());

        BigDecimal result = outcome.getAdjustedBid();

        // The effective window the resolver yields (boundary value, else default).
        BigDecimal effectiveMin = s.safetyBoundary().get(SafetyBoundaryLimit.MIN_BID)
                .orElse(s.defaultMinBid());
        BigDecimal effectiveMax = s.safetyBoundary().get(SafetyBoundaryLimit.MAX_BID)
                .orElse(s.defaultMaxBid());

        // The resolver must surface exactly the window we routed through it
        // (Req 49.11) — confirms the boundary value wins over the default and the
        // default fills in any undefined limit.
        assertThat(effectiveMin).isEqualByComparingTo(s.expectedMin());
        assertThat(effectiveMax).isEqualByComparingTo(s.expectedMax());

        // (1) Personality magnitude cap (Req 49.4 / 49.10): the applied magnitude
        // never exceeds currentBid * maxChangePct/100, regardless of the boundary.
        BigDecimal personalityMagnitude = s.currentBid()
                .multiply(s.maxChangePct().divide(HUNDRED, MC))
                .abs()
                .add(EPSILON);
        BigDecimal appliedMagnitude = result.subtract(s.currentBid()).abs();
        assertThat(appliedMagnitude).isLessThanOrEqualTo(personalityMagnitude);

        int cmpHigh = s.currentBid().compareTo(effectiveMax);
        int cmpLow = s.currentBid().compareTo(effectiveMin);

        if (cmpHigh <= 0 && cmpLow >= 0) {
            // (2) In-range start: the adjusted value stays within the resolved
            // [minBid, maxBid] window (Req 22.3) and the Campaign is not flagged.
            assertThat(result).isGreaterThanOrEqualTo(effectiveMin);
            assertThat(result).isLessThanOrEqualTo(effectiveMax);
            assertThat(outcome.isFlagged()).isFalse();

            int dir = s.recentAcos().compareTo(s.targetAcos());
            if (dir > 0) {
                assertThat(result).isLessThanOrEqualTo(s.currentBid());
            } else if (dir < 0) {
                assertThat(result).isGreaterThanOrEqualTo(s.currentBid());
            } else {
                assertThat(result).isEqualByComparingTo(s.currentBid());
            }
        } else if (cmpHigh > 0) {
            // (3) Above the ceiling: the boundary is never widened to admit the
            // out-of-range value (Req 22.4). The result only moves toward the safe
            // range (down, never up) and never overshoots below the ceiling.
            assertThat(result).isLessThanOrEqualTo(s.currentBid());
            assertThat(result).isGreaterThanOrEqualTo(effectiveMax);
            assertThat(outcome.isFlagged()).isTrue();
        } else {
            // (4) Below the floor: symmetric — only moves up toward the floor,
            // never overshooting above it, and the boundary is not widened down.
            assertThat(result).isGreaterThanOrEqualTo(s.currentBid());
            assertThat(result).isLessThanOrEqualTo(effectiveMin);
            assertThat(outcome.isFlagged()).isTrue();
        }
    }

    // --- Generators ---------------------------------------------------------

    /** Non-negative bid at 4-decimal scale (0.0000 .. 100.0000). */
    @Provide
    Arbitrary<BigDecimal> nonNegativeBid() {
        return Arbitraries.longs().between(0L, 1_000_000L)
                .map(cents -> BigDecimal.valueOf(cents, 4));
    }

    /** Non-negative ACoS value at 2-decimal scale (0.00 .. 200.00 percent). */
    @Provide
    Arbitrary<BigDecimal> nonNegativeAcos() {
        return Arbitraries.longs().between(0L, 20_000L)
                .map(hundredths -> BigDecimal.valueOf(hundredths, 2));
    }

    /** Non-negative max change percentage at 2-decimal scale (0.00 .. 200.00). */
    @Provide
    Arbitrary<BigDecimal> nonNegativeMaxChangePct() {
        return Arbitraries.longs().between(0L, 20_000L)
                .map(hundredths -> BigDecimal.valueOf(hundredths, 2));
    }

    /**
     * A full scenario: an ordered effective window {@code [expectedMin, expectedMax]}
     * routed through the Safety_Boundary resolver either via boundary-defined limits
     * or via System defaults (both branches exercised); a current bid that may be
     * in-range, above the ceiling, or below the floor; plus arbitrary ACoS and a
     * non-negative personality magnitude.
     */
    @Provide
    Arbitrary<Scenario> scenarios() {
        // Ordered effective window [lo, hi].
        Arbitrary<BigDecimal[]> window = Combinators.combine(nonNegativeBid(), nonNegativeBid())
                .as((a, b) -> a.compareTo(b) <= 0
                        ? new BigDecimal[] {a, b}
                        : new BigDecimal[] {b, a});

        // Current bid spans the whole non-negative range so in-range AND
        // out-of-range (above ceiling / below floor) starts are all exercised.
        Arbitrary<BigDecimal> currentBid = nonNegativeBid();

        return Combinators.combine(
                        window,
                        currentBid,
                        nonNegativeAcos(),
                        nonNegativeAcos(),
                        nonNegativeMaxChangePct(),
                        Arbitraries.integers().between(0, 3))
                .as((win, current, recent, target, pct, sourceMix) -> {
                    BigDecimal lo = win[0];
                    BigDecimal hi = win[1];

                    // sourceMix bit 0 -> MIN_BID from boundary; bit 1 -> MAX_BID from
                    // boundary. The non-selected source supplies a wider value so the
                    // resolved window is still [lo, hi] only if precedence/fallback is
                    // correct (Req 49.11).
                    boolean minFromBoundary = (sourceMix & 1) != 0;
                    boolean maxFromBoundary = (sourceMix & 2) != 0;

                    // The boundary defines a limit only when "*FromBoundary"; otherwise
                    // it is left undefined so the optimizer's System-default fallback
                    // path (orElse) is genuinely exercised (Req 49.11).
                    SafetyBoundaryLimits.Builder campaign = SafetyBoundaryLimits.builder();
                    BigDecimal defaultMin;
                    BigDecimal defaultMax;

                    if (minFromBoundary) {
                        campaign.minBid(lo);
                        // Wider (lower) default that the boundary must override.
                        defaultMin = BigDecimal.ZERO;
                    } else {
                        // Boundary leaves MIN_BID undefined -> falls back to this default.
                        defaultMin = lo;
                    }

                    if (maxFromBoundary) {
                        campaign.maxBid(hi);
                        // Wider (higher) default that the boundary must override.
                        defaultMax = hi.add(BigDecimal.valueOf(1_000));
                    } else {
                        defaultMax = hi;
                    }

                    SafetyBoundary boundary = SafetyBoundaryResolver.resolve(
                            campaign.build(),
                            SafetyBoundaryLimits.empty(),
                            SafetyBoundaryLimits.empty(),
                            SafetyBoundaryLimits.empty());

                    return new Scenario(current, recent, target, pct,
                            boundary, defaultMin, defaultMax, lo, hi);
                });
    }

    record Scenario(BigDecimal currentBid,
                    BigDecimal recentAcos,
                    BigDecimal targetAcos,
                    BigDecimal maxChangePct,
                    SafetyBoundary safetyBoundary,
                    BigDecimal defaultMinBid,
                    BigDecimal defaultMaxBid,
                    BigDecimal expectedMin,
                    BigDecimal expectedMax) {
    }
}
