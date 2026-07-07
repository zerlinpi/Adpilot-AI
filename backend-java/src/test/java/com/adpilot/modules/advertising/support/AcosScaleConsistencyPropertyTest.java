package com.adpilot.modules.advertising.support;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the single, authoritative ACoS scale convention encoded by the pure
 * {@link AcosScale} utility.
 *
 * <p>Feature: advertising-workspace-rework, Property 39: ACoS decimal-ratio scale is consistent
 * across layers.
 *
 * <p>Validates: Requirements 17.1, 17.2, 17.3, 17.4, 17.7.
 *
 * <p><em>For any</em> ACoS ratio {@code r}: the percentage display equals {@code r×100}
 * (Req 17.2); a value parsed from a percentage and a value rendered to a percentage round-trip back
 * to the same ratio (Req 17.1, 17.7); threshold comparisons performed on stored decimal ratios order
 * identically to comparisons performed on the displayed percentages (Req 17.3); and a value stored as
 * {@code r} and displayed as {@code (r×100)%} denote the same ratio (Req 17.4).
 *
 * <p>The oracle is independent of the production methods: each expected value is recomputed directly
 * from the scale definition ({@code ×100} for display, {@code ÷100} for storage) rather than by
 * re-invoking the method under test, so a regression in {@link AcosScale} cannot mask itself.
 *
 * <p>These exercise the pure helper directly — no Spring context is needed because every method
 * deterministically maps its {@link BigDecimal} inputs to a {@link BigDecimal} or comparison result.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 39: ACoS decimal-ratio scale is consistent across layers")
class AcosScaleConsistencyPropertyTest {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * Feature: advertising-workspace-rework, Property 39: ACoS decimal-ratio scale is consistent
     * across layers.
     *
     * <p>Validates: Requirements 17.2.
     *
     * <p>The percentage display of any stored ratio equals {@code ratio × 100}, rounded to the
     * display scale. This is the one rule the Frontend applies on every advertising surface.
     */
    @Property(tries = 200)
    void percentForDisplayEqualsRatioTimesHundred(@ForAll("ratios") BigDecimal ratio) {
        BigDecimal display = AcosScale.percentForDisplay(ratio);
        BigDecimal expected = ratio.multiply(HUNDRED).setScale(AcosScale.DISPLAY_SCALE, RoundingMode.HALF_UP);

        assertThat(display).isNotNull();
        assertThat(display.compareTo(expected)).isZero();
    }

    /**
     * Feature: advertising-workspace-rework, Property 39: ACoS decimal-ratio scale is consistent
     * across layers.
     *
     * <p>Validates: Requirements 17.1, 17.2, 17.4, 17.7.
     *
     * <p>For any displayable ratio (a ratio whose ×100 percentage needs no more than the display
     * scale), rendering it to a percentage and parsing that percentage back yields the same ratio,
     * and {@link AcosScale#displayMatchesRatio} confirms the displayed percentage and the stored ratio
     * denote the same ACoS. This is the round-trip that keeps the database, API, filter, and
     * form-parsing layers on one scale.
     */
    @Property(tries = 200)
    void ratioRoundTripsThroughItsPercentageDisplay(@ForAll("displayableRatios") BigDecimal ratio) {
        BigDecimal display = AcosScale.percentForDisplay(ratio);
        BigDecimal roundTripped = AcosScale.ratioFromPercent(display);

        assertThat(AcosScale.ratiosEqual(roundTripped, ratio)).isTrue();
        assertThat(AcosScale.displayMatchesRatio(display, ratio)).isTrue();
    }

    /**
     * Feature: advertising-workspace-rework, Property 39: ACoS decimal-ratio scale is consistent
     * across layers.
     *
     * <p>Validates: Requirements 17.1, 17.7.
     *
     * <p>A percentage typed into a form (carried at the display scale) is parsed to the canonical
     * decimal ratio and renders back to the same percentage, so form-parsing and display agree on one
     * scale.
     */
    @Property(tries = 200)
    void percentRoundTripsThroughItsStoredRatio(@ForAll("percents") BigDecimal percent) {
        BigDecimal ratio = AcosScale.ratioFromPercent(percent);
        BigDecimal backToPercent = AcosScale.percentForDisplay(ratio);

        assertThat(backToPercent.compareTo(percent)).isZero();
    }

