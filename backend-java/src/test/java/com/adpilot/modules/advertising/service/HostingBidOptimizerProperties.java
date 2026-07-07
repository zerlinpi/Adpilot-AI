package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.service.HostingBidOptimizer.SafeBidAdjustment;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryResolver;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link HostingBidOptimizer}.
 *
 * <p>Feature: app-functionality-completion, Property 4: AI-hosting bid adjustment
 * moves toward Target ACoS within bounds.
 *
 * <p>Validates: Requirements 21.2, 26.3.
 *
 * <p>For any non-negative bids/ACoS with {@code currentBid} inside
 * {@code [minBid, maxBid]}, {@code minBid <= maxBid} and {@code maxChangePct >= 0},
 * the adjusted bid is simultaneously:
 * <ul>
 *   <li>within the absolute range {@code [minBid, maxBid]};</li>
 *   <li>within &plusmn;{@code maxChangePct} of {@code currentBid};</li>
 *   <li>never increased when {@code recentAcos > targetAcos} (moves down toward target);</li>
 *   <li>never decreased when {@code recentAcos < targetAcos} (moves up toward target);</li>
 *   <li>a no-op when {@code recentAcos == targetAcos}.</li>
 * </ul>
 *
 * <p>The same range clamp ({@link HostingBidOptimizer#clampToRange}) used by
 * placement-lock strategies keeps a chosen bid within its configured
 * {@code [bidMin, bidMax]} window (Req 26.3).
 *
 * <p>The companion example-based tests live in {@link HostingBidOptimizerTest}.
 */
class HostingBidOptimizerProperties {

    /** Mirrors the helper's intermediate precision for the band tolerance. */
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** Tiny tolerance to absorb HALF_UP rounding in the percentage band. */
    private static final BigDecimal EPSILON = new BigDecimal("0.0000000001");

    /**
     * Feature: app-functionality-completion, Property 4: AI-hosting bid adjustment
     * moves toward Target ACoS within bounds.
     *
     * <p>Validates: Requirements 21.2, 26.3.
     *
     * <p>Asserts all four directional/bound guarantees for an arbitrary
     * (in-range currentBid, recentAcos, targetAcos, maxChangePct) scenario.
     */
    @Property(tries = 200)
    void adjustedBidStaysWithinBoundsAndMovesTowardTarget(@ForAll("scenarios") Scenario s) {
        BigDecimal result = HostingBidOptimizer.adjustBid(
                s.currentBid(), s.recentAcos(), s.targetAcos(),
                s.minBid(), s.maxBid(), s.maxChangePct());

        // (1) Always within the absolute permitted range.
        assertThat(result).isGreaterThanOrEqualTo(s.minBid());
        assertThat(result).isLessThanOrEqualTo(s.maxBid());

        // (2) Always within +/- maxChangePct of the current bid (with rounding tolerance).
        BigDecimal maxStep = s.currentBid()
                .multiply(s.maxChangePct().divide(HUNDRED, MC))
                .abs()
                .add(EPSILON);
        BigDecimal delta = result.subtract(s.currentBid()).abs();
        assertThat(delta).isLessThanOrEqualTo(maxStep);

        int dir = s.recentAcos().compareTo(s.targetAcos());
        if (dir > 0) {
            // (3) ACoS above target -> too expensive -> never increases.
            assertThat(result).isLessThanOrEqualTo(s.currentBid());
        } else if (dir < 0) {
            // (4) ACoS below target -> room to spend -> never decreases.
            assertThat(result).isGreaterThanOrEqualTo(s.currentBid());
        } else {
            // (no-op) ACoS equal to target -> fixpoint.
            assertThat(result).isEqualByComparingTo(s.currentBid());
        }
    }

    /**
     * Feature: app-functionality-completion, Property 4: AI-hosting bid adjustment
     * moves toward Target ACoS within bounds.
     *
     * <p>Validates: Requirements 21.2, 26.3.
     *
     * <p>When {@code recentAcos == targetAcos} the adjusted bid is exactly the
     * current bid, for any range and any non-negative maxChangePct.
     */
    @Property(tries = 200)
    void noOpWhenAcosEqualsTarget(
            @ForAll("scenarios") Scenario s,
            @ForAll("nonNegativeAcos") BigDecimal acos) {
        BigDecimal result = HostingBidOptimizer.adjustBid(
                s.currentBid(), acos, acos, s.minBid(), s.maxBid(), s.maxChangePct());
        assertThat(result).isEqualByComparingTo(s.currentBid());
    }

    /**
     * Feature: app-functionality-completion, Property 4: AI-hosting bid adjustment
     * moves toward Target ACoS within bounds.
     *
     * <p>Validates: Requirements 21.2, 26.3.
     *
     * <p>Placement-lock clamp: for any non-negative window {@code [bidMin, bidMax]}
     * with {@code bidMin <= bidMax} and any non-negative proposed bid, the clamped
     * result lies within {@code [bidMin, bidMax]} and is unchanged when already inside.
     */
    @Property(tries = 200)
    void clampToRangeKeepsPlacementLockBidsWithinWindow(
            @ForAll("nonNegativeBid") BigDecimal bid,
            @ForAll("orderedWindow") BigDecimal[] window) {
        BigDecimal min = window[0];
        BigDecimal max = window[1];

        BigDecimal result = HostingBidOptimizer.clampToRange(bid, min, max);

        assertThat(result).isGreaterThanOrEqualTo(min);
        assertThat(result).isLessThanOrEqualTo(max);

        if (bid.compareTo(min) < 0) {
            assertThat(result).isEqualByComparingTo(min);
        } else if (bid.compareTo(max) > 0) {
            assertThat(result).isEqualByComparingTo(max);
        } else {
            assertThat(result).isEqualByComparingTo(bid);
        }
    }

    /**
     * Feature: advertising-workspace-rework, Property 50: Out-of-range starting
     * values only move toward the safe range.
     *
     * <p>Validates: Requirements 22.5.
     *
     * <p>When a Campaign's current bid is already outside its hard
     * Safety_Boundary, {@link HostingBidOptimizer#adjustBidWithinBoundary} MUST
     * only nudge the value <em>toward</em> the safe range and MUST flag the
     * Campaign. Concretely, for any out-of-range {@code currentBid}:
     * <ul>
     *   <li>when {@code currentBid > effectiveMax}: the result never increases
     *       (never moves further out) and never drops below {@code effectiveMax}
     *       (never overshoots past the nearest boundary), i.e.
     *       {@code effectiveMax <= result <= currentBid};</li>
     *   <li>when {@code currentBid < effectiveMin}: the result never decreases
     *       and never rises above {@code effectiveMin}, i.e.
     *       {@code currentBid <= result <= effectiveMin};</li>
     *   <li>{@link SafeBidAdjustment#isFlagged()} is always {@code true}.</li>
     * </ul>
     */
    @Property(tries = 200)
    void outOfRangeBidsMoveOnlyTowardSafeRangeAndAreFlagged(
            @ForAll("outOfRangeScenarios") OutOfRangeScenario s) {
        SafeBidAdjustment adjustment = HostingBidOptimizer.adjustBidWithinBoundary(
                s.currentBid(), s.recentAcos(), s.targetAcos(), s.maxChangePct(),
                s.safetyBoundary(), s.defaultMinBid(), s.defaultMaxBid());

        BigDecimal result = adjustment.getAdjustedBid();

        // An already-out-of-range value MUST flag the Campaign (Req 22.5).
        assertThat(adjustment.isFlagged()).isTrue();

        if (s.currentBid().compareTo(s.effectiveMax()) > 0) {
            // Above the ceiling: only ever move down toward the range...
            assertThat(result).isLessThanOrEqualTo(s.currentBid());
            // ...and never past the nearest boundary (the ceiling) into the range.
            assertThat(result).isGreaterThanOrEqualTo(s.effectiveMax());
        } else {
            // Below the floor: only ever move up toward the range...
            assertThat(result).isGreaterThanOrEqualTo(s.currentBid());
            // ...and never past the nearest boundary (the floor) into the range.
            assertThat(result).isLessThanOrEqualTo(s.effectiveMin());
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

    /** An ordered, non-negative window {@code [min, max]} with min <= max. */
    @Provide
    Arbitrary<BigDecimal[]> orderedWindow() {
        return Combinators.combine(nonNegativeBid(), nonNegativeBid())
                .as((a, b) -> a.compareTo(b) <= 0
                        ? new BigDecimal[] {a, b}
                        : new BigDecimal[] {b, a});
    }

    /**
     * A full scenario: three sorted non-negative bids guarantee
     * {@code minBid <= currentBid <= maxBid}; plus arbitrary non-negative
     * recent/target ACoS and a non-negative maxChangePct.
     */
    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<BigDecimal[]> sortedBids = nonNegativeBid().list().ofSize(3)
                .map(list -> {
                    List<BigDecimal> sorted = new ArrayList<>(list);
                    sorted.sort(BigDecimal::compareTo);
                    return new BigDecimal[] {sorted.get(0), sorted.get(1), sorted.get(2)};
                });

        return Combinators.combine(
                        sortedBids,
                        nonNegativeAcos(),
                        nonNegativeAcos(),
                        nonNegativeMaxChangePct())
                .as((bids, recent, target, pct) ->
                        new Scenario(bids[0], bids[1], bids[2], recent, target, pct));
    }

    /**
     * An out-of-range scenario: an effective hard window {@code [effectiveMin,
     * effectiveMax]} (defined directly on the Safety_Boundary) together with a
     * {@code currentBid} placed strictly <em>outside</em> that window — either
     * above the ceiling or below the floor — plus arbitrary non-negative
     * recent/target ACoS and a non-negative maxChangePct.
     */
    @Provide
    Arbitrary<OutOfRangeScenario> outOfRangeScenarios() {
        return Combinators.combine(
                        orderedWindow(),
                        Arbitraries.longs().between(1L, 1_000_000L), // strictly positive offset (cents)
                        Arbitraries.of(true, false),                 // place above ceiling vs below floor
                        nonNegativeAcos(),
                        nonNegativeAcos(),
                        nonNegativeMaxChangePct())
                .as((window, offsetCents, above, recent, target, pct) -> {
                    BigDecimal min = window[0];
                    BigDecimal max = window[1];
                    long minCents = min.movePointRight(4).longValueExact();

                    BigDecimal currentBid;
                    if (above || minCents == 0L) {
                        // Strictly above the ceiling: max + positive offset.
                        currentBid = max.add(BigDecimal.valueOf(offsetCents, 4));
                    } else {
                        // Strictly below the floor: a non-negative value in [0, min).
                        long belowCents = (minCents - 1L) - (offsetCents % minCents);
                        currentBid = BigDecimal.valueOf(belowCents, 4);
                    }

                    SafetyBoundary boundary = SafetyBoundaryResolver.resolve(
                            SafetyBoundaryLimits.builder().minBid(min).maxBid(max).build(),
                            null, null, null);

                    // Defaults are non-null but unused here: the boundary defines both limits.
                    BigDecimal defaultMin = BigDecimal.ZERO;
                    BigDecimal defaultMax = new BigDecimal("1000000");

                    return new OutOfRangeScenario(
                            currentBid, recent, target, pct, boundary, defaultMin, defaultMax, min, max);
                });
    }

    record Scenario(BigDecimal minBid,
                    BigDecimal currentBid,
                    BigDecimal maxBid,
                    BigDecimal recentAcos,
                    BigDecimal targetAcos,
                    BigDecimal maxChangePct) {
    }

    record OutOfRangeScenario(BigDecimal currentBid,
                              BigDecimal recentAcos,
                              BigDecimal targetAcos,
                              BigDecimal maxChangePct,
                              SafetyBoundary safetyBoundary,
                              BigDecimal defaultMinBid,
                              BigDecimal defaultMaxBid,
                              BigDecimal effectiveMin,
                              BigDecimal effectiveMax) {
    }
}
