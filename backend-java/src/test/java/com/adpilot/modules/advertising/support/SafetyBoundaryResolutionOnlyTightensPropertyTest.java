package com.adpilot.modules.advertising.support;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the boundary resolution "only-tightening" invariant:
 * the resolved boundary from {@link SafetyBoundaryResolver} is always at least
 * as restrictive as any individual level's value.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 16: Boundary resolution only tightens.
 *
 * <p><b>Validates: Requirements 6.4, 21.4</b>
 *
 * <p>For each limit, the resolved value from a multi-level hierarchy is the
 * most-restrictive (per comparison semantics) of all defined values:
 * <ul>
 *   <li>Upper-bound limits: resolved ≤ every individual level value</li>
 *   <li>Lower-bound limits: resolved ≥ every individual level value</li>
 *   <li>Boolean OR: resolved = 1 (true) if any level is non-zero (true)</li>
 * </ul>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 16: Boundary resolution only tightens")
class SafetyBoundaryResolutionOnlyTightensPropertyTest {

    // --- Generators --------------------------------------------------------

    @Provide
    Arbitrary<BigDecimal> positiveValues() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.valueOf(1), BigDecimal.valueOf(10_000))
                .ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> booleanValues() {
        return Arbitraries.of(BigDecimal.ZERO, BigDecimal.ONE);
    }

    @Provide
    Arbitrary<SafetyBoundaryLimit> upperBoundLimits() {
        return Arbitraries.of(
                Arrays.stream(SafetyBoundaryLimit.values())
                        .filter(l -> l.comparisonSemantics() == BoundaryComparison.UPPER_BOUND)
                        .toArray(SafetyBoundaryLimit[]::new));
    }

    @Provide
    Arbitrary<SafetyBoundaryLimit> lowerBoundLimits() {
        return Arbitraries.of(
                Arrays.stream(SafetyBoundaryLimit.values())
                        .filter(l -> l.comparisonSemantics() == BoundaryComparison.LOWER_BOUND)
                        .toArray(SafetyBoundaryLimit[]::new));
    }

    @Provide
    Arbitrary<SafetyBoundaryLimit> booleanOrLimits() {
        return Arbitraries.of(
                Arrays.stream(SafetyBoundaryLimit.values())
                        .filter(l -> l.comparisonSemantics() == BoundaryComparison.BOOLEAN_OR)
                        .toArray(SafetyBoundaryLimit[]::new));
    }

    /**
     * Generates an optional BigDecimal value (present or absent) to model
     * a level that may or may not define the limit.
     */
    @Provide
    Arbitrary<Optional<BigDecimal>> optionalPositiveValue() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.valueOf(1), BigDecimal.valueOf(10_000))
                .ofScale(2)
                .optional();
    }

    @Provide
    Arbitrary<Optional<BigDecimal>> optionalBooleanValue() {
        return Arbitraries.of(BigDecimal.ZERO, BigDecimal.ONE).optional();
    }

    // --- Properties --------------------------------------------------------

    /**
     * For UPPER_BOUND limits: the resolved value is ≤ every individual level's value.
     * This means the resolver picks the minimum (most restrictive) across all defining levels.
     */
    @Property(tries = 200)
    @Label("UPPER_BOUND: resolved value ≤ every contributing level value")
    void upperBoundResolvedNeverExceedsAnyLevel(
            @ForAll("upperBoundLimits") SafetyBoundaryLimit limit,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> campaignVal,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> goalVal,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> storeVal,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> orgVal,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> systemVal) {

        // Build level contributions
        SafetyBoundaryLimits campaign = buildLimits(limit, campaignVal);
        SafetyBoundaryLimits goal = buildLimits(limit, goalVal);
        SafetyBoundaryLimits store = buildLimits(limit, storeVal);
        SafetyBoundaryLimits org = buildLimits(limit, orgVal);
        SafetyBoundaryLimits system = buildLimits(limit, systemVal);

        // Resolve
        SafetyBoundary resolved = SafetyBoundaryResolver.resolve(campaign, goal, store, org, system);

        // If no level defines the limit, the resolved value is absent
        if (campaignVal.isEmpty() && goalVal.isEmpty() && storeVal.isEmpty()
                && orgVal.isEmpty() && systemVal.isEmpty()) {
            assertThat(resolved.isDefined(limit))
                    .as("No level defines %s, so resolved should be absent", limit)
                    .isFalse();
            return;
        }

        // The resolved value must exist
        assertThat(resolved.isDefined(limit))
                .as("At least one level defines %s, so resolved should be defined", limit)
                .isTrue();

        BigDecimal resolvedValue = resolved.get(limit).orElseThrow();

        // resolved ≤ every defined level value (smaller = more restrictive for UPPER_BOUND)
        campaignVal.ifPresent(v -> assertThat(resolvedValue.compareTo(v))
                .as("UPPER_BOUND %s: resolved %s should be ≤ campaign value %s", limit, resolvedValue, v)
                .isLessThanOrEqualTo(0));
        goalVal.ifPresent(v -> assertThat(resolvedValue.compareTo(v))
                .as("UPPER_BOUND %s: resolved %s should be ≤ goal value %s", limit, resolvedValue, v)
                .isLessThanOrEqualTo(0));
        storeVal.ifPresent(v -> assertThat(resolvedValue.compareTo(v))
                .as("UPPER_BOUND %s: resolved %s should be ≤ store value %s", limit, resolvedValue, v)
                .isLessThanOrEqualTo(0));
        orgVal.ifPresent(v -> assertThat(resolvedValue.compareTo(v))
                .as("UPPER_BOUND %s: resolved %s should be ≤ organization value %s", limit, resolvedValue, v)
                .isLessThanOrEqualTo(0));
        systemVal.ifPresent(v -> assertThat(resolvedValue.compareTo(v))
                .as("UPPER_BOUND %s: resolved %s should be ≤ system value %s", limit, resolvedValue, v)
                .isLessThanOrEqualTo(0));
    }

    /**
     * For LOWER_BOUND limits: the resolved value is ≥ every individual level's value.
     * This means the resolver picks the maximum (most restrictive) across all defining levels.
     */
    @Property(tries = 200)
    @Label("LOWER_BOUND: resolved value ≥ every contributing level value")
    void lowerBoundResolvedNeverBelowAnyLevel(
            @ForAll("lowerBoundLimits") SafetyBoundaryLimit limit,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> campaignVal,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> goalVal,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> storeVal,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> orgVal,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> systemVal) {

        // Build level contributions
        SafetyBoundaryLimits campaign = buildLimits(limit, campaignVal);
        SafetyBoundaryLimits goal = buildLimits(limit, goalVal);
        SafetyBoundaryLimits store = buildLimits(limit, storeVal);
        SafetyBoundaryLimits org = buildLimits(limit, orgVal);
        SafetyBoundaryLimits system = buildLimits(limit, systemVal);

        // Resolve
        SafetyBoundary resolved = SafetyBoundaryResolver.resolve(campaign, goal, store, org, system);

        // If no level defines the limit, the resolved value is absent
        if (campaignVal.isEmpty() && goalVal.isEmpty() && storeVal.isEmpty()
                && orgVal.isEmpty() && systemVal.isEmpty()) {
            assertThat(resolved.isDefined(limit))
                    .as("No level defines %s, so resolved should be absent", limit)
                    .isFalse();
            return;
        }

        // The resolved value must exist
        assertThat(resolved.isDefined(limit))
                .as("At least one level defines %s, so resolved should be defined", limit)
                .isTrue();

        BigDecimal resolvedValue = resolved.get(limit).orElseThrow();

        // resolved ≥ every defined level value (larger = more restrictive for LOWER_BOUND)
        campaignVal.ifPresent(v -> assertThat(resolvedValue.compareTo(v))
                .as("LOWER_BOUND %s: resolved %s should be ≥ campaign value %s", limit, resolvedValue, v)
                .isGreaterThanOrEqualTo(0));
        goalVal.ifPresent(v -> assertThat(resolvedValue.compareTo(v))
                .as("LOWER_BOUND %s: resolved %s should be ≥ goal value %s", limit, resolvedValue, v)
                .isGreaterThanOrEqualTo(0));
        storeVal.ifPresent(v -> assertThat(resolvedValue.compareTo(v))
                .as("LOWER_BOUND %s: resolved %s should be ≥ store value %s", limit, resolvedValue, v)
                .isGreaterThanOrEqualTo(0));
        orgVal.ifPresent(v -> assertThat(resolvedValue.compareTo(v))
                .as("LOWER_BOUND %s: resolved %s should be ≥ organization value %s", limit, resolvedValue, v)
                .isGreaterThanOrEqualTo(0));
        systemVal.ifPresent(v -> assertThat(resolvedValue.compareTo(v))
                .as("LOWER_BOUND %s: resolved %s should be ≥ system value %s", limit, resolvedValue, v)
                .isGreaterThanOrEqualTo(0));
    }

    /**
     * For BOOLEAN_OR limits: if any level enables it (non-zero), the resolved value is 1 (true).
     * Only when all contributing levels are zero (or absent) is the resolved value 0 (false).
     */
    @Property(tries = 200)
    @Label("BOOLEAN_OR: resolved is true if any level is true")
    void booleanOrResolvedTrueIfAnyLevelTrue(
            @ForAll("booleanOrLimits") SafetyBoundaryLimit limit,
            @ForAll("optionalBooleanValue") Optional<BigDecimal> campaignVal,
            @ForAll("optionalBooleanValue") Optional<BigDecimal> goalVal,
            @ForAll("optionalBooleanValue") Optional<BigDecimal> storeVal,
            @ForAll("optionalBooleanValue") Optional<BigDecimal> orgVal,
            @ForAll("optionalBooleanValue") Optional<BigDecimal> systemVal) {

        // Build level contributions
        SafetyBoundaryLimits campaign = buildLimits(limit, campaignVal);
        SafetyBoundaryLimits goal = buildLimits(limit, goalVal);
        SafetyBoundaryLimits store = buildLimits(limit, storeVal);
        SafetyBoundaryLimits org = buildLimits(limit, orgVal);
        SafetyBoundaryLimits system = buildLimits(limit, systemVal);

        // Resolve
        SafetyBoundary resolved = SafetyBoundaryResolver.resolve(campaign, goal, store, org, system);

        // If no level defines the limit, the resolved value is absent
        if (campaignVal.isEmpty() && goalVal.isEmpty() && storeVal.isEmpty()
                && orgVal.isEmpty() && systemVal.isEmpty()) {
            assertThat(resolved.isDefined(limit))
                    .as("No level defines %s, so resolved should be absent", limit)
                    .isFalse();
            return;
        }

        // The resolved value must exist
        assertThat(resolved.isDefined(limit))
                .as("At least one level defines %s, so resolved should be defined", limit)
                .isTrue();

        BigDecimal resolvedValue = resolved.get(limit).orElseThrow();

        // Check if any level enables it
        boolean anyEnabled = campaignVal.map(v -> v.compareTo(BigDecimal.ZERO) != 0).orElse(false)
                || goalVal.map(v -> v.compareTo(BigDecimal.ZERO) != 0).orElse(false)
                || storeVal.map(v -> v.compareTo(BigDecimal.ZERO) != 0).orElse(false)
                || orgVal.map(v -> v.compareTo(BigDecimal.ZERO) != 0).orElse(false)
                || systemVal.map(v -> v.compareTo(BigDecimal.ZERO) != 0).orElse(false);

        if (anyEnabled) {
            assertThat(resolvedValue.compareTo(BigDecimal.ONE))
                    .as("BOOLEAN_OR %s: any level enables it, resolved should be 1 but was %s",
                            limit, resolvedValue)
                    .isEqualTo(0);
        } else {
            assertThat(resolvedValue.compareTo(BigDecimal.ZERO))
                    .as("BOOLEAN_OR %s: all levels are false, resolved should be 0 but was %s",
                            limit, resolvedValue)
                    .isEqualTo(0);
        }
    }

    /**
     * For any limit and any 5-level hierarchy, the resolved value equals the
     * result of folding all defined level values through moreRestrictive.
     * This is the universal "only tightens" check — the resolved value is exactly
     * the most-restrictive intersection of all contributing values.
     */
    @Property(tries = 200)
    @Label("Resolved value equals fold of moreRestrictive across all defined levels")
    void resolvedEqualsMoreRestrictiveFold(
            @ForAll("upperBoundLimits") SafetyBoundaryLimit limit,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> campaignVal,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> goalVal,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> storeVal,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> orgVal,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> systemVal) {

        SafetyBoundaryLimits campaign = buildLimits(limit, campaignVal);
        SafetyBoundaryLimits goal = buildLimits(limit, goalVal);
        SafetyBoundaryLimits store = buildLimits(limit, storeVal);
        SafetyBoundaryLimits org = buildLimits(limit, orgVal);
        SafetyBoundaryLimits system = buildLimits(limit, systemVal);

        SafetyBoundary resolved = SafetyBoundaryResolver.resolve(campaign, goal, store, org, system);

        // Compute expected by folding all present values
        BigDecimal expected = null;
        for (Optional<BigDecimal> val : new Optional[]{campaignVal, goalVal, storeVal, orgVal, systemVal}) {
            if (val.isPresent()) {
                expected = expected == null ? val.get() : limit.moreRestrictive(expected, val.get());
            }
        }

        if (expected == null) {
            assertThat(resolved.isDefined(limit)).isFalse();
        } else {
            assertThat(resolved.isDefined(limit)).isTrue();
            assertThat(resolved.get(limit).orElseThrow().compareTo(expected))
                    .as("Resolved %s should equal moreRestrictive fold: expected %s, got %s",
                            limit, expected, resolved.get(limit).orElseThrow())
                    .isEqualTo(0);
        }
    }

    /**
     * Resolution via the Map-based overload produces the same result as the
     * 5-parameter overload, confirming consistency of the API entry points.
     */
    @Property(tries = 100)
    @Label("Map-based resolve matches 5-param resolve")
    void mapOverloadMatchesFiveParamOverload(
            @ForAll("upperBoundLimits") SafetyBoundaryLimit limit,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> campaignVal,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> goalVal,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> storeVal,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> orgVal,
            @ForAll("optionalPositiveValue") Optional<BigDecimal> systemVal) {

        SafetyBoundaryLimits campaign = buildLimits(limit, campaignVal);
        SafetyBoundaryLimits goal = buildLimits(limit, goalVal);
        SafetyBoundaryLimits store = buildLimits(limit, storeVal);
        SafetyBoundaryLimits org = buildLimits(limit, orgVal);
        SafetyBoundaryLimits system = buildLimits(limit, systemVal);

        // Resolve via both APIs
        SafetyBoundary fromFiveParam = SafetyBoundaryResolver.resolve(campaign, goal, store, org, system);

        Map<SafetyBoundaryLevel, SafetyBoundaryLimits> map = new EnumMap<>(SafetyBoundaryLevel.class);
        map.put(SafetyBoundaryLevel.CAMPAIGN_OVERRIDE, campaign);
        map.put(SafetyBoundaryLevel.GOAL_BOUNDARY, goal);
        map.put(SafetyBoundaryLevel.STORE_POLICY, store);
        map.put(SafetyBoundaryLevel.ORGANIZATION_POLICY, org);
        map.put(SafetyBoundaryLevel.SYSTEM_DEFAULT, system);
        SafetyBoundary fromMap = SafetyBoundaryResolver.resolve(map);

        // Both must agree
        if (fromFiveParam.isDefined(limit)) {
            assertThat(fromMap.isDefined(limit)).isTrue();
            assertThat(fromMap.get(limit).orElseThrow().compareTo(fromFiveParam.get(limit).orElseThrow()))
                    .as("Map and 5-param APIs must agree for %s", limit)
                    .isEqualTo(0);
        } else {
            assertThat(fromMap.isDefined(limit)).isFalse();
        }
    }

    // --- Helpers ------------------------------------------------------------

    private SafetyBoundaryLimits buildLimits(SafetyBoundaryLimit limit, Optional<BigDecimal> value) {
        if (value.isEmpty()) {
            return SafetyBoundaryLimits.empty();
        }
        return SafetyBoundaryLimits.builder()
                .limit(limit, value.get())
                .build();
    }
}
