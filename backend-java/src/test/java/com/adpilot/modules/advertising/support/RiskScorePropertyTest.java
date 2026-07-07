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
 * Property-based test for {@link RiskScoreCalculator}.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 19: Risk score is bounded, deterministic, and monotonic.
 *
 * <p><b>Validates: Requirements 7.1, 7.9</b>
 *
 * <p>Properties validated:
 * <ol>
 *   <li>Score is always bounded in [0.0, 1.0] for any valid input.</li>
 *   <li>Same inputs always produce the same score (deterministic).</li>
 *   <li>Higher changeMagnitude → higher or equal risk score (monotonic in magnitude).</li>
 *   <li>Lower dataConfidence → higher or equal risk score (monotonic in confidence, inverted).</li>
 *   <li>Higher historicalVolatility → higher or equal risk score.</li>
 *   <li>Higher absoluteDollarImpact → higher or equal risk score.</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 19: Risk score is bounded, deterministic, and monotonic")
class RiskScorePropertyTest {

    private final RiskScoreCalculator calculator = new RiskScoreCalculator();

    // --- Generators --------------------------------------------------------

    /** Change magnitude: [0, 100] with up to 4 decimal places. */
    @Provide
    Arbitrary<BigDecimal> changeMagnitudes() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("100"))
                .ofScale(4);
    }

    /** Data confidence: [0.0, 1.0] with up to 4 decimal places. */
    @Provide
    Arbitrary<BigDecimal> dataConfidences() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, BigDecimal.ONE)
                .ofScale(4);
    }

    /** Historical volatility: [0.0, 1.0] with up to 4 decimal places. */
    @Provide
    Arbitrary<BigDecimal> volatilities() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, BigDecimal.ONE)
                .ofScale(4);
    }

    /** Absolute dollar impact: [0, 100000] with up to 2 decimal places. */
    @Provide
    Arbitrary<BigDecimal> dollarImpacts() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("100000"))
                .ofScale(2);
    }

    /** Generates a valid RiskScoreInput combining all four dimensions. */
    @Provide
    Arbitrary<RiskScoreInput> validInputs() {
        return Combinators.combine(
                changeMagnitudes(),
                dataConfidences(),
                volatilities(),
                dollarImpacts()
        ).as(RiskScoreInput::new);
    }

    // --- Property 1: Bounded output ----------------------------------------

    /**
     * For any valid input, the risk score is always in [0.0, 1.0].
     */
    @Property(tries = 200)
    @Label("Score is always bounded in [0.0, 1.0]")
    void scoreIsBounded(@ForAll("validInputs") RiskScoreInput input) {
        RiskScoreResult result = calculator.calculate(input);

        assertThat(result.score())
                .as("Risk score must be >= 0.0 for input %s", input)
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(result.score())
                .as("Risk score must be <= 1.0 for input %s", input)
                .isLessThanOrEqualTo(BigDecimal.ONE);
    }

    // --- Property 2: Deterministic -----------------------------------------

    /**
     * Same inputs always produce the same score (Requirement 7.9).
     */
    @Property(tries = 200)
    @Label("Same inputs always produce the same score (deterministic)")
    void sameInputsProduceSameScore(@ForAll("validInputs") RiskScoreInput input) {
        RiskScoreResult result1 = calculator.calculate(input);
        RiskScoreResult result2 = calculator.calculate(input);

        assertThat(result1.score())
                .as("Determinism violated for input %s", input)
                .isEqualByComparingTo(result2.score());
        assertThat(result1.formulaVersion())
                .isEqualTo(result2.formulaVersion());
    }

    // --- Property 3: Monotonic in changeMagnitude --------------------------

    /**
     * Higher changeMagnitude → higher or equal risk score, holding all else constant.
     */
    @Property(tries = 200)
    @Label("Higher changeMagnitude produces higher or equal risk score")
    void monotonicInMagnitude(
            @ForAll("changeMagnitudes") BigDecimal mag1,
            @ForAll("changeMagnitudes") BigDecimal mag2,
            @ForAll("dataConfidences") BigDecimal confidence,
            @ForAll("volatilities") BigDecimal volatility,
            @ForAll("dollarImpacts") BigDecimal dollar) {

        BigDecimal lower = mag1.min(mag2);
        BigDecimal higher = mag1.max(mag2);

        RiskScoreInput inputLow = new RiskScoreInput(lower, confidence, volatility, dollar);
        RiskScoreInput inputHigh = new RiskScoreInput(higher, confidence, volatility, dollar);

        RiskScoreResult resultLow = calculator.calculate(inputLow);
        RiskScoreResult resultHigh = calculator.calculate(inputHigh);

        assertThat(resultHigh.score())
                .as("Monotonicity in magnitude violated: mag %s → %s, mag %s → %s",
                        lower, resultLow.score(), higher, resultHigh.score())
                .isGreaterThanOrEqualTo(resultLow.score());
    }

    // --- Property 4: Monotonic in dataConfidence (inverted) ----------------

    /**
     * Lower dataConfidence → higher or equal risk score, holding all else constant.
     */
    @Property(tries = 200)
    @Label("Lower dataConfidence produces higher or equal risk score")
    void monotonicInConfidenceInverted(
            @ForAll("changeMagnitudes") BigDecimal magnitude,
            @ForAll("dataConfidences") BigDecimal conf1,
            @ForAll("dataConfidences") BigDecimal conf2,
            @ForAll("volatilities") BigDecimal volatility,
            @ForAll("dollarImpacts") BigDecimal dollar) {

        BigDecimal lowerConf = conf1.min(conf2);
        BigDecimal higherConf = conf1.max(conf2);

        RiskScoreInput inputLowConf = new RiskScoreInput(magnitude, lowerConf, volatility, dollar);
        RiskScoreInput inputHighConf = new RiskScoreInput(magnitude, higherConf, volatility, dollar);

        RiskScoreResult resultLowConf = calculator.calculate(inputLowConf);
        RiskScoreResult resultHighConf = calculator.calculate(inputHighConf);

        // Lower confidence → higher risk
        assertThat(resultLowConf.score())
                .as("Monotonicity in confidence (inverted) violated: conf %s → %s, conf %s → %s",
                        lowerConf, resultLowConf.score(), higherConf, resultHighConf.score())
                .isGreaterThanOrEqualTo(resultHighConf.score());
    }

    // --- Property 5: Monotonic in historicalVolatility ---------------------

    /**
     * Higher historicalVolatility → higher or equal risk score, holding all else constant.
     */
    @Property(tries = 200)
    @Label("Higher historicalVolatility produces higher or equal risk score")
    void monotonicInVolatility(
            @ForAll("changeMagnitudes") BigDecimal magnitude,
            @ForAll("dataConfidences") BigDecimal confidence,
            @ForAll("volatilities") BigDecimal vol1,
            @ForAll("volatilities") BigDecimal vol2,
            @ForAll("dollarImpacts") BigDecimal dollar) {

        BigDecimal lowerVol = vol1.min(vol2);
        BigDecimal higherVol = vol1.max(vol2);

        RiskScoreInput inputLowVol = new RiskScoreInput(magnitude, confidence, lowerVol, dollar);
        RiskScoreInput inputHighVol = new RiskScoreInput(magnitude, confidence, higherVol, dollar);

        RiskScoreResult resultLowVol = calculator.calculate(inputLowVol);
        RiskScoreResult resultHighVol = calculator.calculate(inputHighVol);

        assertThat(resultHighVol.score())
                .as("Monotonicity in volatility violated: vol %s → %s, vol %s → %s",
                        lowerVol, resultLowVol.score(), higherVol, resultHighVol.score())
                .isGreaterThanOrEqualTo(resultLowVol.score());
    }

    // --- Property 6: Monotonic in absoluteDollarImpact --------------------

    /**
     * Higher absoluteDollarImpact → higher or equal risk score, holding all else constant.
     */
    @Property(tries = 200)
    @Label("Higher absoluteDollarImpact produces higher or equal risk score")
    void monotonicInDollarImpact(
            @ForAll("changeMagnitudes") BigDecimal magnitude,
            @ForAll("dataConfidences") BigDecimal confidence,
            @ForAll("volatilities") BigDecimal volatility,
            @ForAll("dollarImpacts") BigDecimal dollar1,
            @ForAll("dollarImpacts") BigDecimal dollar2) {

        BigDecimal lowerDollar = dollar1.min(dollar2);
        BigDecimal higherDollar = dollar1.max(dollar2);

        RiskScoreInput inputLowDollar = new RiskScoreInput(magnitude, confidence, volatility, lowerDollar);
        RiskScoreInput inputHighDollar = new RiskScoreInput(magnitude, confidence, volatility, higherDollar);

        RiskScoreResult resultLowDollar = calculator.calculate(inputLowDollar);
        RiskScoreResult resultHighDollar = calculator.calculate(inputHighDollar);

        assertThat(resultHighDollar.score())
                .as("Monotonicity in dollar impact violated: $%s → %s, $%s → %s",
                        lowerDollar, resultLowDollar.score(), higherDollar, resultHighDollar.score())
                .isGreaterThanOrEqualTo(resultLowDollar.score());
    }
}
