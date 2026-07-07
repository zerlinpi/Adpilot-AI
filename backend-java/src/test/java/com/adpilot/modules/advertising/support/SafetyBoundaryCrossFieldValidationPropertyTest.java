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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link SafetyBoundaryValidator#validateCrossFieldConstraints}
 * method, verifying that cross-field ordering constraints are correctly enforced.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 18: Boundary cross-field validation.
 *
 * <p><b>Validates: Requirements 6.8, 12.6</b>
 *
 * <p>Cross-field constraints tested:
 * <ol>
 *   <li>minBid &gt; maxBid is rejected</li>
 *   <li>minBid ≤ maxBid is accepted</li>
 *   <li>minDailyBudget &gt; maxDailyBudget is rejected</li>
 *   <li>minDailyBudget ≤ maxDailyBudget is accepted</li>
 *   <li>inventoryCriticalDays &gt; inventorySafetyDays is rejected</li>
 *   <li>inventorySafetyDays &gt; inventoryHealthyDays is rejected</li>
 *   <li>When all cross-field constraints are satisfied, validation passes</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 18: Boundary cross-field validation")
class SafetyBoundaryCrossFieldValidationPropertyTest {

    private final SafetyBoundaryValidator validator = new SafetyBoundaryValidator();

    // --- Generators --------------------------------------------------------

