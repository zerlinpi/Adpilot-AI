package com.adpilot.modules.advertising.support;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link RankQuota}.
 *
 * <p>Feature: app-functionality-completion, Property 9: Rank-monitor quota
 * boundary.
 *
 * <p>Validates: Requirements 28.4.
 *
 * <p>Adding a rank-monitor task is permitted <strong>iff</strong> the consumed
 * count is strictly less than the total quota. At the boundary where
 * {@code consumed == total} (and beyond), any further add is rejected. These
 * properties pin the boundary precisely and cross-check the helper against an
 * independent reference, exercising the exact equality point on every run.
 */
class RankQuotaPropertyTest {

    /**
     * Feature: app-functionality-completion, Property 9: Rank-monitor quota
     * boundary.
     *
     * <p>Validates: Requirements 28.4.
     *
     * <p>For any consumed/total pair, an add is permitted exactly when
     * {@code consumed < total}, cross-checked against an independent reference,
     * and {@link RankQuota#isExhausted} is its exact complement.
     */
    @Property(tries = 200)
    void permitsAddIffConsumedStrictlyLessThanTotal(
            @ForAll("counts") int consumed,
            @ForAll("counts") int total) {

        boolean expected = consumed < total;
        boolean actual = RankQuota.permitsAdd(consumed, total);

        assertThat(actual).isEqualTo(expected);
        // Exhausted is the exact complement of permitsAdd.
        assertThat(RankQuota.isExhausted(consumed, total)).isEqualTo(!expected);
    }

    /**
     * Feature: app-functionality-completion, Property 9: Rank-monitor quota
     * boundary.
     *
     * <p>Validates: Requirements 28.4.
     *
     * <p>At the boundary {@code consumed == total} any further add is rejected,
     * and one slot below the boundary an add is always permitted. This forces
     * the exact equality point — the place a fence-post bug would hide — to be
     * tested on every run across the full range of quota sizes.
     */
    @Property(tries = 200)
    void rejectsAtBoundaryPermitsJustBelow(@ForAll("nonNegativeTotals") int total) {
        // At the boundary: consumed == total => rejected.
        assertThat(RankQuota.permitsAdd(total, total)).isFalse();
        assertThat(RankQuota.isExhausted(total, total)).isTrue();
        assertThat(RankQuota.remaining(total, total)).isZero();

        // Beyond the boundary: consumed > total => still rejected, remaining clamps to 0.
        assertThat(RankQuota.permitsAdd(total + 1, total)).isFalse();
        assertThat(RankQuota.remaining(total + 1, total)).isZero();

        // Just below the boundary: consumed == total - 1 => permitted (when a slot exists).
        if (total > 0) {
            assertThat(RankQuota.permitsAdd(total - 1, total)).isTrue();
            assertThat(RankQuota.isExhausted(total - 1, total)).isFalse();
            assertThat(RankQuota.remaining(total - 1, total)).isEqualTo(1);
        }
    }

    // --- Generators ---------------------------------------------------------

    /**
     * Counts spanning negative, zero, and positive values (and the equality
     * region around it) so the boundary is straddled frequently. Capped to a
     * modest range to keep {@code consumed == total} collisions common.
     */
    @Provide
    Arbitrary<Integer> counts() {
        return Arbitraries.integers().between(-5, 50);
    }

    /** Non-negative totals used to anchor the exact boundary checks. */
    @Provide
    Arbitrary<Integer> nonNegativeTotals() {
        return Arbitraries.integers().between(0, 1000);
    }
}
