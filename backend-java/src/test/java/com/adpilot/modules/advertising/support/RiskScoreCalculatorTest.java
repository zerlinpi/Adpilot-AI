package com.adpilot.modules.advertising.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RiskScoreCalculator}.
 *
 * <p>Validates: Requirements 7.1, 7.9
 */
class RiskScoreCalculatorTest {

    private final RiskScoreCalculator calculator = new RiskScoreCalculator();

    // --- Bounded output ---

    @Test
    void scoreIsAlwaysBetweenZeroAndOne_minimalInputs() {
        var input = new RiskScoreInput(
                BigDecimal.ZERO,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
        RiskScoreResult result = calculator.calculate(input);
        assertThat(result.score()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(result.score()).isLessThanOrEqualTo(BigDecimal.ONE);
    }

    @Test
    void scoreIsAlwaysBetweenZeroAndOne_maximalInputs() {
        var input = new RiskScoreInput(
                new BigDecimal("100"),    // very large magnitude
                BigDecimal.ZERO,          // no confidence
                BigDecimal.ONE,           // max volatility
                new BigDecimal("1000000") // huge dollar impact
        );
        RiskScoreResult result = calculator.calculate(input);
        assertThat(result.score()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(result.score()).isLessThanOrEqualTo(BigDecimal.ONE);
    }

    @Test
    void minimalRiskInputsProduceLowScore() {
        // Zero change, perfect confidence, no volatility, no dollar impact → lowest risk
        var input = new RiskScoreInput(
                BigDecimal.ZERO,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
        RiskScoreResult result = calculator.calculate(input);
        assertThat(result.score().doubleValue()).isEqualTo(0.0);
    }

    @Test
    void maximalRiskInputsProduceHighScore() {
        // Large change, no confidence, max volatility, huge dollar impact → high risk
        var input = new RiskScoreInput(
                new BigDecimal("1000"),
                BigDecimal.ZERO,
                BigDecimal.ONE,
                new BigDecimal("1000000")
        );
        RiskScoreResult result = calculator.calculate(input);
        assertThat(result.score().doubleValue()).isGreaterThan(0.8);
    }

    // --- Determinism ---

    @Test
    void sameInputsProduceSameScore() {
        var input = new RiskScoreInput(
                new BigDecimal("0.5"),
                new BigDecimal("0.7"),
                new BigDecimal("0.3"),
                new BigDecimal("200")
        );
        RiskScoreResult result1 = calculator.calculate(input);
        RiskScoreResult result2 = calculator.calculate(input);
        assertThat(result1.score()).isEqualByComparingTo(result2.score());
    }

    // --- Monotonicity ---

    @Test
    void higherMagnitudeIncreasesRisk() {
        var low = new RiskScoreInput(new BigDecimal("0.1"), new BigDecimal("0.5"),
                new BigDecimal("0.3"), new BigDecimal("100"));
        var high = new RiskScoreInput(new BigDecimal("0.9"), new BigDecimal("0.5"),
                new BigDecimal("0.3"), new BigDecimal("100"));

        assertThat(calculator.calculate(high).score())
                .isGreaterThan(calculator.calculate(low).score());
    }

    @Test
    void lowerConfidenceIncreasesRisk() {
        var highConf = new RiskScoreInput(new BigDecimal("0.3"), new BigDecimal("0.9"),
                new BigDecimal("0.3"), new BigDecimal("100"));
        var lowConf = new RiskScoreInput(new BigDecimal("0.3"), new BigDecimal("0.2"),
                new BigDecimal("0.3"), new BigDecimal("100"));

        assertThat(calculator.calculate(lowConf).score())
                .isGreaterThan(calculator.calculate(highConf).score());
    }

    @Test
    void higherVolatilityIncreasesRisk() {
        var lowVol = new RiskScoreInput(new BigDecimal("0.3"), new BigDecimal("0.5"),
                new BigDecimal("0.1"), new BigDecimal("100"));
        var highVol = new RiskScoreInput(new BigDecimal("0.3"), new BigDecimal("0.5"),
                new BigDecimal("0.8"), new BigDecimal("100"));

        assertThat(calculator.calculate(highVol).score())
                .isGreaterThan(calculator.calculate(lowVol).score());
    }

    @Test
    void higherDollarImpactIncreasesRisk() {
        var lowDollar = new RiskScoreInput(new BigDecimal("0.3"), new BigDecimal("0.5"),
                new BigDecimal("0.3"), new BigDecimal("10"));
        var highDollar = new RiskScoreInput(new BigDecimal("0.3"), new BigDecimal("0.5"),
                new BigDecimal("0.3"), new BigDecimal("5000"));

        assertThat(calculator.calculate(highDollar).score())
                .isGreaterThan(calculator.calculate(lowDollar).score());
    }

    // --- Formula version ---

    @Test
    void formulaVersionIsRecorded() {
        var input = new RiskScoreInput(
                new BigDecimal("0.5"),
                new BigDecimal("0.5"),
                new BigDecimal("0.5"),
                new BigDecimal("500")
        );
        RiskScoreResult result = calculator.calculate(input);
        assertThat(result.formulaVersion()).isEqualTo("v1");
    }

    // --- Null rejection ---

    @Test
    void nullInputThrows() {
        assertThatThrownBy(() -> calculator.calculate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Input validation ---

    @Test
    void negativeMagnitudeThrows() {
        assertThatThrownBy(() -> new RiskScoreInput(
                new BigDecimal("-1"), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confidenceAboveOneThrows() {
        assertThatThrownBy(() -> new RiskScoreInput(
                BigDecimal.ZERO, new BigDecimal("1.1"), BigDecimal.ZERO, BigDecimal.ZERO
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void volatilityBelowZeroThrows() {
        assertThatThrownBy(() -> new RiskScoreInput(
                BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("-0.01"), BigDecimal.ZERO
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeDollarImpactThrows() {
        assertThatThrownBy(() -> new RiskScoreInput(
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("-5")
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