    @Provide
    Arbitrary<BigDecimal> positiveAmounts() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.valueOf(0.01), BigDecimal.valueOf(10_000))
                .ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> positiveDays() {
        return Arbitraries.integers()
                .between(1, 365)
                .map(BigDecimal::valueOf);
    }

    // --- Properties: minBid vs maxBid --------------------------------------

    /**
     * When minBid > maxBid, cross-field validation rejects.
     */
    @Property(tries = 150)
    @Label("minBid > maxBid is rejected")
    void minBidGreaterThanMaxBidIsRejected(
            @ForAll("positiveAmounts") BigDecimal base) {

        BigDecimal minBid = base.add(BigDecimal.valueOf(0.01));
        BigDecimal maxBid = base;

        SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                .minBid(minBid)
                .maxBid(maxBid)
                .build();

        BoundaryValidationResult result = validator.validateCrossFieldConstraints(limits);

        assertThat(result.valid())
                .as("minBid=%s > maxBid=%s should be rejected", minBid, maxBid)
                .isFalse();
        assertThat(result.violations()).isNotEmpty();
        assertThat(result.violations().get(0).type())
                .isEqualTo(BoundaryValidationResult.ViolationType.CROSS_FIELD);
        assertThat(result.violations().get(0).limit())
                .isEqualTo(SafetyBoundaryLimit.MIN_BID);
    }

    /**
     * When minBid ≤ maxBid, cross-field validation accepts (for that pair).
     */
    @Property(tries = 150)
    @Label("minBid ≤ maxBid is accepted")
    void minBidLessOrEqualMaxBidIsAccepted(
            @ForAll("positiveAmounts") BigDecimal minBid,
            @ForAll("positiveAmounts") BigDecimal delta) {

        BigDecimal maxBid = minBid.add(delta);

        SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                .minBid(minBid)
                .maxBid(maxBid)
                .build();

        BoundaryValidationResult result = validator.validateCrossFieldConstraints(limits);

        assertThat(result.valid())
                .as("minBid=%s ≤ maxBid=%s should be accepted", minBid, maxBid)
                .isTrue();
    }

    // --- Properties: minDailyBudget vs maxDailyBudget ----------------------

    /**
     * When minDailyBudget > maxDailyBudget, cross-field validation rejects.
     */
    @Property(tries = 150)
    @Label("minDailyBudget > maxDailyBudget is rejected")
    void minDailyBudgetGreaterThanMaxDailyBudgetIsRejected(
            @ForAll("positiveAmounts") BigDecimal base) {

        BigDecimal minDailyBudget = base.add(BigDecimal.valueOf(0.01));
        BigDecimal maxDailyBudget = base;

        SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                .minDailyBudget(minDailyBudget)
                .maxDailyBudget(maxDailyBudget)
                .build();

        BoundaryValidationResult result = validator.validateCrossFieldConstraints(limits);

        assertThat(result.valid())
                .as("minDailyBudget=%s > maxDailyBudget=%s should be rejected",
                        minDailyBudget, maxDailyBudget)
                .isFalse();
        assertThat(result.violations()).isNotEmpty();
        assertThat(result.violations().get(0).type())
                .isEqualTo(BoundaryValidationResult.ViolationType.CROSS_FIELD);
        assertThat(result.violations().get(0).limit())
                .isEqualTo(SafetyBoundaryLimit.MIN_DAILY_BUDGET);
    }

    /**
     * When minDailyBudget ≤ maxDailyBudget, cross-field validation accepts (for that pair).
     */
    @Property(tries = 150)
    @Label("minDailyBudget ≤ maxDailyBudget is accepted")
    void minDailyBudgetLessOrEqualMaxDailyBudgetIsAccepted(
            @ForAll("positiveAmounts") BigDecimal minBudget,
            @ForAll("positiveAmounts") BigDecimal delta) {

        BigDecimal maxBudget = minBudget.add(delta);

        SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                .minDailyBudget(minBudget)
                .maxDailyBudget(maxBudget)
                .build();

        BoundaryValidationResult result = validator.validateCrossFieldConstraints(limits);

        assertThat(result.valid())
                .as("minDailyBudget=%s ≤ maxDailyBudget=%s should be accepted",
                        minBudget, maxBudget)
                .isTrue();
    }

    // --- Properties: inventory day ordering --------------------------------

    /**
     * When inventoryCriticalDays > inventorySafetyDays, cross-field validation rejects.
     */
    @Property(tries = 150)
    @Label("inventoryCriticalDays > inventorySafetyDays is rejected")
    void inventoryCriticalGreaterThanSafetyIsRejected(
            @ForAll("positiveDays") BigDecimal base) {

        BigDecimal criticalDays = base.add(BigDecimal.ONE);
        BigDecimal safetyDays = base;

        SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                .inventoryCriticalDays(criticalDays)
                .inventorySafetyDays(safetyDays)
                .build();

        BoundaryValidationResult result = validator.validateCrossFieldConstraints(limits);

        assertThat(result.valid())
                .as("inventoryCriticalDays=%s > inventorySafetyDays=%s should be rejected",
                        criticalDays, safetyDays)
                .isFalse();
        assertThat(result.violations()).isNotEmpty();
        assertThat(result.violations().get(0).type())
                .isEqualTo(BoundaryValidationResult.ViolationType.CROSS_FIELD);
    }

    /**
     * When inventorySafetyDays > inventoryHealthyDays, cross-field validation rejects.
     */
    @Property(tries = 150)
    @Label("inventorySafetyDays > inventoryHealthyDays is rejected")
    void inventorySafetyGreaterThanHealthyIsRejected(
            @ForAll("positiveDays") BigDecimal base) {

        BigDecimal safetyDays = base.add(BigDecimal.ONE);
        BigDecimal healthyDays = base;

        SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                .inventorySafetyDays(safetyDays)
                .inventoryHealthyDays(healthyDays)
                .build();

        BoundaryValidationResult result = validator.validateCrossFieldConstraints(limits);

        assertThat(result.valid())
                .as("inventorySafetyDays=%s > inventoryHealthyDays=%s should be rejected",
                        safetyDays, healthyDays)
                .isFalse();
        assertThat(result.violations()).isNotEmpty();
        assertThat(result.violations().get(0).type())
                .isEqualTo(BoundaryValidationResult.ViolationType.CROSS_FIELD);
    }

    // --- Property: all constraints satisfied → passes ----------------------

    /**
     * When all cross-field constraints are satisfied (minBid ≤ maxBid,
     * minDailyBudget ≤ maxDailyBudget, inventoryCritical ≤ inventorySafety ≤ inventoryHealthy),
     * the validation passes.
     */
    @Property(tries = 150)
    @Label("All cross-field constraints satisfied → validation passes")
    void allCrossFieldConstraintsSatisfiedPasses(
            @ForAll("positiveAmounts") BigDecimal minBid,
            @ForAll("positiveAmounts") BigDecimal bidSpread,
            @ForAll("positiveAmounts") BigDecimal minBudget,
            @ForAll("positiveAmounts") BigDecimal budgetSpread,
            @ForAll("positiveDays") BigDecimal criticalDays,
            @ForAll("positiveDays") BigDecimal safetySpread,
            @ForAll("positiveDays") BigDecimal healthySpread) {

        BigDecimal maxBid = minBid.add(bidSpread);
        BigDecimal maxBudget = minBudget.add(budgetSpread);
        BigDecimal safetyDays = criticalDays.add(safetySpread);
        BigDecimal healthyDays = safetyDays.add(healthySpread);

        SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                .minBid(minBid)
                .maxBid(maxBid)
                .minDailyBudget(minBudget)
                .maxDailyBudget(maxBudget)
                .inventoryCriticalDays(criticalDays)
                .inventorySafetyDays(safetyDays)
                .inventoryHealthyDays(healthyDays)
                .build();

        BoundaryValidationResult result = validator.validateCrossFieldConstraints(limits);

        assertThat(result.valid())
                .as("All constraints satisfied: minBid=%s ≤ maxBid=%s, minBudget=%s ≤ maxBudget=%s, "
                                + "critical=%s ≤ safety=%s ≤ healthy=%s should pass",
                        minBid, maxBid, minBudget, maxBudget, criticalDays, safetyDays, healthyDays)
                .isTrue();
        assertThat(result.violations()).isEmpty();
    }
}
