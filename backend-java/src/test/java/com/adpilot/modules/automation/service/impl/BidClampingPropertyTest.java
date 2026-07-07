package com.adpilot.modules.automation.service.impl;

import com.adpilot.modules.automation.service.AutomationRunner;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.Tuple.Tuple2;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link AutomationRunner#clampBid(BigDecimal, BigDecimal, BigDecimal)}.
 *
 * Feature: core-platform-completion, Property 29: Adjusted bids are always
 * clamped within the rule's bounds.
 *
 * <p>For any proposed bid and rule bounds {@code [lower, upper]} with
 * {@code lower <= upper}, the adjusted bid the runner submits is always within
 * those bounds (Req 13.2.2, 13.2.5). A {@code null} bound is treated as
 * unbounded on that side, and a {@code null} value returns {@code null}.
 */
class BidClampingPropertyTest {

    /**
     * Feature: core-platform-completion, Property 29: Adjusted bids are always
     * clamped within the rule's bounds.
     *
     * Req 13.2.2 / 13.2.5: for any proposed bid and bounds with lower <= upper,
     * the clamped result is always within [lower, upper]; it equals the value
     * when already in range and equals the nearer bound when out of range.
     */
    @Property(tries = 200)
    void clampedBidIsAlwaysWithinBounds(@ForAll("boundedScenarios") Scenario scenario) {
        BigDecimal value = scenario.value();
        BigDecimal lower = scenario.lower();
        BigDecimal upper = scenario.upper();

        BigDecimal result = AutomationRunner.clampBid(value, lower, upper);

        // Always within the inclusive bounds.
        assertThat(result).isGreaterThanOrEqualTo(lower);
        assertThat(result).isLessThanOrEqualTo(upper);

        if (value.compareTo(lower) < 0) {
            // Below range -> nearer bound is the lower bound.
            assertThat(result).isEqualByComparingTo(lower);
        } else if (value.compareTo(upper) > 0) {
            // Above range -> nearer bound is the upper bound.
            assertThat(result).isEqualByComparingTo(upper);
        } else {
            // Already in range -> unchanged.
            assertThat(result).isEqualByComparingTo(value);
        }
    }

    /**
     * Feature: core-platform-completion, Property 29: Adjusted bids are always
     * clamped within the rule's bounds.
     *
     * Req 13.2.5: a {@code null} bound is unbounded on that side, so the result
     * is only constrained by the bound that is present.
     */
    @Property(tries = 200)
    void nullBoundIsUnboundedOnThatSide(
            @ForAll("bidValues") BigDecimal value,
            @ForAll("bidValues") BigDecimal bound) {

        // No bounds at all -> value passes through unchanged.
        assertThat(AutomationRunner.clampBid(value, null, null))
                .isEqualByComparingTo(value);

        // Only a lower bound -> never below it, but otherwise unconstrained above.
        BigDecimal lowerOnly = AutomationRunner.clampBid(value, bound, null);
        assertThat(lowerOnly).isGreaterThanOrEqualTo(bound);
        if (value.compareTo(bound) >= 0) {
            assertThat(lowerOnly).isEqualByComparingTo(value);
        } else {
            assertThat(lowerOnly).isEqualByComparingTo(bound);
        }

        // Only an upper bound -> never above it, but otherwise unconstrained below.
        BigDecimal upperOnly = AutomationRunner.clampBid(value, null, bound);
        assertThat(upperOnly).isLessThanOrEqualTo(bound);
        if (value.compareTo(bound) <= 0) {
            assertThat(upperOnly).isEqualByComparingTo(value);
        } else {
            assertThat(upperOnly).isEqualByComparingTo(bound);
        }
    }

    /**
     * Feature: core-platform-completion, Property 29: Adjusted bids are always
     * clamped within the rule's bounds.
     *
     * Req 13.2.5: a {@code null} value returns {@code null} regardless of bounds.
     */
    @Property(tries = 200)
    void nullValueAlwaysReturnsNull(
            @ForAll("nullableBounds") BigDecimal lower,
            @ForAll("nullableBounds") BigDecimal upper) {
        assertThat(AutomationRunner.clampBid(null, lower, upper)).isNull();
    }

    /** Proposed bids spanning a wide range, including negatives and zero, at 4-decimal scale. */
    @Provide
    Arbitrary<BigDecimal> bidValues() {
        return Arbitraries.longs()
                .between(-1_000_000L, 1_000_000L)
                .map(cents -> BigDecimal.valueOf(cents, 4));
    }

    /** A bound value or {@code null} (unbounded on that side). */
    @Provide
    Arbitrary<BigDecimal> nullableBounds() {
        return Arbitraries.oneOf(bidValues(), Arbitraries.just(null));
    }

    /**
     * A scenario with a value and an ordered pair of bounds (lower <= upper).
     * Generates values below, within, and above the range across iterations.
     */
    @Provide
    Arbitrary<Scenario> boundedScenarios() {
        Arbitrary<Tuple2<BigDecimal, BigDecimal>> orderedBounds =
                Combinators.combine(bidValues(), bidValues())
                        .as((a, b) -> a.compareTo(b) <= 0 ? Tuple.of(a, b) : Tuple.of(b, a));

        return Combinators.combine(bidValues(), orderedBounds)
                .as((value, bounds) -> new Scenario(value, bounds.get1(), bounds.get2()));
    }

    record Scenario(BigDecimal value, BigDecimal lower, BigDecimal upper) {
    }
}
