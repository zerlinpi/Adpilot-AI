package com.adpilot.modules.advertising.support;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the only-tighten configuration rejection invariant:
 * {@link SafetyBoundaryValidator#validateOnlyTighten} rejects any proposed value
 * that is LESS restrictive than the higher-level boundary, and accepts values that
 * are equal to or more restrictive.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 17: Only-tighten configuration rejection.
 *
 * <p><b>Validates: Requirements 6.3, 21.4</b>
 *
 * <p>Properties:
 * <ol>
 *   <li>UPPER_BOUND: a proposed value LARGER than the higher-level boundary is rejected</li>
 *   <li>LOWER_BOUND: a proposed value SMALLER than the higher-level boundary is rejected</li>
 *   <li>A proposed value equal to or more restrictive than the higher-level boundary is accepted</li>
 *   <li>The rejection identifies which higher-level boundary constrains the value</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 17: Only-tighten configuration rejection")
class SafetyBoundaryValidatorOnlyTightenPropertyTest {

    private final SafetyBoundaryValidator validator = new SafetyBoundaryValidator();

    // --- Generators --------------------------------------------------------

    @Provide
    Arbitrary<BigDecimal> positiveValues() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.valueOf(1), BigDecimal.valueOf(10_000))
                .ofScale(2);
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

    /**
     * Generates a higher-level SafetyBoundaryLevel (store, organization, or system)
     * to serve as the source of the constraining boundary.
     */
    @Provide
    Arbitrary<SafetyBoundaryLevel> higherLevels() {
        return Arbitraries.of(
                SafetyBoundaryLevel.STORE_POLICY,
                SafetyBoundaryLevel.ORGANIZATION_POLICY,
                SafetyBoundaryLevel.SYSTEM_DEFAULT);
    }

    /**
     * Generates a target level that is below system (campaign or goal),
     * representing where the user is trying to configure.
     */
    @Provide
    Arbitrary<SafetyBoundaryLevel> targetLevels() {
        return Arbitraries.of(
                SafetyBoundaryLevel.CAMPAIGN_OVERRIDE,
                SafetyBoundaryLevel.GOAL_BOUNDARY);
    }

    // --- Properties --------------------------------------------------------

    /**
     * For UPPER_BOUND limits: a proposed value that is LARGER than the higher-level
     * boundary is rejected (larger = less restrictive for upper bounds).
     */
    @Property(tries = 150)
    @Label("UPPER_BOUND: proposed value larger than higher-level boundary is rejected")
    void upperBoundLargerValueRejected(
            @ForAll("upperBoundLimits") SafetyBoundaryLimit limit,
            @ForAll("positiveValues") BigDecimal higherLevelValue,
            @ForAll("positiveValues") BigDecimal positiveOffset,
            @ForAll("higherLevels") SafetyBoundaryLevel sourceLevel,
            @ForAll("targetLevels") SafetyBoundaryLevel targetLevel) {

        // Ensure the proposed value is strictly larger (less restrictive for UPPER_BOUND)
        BigDecimal proposedValue = higherLevelValue.add(positiveOffset);

        // Build the higher-level boundary with a known source
        SafetyBoundary higherBoundary = buildBoundaryWithSource(limit, higherLevelValue, sourceLevel);

        // Build proposed limits with the less-restrictive value
        SafetyBoundaryLimits proposed = SafetyBoundaryLimits.builder()
                .limit(limit, proposedValue)
                .build();

        // Validate: should be rejected
        BoundaryValidationResult result = validator.validateOnlyTighten(proposed, targetLevel, higherBoundary);

        assertThat(result.valid())
                .as("UPPER_BOUND %s: proposed %s > higher-level %s should be rejected",
                        limit, proposedValue, higherLevelValue)
                .isFalse();

        // Verify the violation identifies the constraining level
        assertThat(result.violations()).isNotEmpty();
        assertThat(result.violations().get(0).type())
                .isEqualTo(BoundaryValidationResult.ViolationType.ONLY_TIGHTEN);
        assertThat(result.violations().get(0).limit()).isEqualTo(limit);
        assertThat(result.violations().get(0).constrainingLevel()).isEqualTo(sourceLevel);
        assertThat(result.violations().get(0).constrainingValue().compareTo(higherLevelValue))
                .isEqualTo(0);
    }

    /**
     * For LOWER_BOUND limits: a proposed value that is SMALLER than the higher-level
     * boundary is rejected (smaller = less restrictive for lower bounds).
     */
    @Property(tries = 150)
    @Label("LOWER_BOUND: proposed value smaller than higher-level boundary is rejected")
    void lowerBoundSmallerValueRejected(
            @ForAll("lowerBoundLimits") SafetyBoundaryLimit limit,
            @ForAll("positiveValues") BigDecimal higherLevelValue,
            @ForAll("positiveValues") BigDecimal positiveOffset,
            @ForAll("higherLevels") SafetyBoundaryLevel sourceLevel,
            @ForAll("targetLevels") SafetyBoundaryLevel targetLevel) {

        // Ensure the higher-level value is large enough to subtract from
        BigDecimal adjustedHigherLevel = higherLevelValue.add(positiveOffset);
        // Proposed is strictly smaller (less restrictive for LOWER_BOUND)
        BigDecimal proposedValue = adjustedHigherLevel.subtract(positiveOffset);

        // Build the higher-level boundary with a known source
        SafetyBoundary higherBoundary = buildBoundaryWithSource(limit, adjustedHigherLevel, sourceLevel);

        // Build proposed limits with the less-restrictive value
        SafetyBoundaryLimits proposed = SafetyBoundaryLimits.builder()
                .limit(limit, proposedValue)
                .build();

        // Validate: should be rejected
        BoundaryValidationResult result = validator.validateOnlyTighten(proposed, targetLevel, higherBoundary);

        assertThat(result.valid())
                .as("LOWER_BOUND %s: proposed %s < higher-level %s should be rejected",
                        limit, proposedValue, adjustedHigherLevel)
                .isFalse();

        // Verify the violation identifies the constraining level
        assertThat(result.violations()).isNotEmpty();
        assertThat(result.violations().get(0).type())
                .isEqualTo(BoundaryValidationResult.ViolationType.ONLY_TIGHTEN);
        assertThat(result.violations().get(0).limit()).isEqualTo(limit);
        assertThat(result.violations().get(0).constrainingLevel()).isEqualTo(sourceLevel);
        assertThat(result.violations().get(0).constrainingValue().compareTo(adjustedHigherLevel))
                .isEqualTo(0);
    }

    /**
     * For UPPER_BOUND limits: a proposed value equal to the higher-level boundary is accepted
     * (equal = equally restrictive, not less restrictive).
     */
    @Property(tries = 150)
    @Label("UPPER_BOUND: proposed value equal to higher-level boundary is accepted")
    void upperBoundEqualValueAccepted(
            @ForAll("upperBoundLimits") SafetyBoundaryLimit limit,
            @ForAll("positiveValues") BigDecimal boundaryValue,
            @ForAll("higherLevels") SafetyBoundaryLevel sourceLevel,
            @ForAll("targetLevels") SafetyBoundaryLevel targetLevel) {

        SafetyBoundary higherBoundary = buildBoundaryWithSource(limit, boundaryValue, sourceLevel);

        // Propose the same value as the higher level
        SafetyBoundaryLimits proposed = SafetyBoundaryLimits.builder()
                .limit(limit, boundaryValue)
                .build();

        BoundaryValidationResult result = validator.validateOnlyTighten(proposed, targetLevel, higherBoundary);

        assertThat(result.valid())
                .as("UPPER_BOUND %s: proposed %s == higher-level %s should be accepted",
                        limit, boundaryValue, boundaryValue)
                .isTrue();
    }

    /**
     * For UPPER_BOUND limits: a proposed value SMALLER (more restrictive) than the
     * higher-level boundary is accepted.
     */
    @Property(tries = 150)
    @Label("UPPER_BOUND: proposed value smaller (more restrictive) than higher-level boundary is accepted")
    void upperBoundSmallerValueAccepted(
            @ForAll("upperBoundLimits") SafetyBoundaryLimit limit,
            @ForAll("positiveValues") BigDecimal higherLevelValue,
            @ForAll("positiveValues") BigDecimal positiveOffset,
            @ForAll("higherLevels") SafetyBoundaryLevel sourceLevel,
            @ForAll("targetLevels") SafetyBoundaryLevel targetLevel) {

        // Ensure higher-level is large enough for a smaller proposed value
        BigDecimal adjustedHigher = higherLevelValue.add(positiveOffset);
        BigDecimal proposedValue = adjustedHigher.subtract(positiveOffset);

        SafetyBoundary higherBoundary = buildBoundaryWithSource(limit, adjustedHigher, sourceLevel);

        SafetyBoundaryLimits proposed = SafetyBoundaryLimits.builder()
                .limit(limit, proposedValue)
                .build();

        BoundaryValidationResult result = validator.validateOnlyTighten(proposed, targetLevel, higherBoundary);

        assertThat(result.valid())
                .as("UPPER_BOUND %s: proposed %s < higher-level %s should be accepted (more restrictive)",
                        limit, proposedValue, adjustedHigher)
                .isTrue();
    }

    /**
     * For LOWER_BOUND limits: a proposed value equal to the higher-level boundary is accepted.
     */
    @Property(tries = 150)
    @Label("LOWER_BOUND: proposed value equal to higher-level boundary is accepted")
    void lowerBoundEqualValueAccepted(
            @ForAll("lowerBoundLimits") SafetyBoundaryLimit limit,
            @ForAll("positiveValues") BigDecimal boundaryValue,
            @ForAll("higherLevels") SafetyBoundaryLevel sourceLevel,
            @ForAll("targetLevels") SafetyBoundaryLevel targetLevel) {

        SafetyBoundary higherBoundary = buildBoundaryWithSource(limit, boundaryValue, sourceLevel);

        SafetyBoundaryLimits proposed = SafetyBoundaryLimits.builder()
                .limit(limit, boundaryValue)
                .build();

        BoundaryValidationResult result = validator.validateOnlyTighten(proposed, targetLevel, higherBoundary);

        assertThat(result.valid())
                .as("LOWER_BOUND %s: proposed %s == higher-level %s should be accepted",
                        limit, boundaryValue, boundaryValue)
                .isTrue();
    }

    /**
     * For LOWER_BOUND limits: a proposed value LARGER (more restrictive) than the
     * higher-level boundary is accepted.
     */
    @Property(tries = 150)
    @Label("LOWER_BOUND: proposed value larger (more restrictive) than higher-level boundary is accepted")
    void lowerBoundLargerValueAccepted(
            @ForAll("lowerBoundLimits") SafetyBoundaryLimit limit,
            @ForAll("positiveValues") BigDecimal higherLevelValue,
            @ForAll("positiveValues") BigDecimal positiveOffset,
            @ForAll("higherLevels") SafetyBoundaryLevel sourceLevel,
            @ForAll("targetLevels") SafetyBoundaryLevel targetLevel) {

        // Proposed is strictly larger (more restrictive for LOWER_BOUND)
        BigDecimal proposedValue = higherLevelValue.add(positiveOffset);

        SafetyBoundary higherBoundary = buildBoundaryWithSource(limit, higherLevelValue, sourceLevel);

        SafetyBoundaryLimits proposed = SafetyBoundaryLimits.builder()
                .limit(limit, proposedValue)
                .build();

        BoundaryValidationResult result = validator.validateOnlyTighten(proposed, targetLevel, higherBoundary);

        assertThat(result.valid())
                .as("LOWER_BOUND %s: proposed %s > higher-level %s should be accepted (more restrictive)",
                        limit, proposedValue, higherLevelValue)
                .isTrue();
    }

    /**
     * When the higher-level boundary does not define a limit, any proposed value
     * for that limit is accepted (no constraint from above).
     */
    @Property(tries = 100)
    @Label("Undefined higher-level limit allows any proposed value")
    void undefinedHigherLevelAllowsAnyValue(
            @ForAll("upperBoundLimits") SafetyBoundaryLimit limit,
            @ForAll("positiveValues") BigDecimal proposedValue,
            @ForAll("targetLevels") SafetyBoundaryLevel targetLevel) {

        // Build a boundary that defines nothing for this limit
        SafetyBoundary emptyBoundary = SafetyBoundaryResolver.resolve(
                SafetyBoundaryLimits.empty(),
                SafetyBoundaryLimits.empty(),
                SafetyBoundaryLimits.empty(),
                SafetyBoundaryLimits.empty(),
                SafetyBoundaryLimits.empty());

        SafetyBoundaryLimits proposed = SafetyBoundaryLimits.builder()
                .limit(limit, proposedValue)
                .build();

        BoundaryValidationResult result = validator.validateOnlyTighten(proposed, targetLevel, emptyBoundary);

        assertThat(result.valid())
                .as("No higher-level constraint for %s: any value %s should be accepted",
                        limit, proposedValue)
                .isTrue();
    }

    // --- Helpers ------------------------------------------------------------

    /**
     * Build a SafetyBoundary that defines the given limit with the given value
     * sourced from the specified level. We use the resolver to properly construct
     * the boundary with correct source attribution.
     */
    private SafetyBoundary buildBoundaryWithSource(SafetyBoundaryLimit limit,
                                                    BigDecimal value,
                                                    SafetyBoundaryLevel sourceLevel) {
        EnumMap<SafetyBoundaryLevel, SafetyBoundaryLimits> contributions =
                new EnumMap<>(SafetyBoundaryLevel.class);

        // Place the value at the specified source level, leave all others empty
        for (SafetyBoundaryLevel level : SafetyBoundaryLevel.values()) {
            if (level == sourceLevel) {
                contributions.put(level, SafetyBoundaryLimits.builder()
                        .limit(limit, value)
                        .build());
            } else {
                contributions.put(level, SafetyBoundaryLimits.empty());
            }
        }

        return SafetyBoundaryResolver.resolve(contributions);
    }
}