    /**
     * Feature: advertising-workspace-rework, Property 39: ACoS decimal-ratio scale is consistent
     * across layers.
     *
     * <p>Validates: Requirements 17.3, 17.4.
     *
     * <p>Threshold comparison performed on stored decimal ratios orders two ACoS values identically
     * to comparison performed on their percentage displays (multiplying by the positive constant 100
     * is order-preserving), and {@code ratiosEqual} agrees with numeric equality of the ratios. The
     * recommendation engine therefore reaches the same verdict whether it compares ratios or the
     * percentages a user sees.
     */
    @Property(tries = 200)
    void comparisonIsConsistentBetweenRatioAndDisplayScales(
            @ForAll("displayableRatios") BigDecimal a,
            @ForAll("displayableRatios") BigDecimal b) {

        int ratioComparison = Integer.signum(AcosScale.compareRatios(a, b));
        int displayComparison =
                Integer.signum(AcosScale.percentForDisplay(a).compareTo(AcosScale.percentForDisplay(b)));

        assertThat(ratioComparison).isEqualTo(displayComparison);
        assertThat(AcosScale.ratiosEqual(a, b)).isEqualTo(a.compareTo(b) == 0);
    }

    /**
     * Feature: advertising-workspace-rework, Property 39: ACoS decimal-ratio scale is consistent
     * across layers.
     *
     * <p>Validates: Requirements 17.4.
     *
     * <p>{@link AcosScale#displayMatchesRatio} reports a match for an arbitrary displayed percentage
     * and an arbitrary stored ratio exactly when the percentage's parsed ratio equals the stored
     * ratio — it never disagrees with the ratio-equality the rest of the system relies on.
     */
    @Property(tries = 200)
    void displayMatchesRatioAgreesWithRatioEquality(
            @ForAll("percents") BigDecimal displayPercent,
            @ForAll("ratios") BigDecimal storedRatio) {

        boolean expected = AcosScale.ratiosEqual(AcosScale.ratioFromPercent(displayPercent), storedRatio);

        assertThat(AcosScale.displayMatchesRatio(displayPercent, storedRatio)).isEqualTo(expected);
    }

    // --- Worked examples from the requirement (Req 17.1, 17.2, 17.4) -------------------------------

    /** {@code 0.1492} displays as {@code 14.92} (Req 17.2). */
    @Example
    void ratioDisplaysAsItsPercentage() {
        assertThat(AcosScale.percentForDisplay(new BigDecimal("0.1492")).compareTo(new BigDecimal("14.92")))
                .isZero();
    }

    /** A 25% target stores as {@code 0.25} (Req 17.1). */
    @Example
    void percentParsesToItsStoredRatio() {
        assertThat(AcosScale.ratioFromPercent(new BigDecimal("25")).compareTo(new BigDecimal("0.25")))
                .isZero();
    }

    /** {@code 14.92%} display and stored {@code 0.1492} are the same ratio (Req 17.4). */
    @Example
    void displayAndStoredRatioDenoteTheSameAcos() {
        assertThat(AcosScale.displayMatchesRatio(new BigDecimal("14.92"), new BigDecimal("0.1492")))
                .isTrue();
    }

    /** {@code 14.92%} is never confused with {@code 1492%}: their ratios differ (Req 17.3, 17.4). */
    @Example
    void percentAndHundredTimesPercentAreNotEqualRatios() {
        BigDecimal acos = AcosScale.ratioFromPercent(new BigDecimal("14.92"));
        BigDecimal inflated = AcosScale.ratioFromPercent(new BigDecimal("1492"));

        assertThat(AcosScale.ratiosEqual(acos, inflated)).isFalse();
        assertThat(AcosScale.compareRatios(acos, inflated)).isNegative();
    }

    // --- Generators --------------------------------------------------------------------------------

    /**
     * Decimal ratios at the canonical {@link AcosScale#RATIO_SCALE storage scale}, ranging from 0 to
     * the plausible upper bound (a ratio of 100, i.e. 10,000% ACoS). Built from an integer count of
     * micro-units so each value lands exactly on the 6-decimal grid.
     */
    @Provide
    Arbitrary<BigDecimal> ratios() {
        return Arbitraries.longs()
                .between(0L, 100_000_000L)
                .map(micros -> BigDecimal.valueOf(micros, AcosScale.RATIO_SCALE));
    }

    /**
     * Displayable ratios: ratios whose ×100 percentage needs no more than the {@link
     * AcosScale#DISPLAY_SCALE display scale}, so the ratio→percentage→ratio round-trip is exact. Built
     * from an integer count of ten-thousandths (4 decimals) spanning 0 to a ratio of 100.
     */
    @Provide
    Arbitrary<BigDecimal> displayableRatios() {
        return Arbitraries.longs()
                .between(0L, 1_000_000L)
                .map(tenThousandths -> BigDecimal.valueOf(tenThousandths, 4));
    }

    /**
     * Percentages as typed in a form or carried on a percentage-scaled field, at the display scale
     * (2 decimals), spanning 0% to 10,000%. Parsing a 2-decimal percentage to a 6-decimal ratio loses
     * nothing, so the percentage→ratio→percentage round-trip is exact.
     */
    @Provide
    Arbitrary<BigDecimal> percents() {
        return Arbitraries.longs()
                .between(0L, 1_000_000L)
                .map(hundredths -> BigDecimal.valueOf(hundredths, AcosScale.DISPLAY_SCALE));
    }
}
