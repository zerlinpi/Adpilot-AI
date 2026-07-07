package com.adpilot.modules.advertising.support;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the per-limit {@code moreRestrictive(a, b)} operator
 * on {@link SafetyBoundaryLimit}.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 15: Per-limit more-restrictive operator.
 *
 * <p><b>Validates: Requirements 6.2</b>
 *
 * <p>For every {@link SafetyBoundaryLimit} the more-restrictive operator must satisfy:
 * <ol>
 *   <li>UPPER_BOUND limits: returns min(a, b)</li>
 *   <li>LOWER_BOUND limits: returns max(a, b)</li>
 *   <li>BOOLEAN_OR limits: returns 1 if either is non-zero, 0 otherwise</li>
 *   <li>Commutativity: moreRestrictive(a, b) == moreRestrictive(b, a)</li>
 *   <li>Idempotency: moreRestrictive(a, a) == a</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 15: Per-limit more-restrictive operator")
class SafetyBoundaryMoreRestrictivePropertyTest {

    // --- Generators --------------------------------------------------------

    @Provide
    Arbitrary<BigDecimal> values() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.valueOf(-10_000), BigDecimal.valueOf(10_000))
                .ofScale(4);
    }

    @Provide
    Arbitrary<SafetyBoundaryLimit> upperBoundLimits() {
        return Arbitraries.of(
                java.util.Arrays.stream(SafetyBoundaryLimit.values())
                        .filter(l -> l.comparisonSemantics() == BoundaryComparison.UPPER_BOUND)
                        .toArray(SafetyBoundaryLimit[]::new));
    }

    @Provide
    Arbitrary<SafetyBoundaryLimit> lowerBoundLimits() {
        return Arbitraries.of(
                java.util.Arrays.stream(SafetyBoundaryLimit.values())
                        .filter(l -> l.comparisonSemantics() == BoundaryComparison.LOWER_BOUND)
                        .toArray(SafetyBoundaryLimit[]::new));
    }

    @Provide
    Arbitrary<SafetyBoundaryLimit> booleanOrLimits() {
        return Arbitraries.of(
                java.util.Arrays.stream(SafetyBoundaryLimit.values())
                        .filter(l -> l.comparisonSemantics() == BoundaryComparison.BOOLEAN_OR)
                        .toArray(SafetyBoundaryLimit[]::new));
    }

    @Provide
    Arbitrary<SafetyBoundaryLimit> scalarLimits() {
        // All limits that are not SET_INTERSECTION (i.e., all current enum values)
        return Arbitraries.of(
                java.util.Arrays.stream(SafetyBoundaryLimit.values())
                        .filter(l -> l.comparisonSemantics() != BoundaryComparison.SET_INTERSECTION)
                        .toArray(SafetyBoundaryLimit[]::new));
    }

    // --- Properties --------------------------------------------------------

    /**
     * For UPPER_BOUND limits, moreRestrictive(a, b) returns min(a, b).
     */
    @Property(tries = 200)
    @Label("UPPER_BOUND: moreRestrictive returns min(a, b)")
    void upperBoundReturnsMin(
            @ForAll("upperBoundLimits") SafetyBoundaryLimit limit,
            @ForAll("values") BigDecimal a,
            @ForAll("values") BigDecimal b) {

        BigDecimal result = limit.moreRestrictive(a, b);
        BigDecimal expected = a.compareTo(b) <= 0 ? a : b;

        assertThat(result.compareTo(expected))
                .as("UPPER_BOUND limit %s: moreRestrictive(%s, %s) should be min = %s but was %s",
                        limit, a, b, expected, result)
                .isEqualTo(0);
    }

    /**
     * For LOWER_BOUND limits, moreRestrictive(a, b) returns max(a, b).
     */
    @Property(tries = 200)
    @Label("LOWER_BOUND: moreRestrictive returns max(a, b)")
    void lowerBoundReturnsMax(
            @ForAll("lowerBoundLimits") SafetyBoundaryLimit limit,
            @ForAll("values") BigDecimal a,
            @ForAll("values") BigDecimal b) {

        BigDecimal result = limit.moreRestrictive(a, b);
        BigDecimal expected = a.compareTo(b) >= 0 ? a : b;

        assertThat(result.compareTo(expected))
                .as("LOWER_BOUND limit %s: moreRestrictive(%s, %s) should be max = %s but was %s",
                        limit, a, b, expected, result)
                .isEqualTo(0);
    }

    /**
     * For BOOLEAN_OR limits, moreRestrictive(a, b) returns 1 if either is non-zero, else 0.
     */
    @Property(tries = 200)
    @Label("BOOLEAN_OR: moreRestrictive returns 1 if either non-zero")
    void booleanOrReturnsOneIfEitherNonZero(
            @ForAll("booleanOrLimits") SafetyBoundaryLimit limit,
            @ForAll("values") BigDecimal a,
            @ForAll("values") BigDecimal b) {

        BigDecimal result = limit.moreRestrictive(a, b);
        boolean eitherNonZero = a.compareTo(BigDecimal.ZERO) != 0
                || b.compareTo(BigDecimal.ZERO) != 0;
        BigDecimal expected = eitherNonZero ? BigDecimal.ONE : BigDecimal.ZERO;

        assertThat(result.compareTo(expected))
                .as("BOOLEAN_OR limit %s: moreRestrictive(%s, %s) should be %s but was %s",
                        limit, a, b, expected, result)
                .isEqualTo(0);
    }

    /**
     * The operator is commutative: moreRestrictive(a, b) == moreRestrictive(b, a).
     */
    @Property(tries = 200)
    @Label("Commutativity: moreRestrictive(a, b) == moreRestrictive(b, a)")
    void commutativity(
            @ForAll("scalarLimits") SafetyBoundaryLimit limit,
            @ForAll("values") BigDecimal a,
            @ForAll("values") BigDecimal b) {

        BigDecimal ab = limit.moreRestrictive(a, b);
        BigDecimal ba = limit.moreRestrictive(b, a);

        assertThat(ab.compareTo(ba))
                .as("Commutativity violated for %s: moreRestrictive(%s, %s)=%s != moreRestrictive(%s, %s)=%s",
                        limit, a, b, ab, b, a, ba)
                .isEqualTo(0);
    }

    /**
     * The operator is idempotent: moreRestrictive(a, a) == a.
     */
    @Property(tries = 200)
    @Label("Idempotency: moreRestrictive(a, a) == a")
    void idempotency(
            @ForAll("scalarLimits") SafetyBoundaryLimit limit,
            @ForAll("values") BigDecimal a) {

        BigDecimal result = limit.moreRestrictive(a, a);

        // For BOOLEAN_OR with non-zero a, the result is 1 (not necessarily a),
        // but idempotency in the boolean domain means applying it again gives
        // the same answer: moreRestrictive(result, result) == result.
        if (limit.comparisonSemantics() == BoundaryComparison.BOOLEAN_OR) {
            // Boolean idempotency: applying the operator to the result with itself yields the same result
            BigDecimal doubleApply = limit.moreRestrictive(result, result);
            assertThat(doubleApply.compareTo(result))
                    .as("BOOLEAN_OR idempotency for %s: moreRestrictive(moreRestrictive(%s,%s), moreRestrictive(%s,%s)) should equal moreRestrictive(%s,%s)",
                            limit, a, a, a, a, a, a)
                    .isEqualTo(0);
        } else {
            // For UPPER_BOUND and LOWER_BOUND, min(a,a)==a and max(a,a)==a
            assertThat(result.compareTo(a))
                    .as("Idempotency violated for %s: moreRestrictive(%s, %s)=%s, expected %s",
                            limit, a, a, result, a)
                    .isEqualTo(0);
        }
    }
}
