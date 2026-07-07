package com.adpilot.modules.advertising.support;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Pure, deterministic, versioned risk-score calculator (Requirement 7.1, 7.9).
 *
 * <p>Computes a 0.0–1.0 risk score from four input dimensions:
 * <ol>
 *   <li><b>Change magnitude</b> — larger changes are riskier.</li>
 *   <li><b>Data confidence</b> — lower confidence increases risk (inverted).</li>
 *   <li><b>Historical volatility</b> — more volatile metrics are riskier.</li>
 *   <li><b>Absolute dollar impact</b> — higher dollar exposure increases risk.</li>
 * </ol>
 *
 * <h3>Monotonicity guarantees</h3>
 * <ul>
 *   <li>Higher {@code changeMagnitude} → higher or equal risk score.</li>
 *   <li>Lower {@code dataConfidence} → higher or equal risk score.</li>
 *   <li>Higher {@code historicalVolatility} → higher or equal risk score.</li>
 *   <li>Higher {@code absoluteDollarImpact} → higher or equal risk score.</li>
 * </ul>
 *
 * <h3>Formula v1</h3>
 * The formula uses a weighted combination of normalized sub-scores:
 * <pre>
 *   magnitudeScore   = clamp01(changeMagnitude / (1 + changeMagnitude))
 *   confidenceScore  = 1.0 - dataConfidence
 *   volatilityScore  = historicalVolatility
 *   dollarScore      = clamp01(absoluteDollarImpact / (dollarScaleConstant + absoluteDollarImpact))
 *
 *   raw = w_magnitude * magnitudeScore
 *       + w_confidence * confidenceScore
 *       + w_volatility * volatilityScore
 *       + w_dollar * dollarScore
 *
 *   score = clamp(raw, 0.0, 1.0)
 * </pre>
 *
 * <p>The sub-score functions are chosen to be monotonic and naturally bounded:
 * <ul>
 *   <li>{@code x / (1 + x)} maps [0, ∞) → [0, 1) monotonically.</li>
 *   <li>{@code 1 − confidence} inverts [0, 1] → [1, 0] monotonically.</li>
 *   <li>Volatility is already [0, 1].</li>
 *   <li>Dollar impact uses the same asymptotic function with a configurable
 *       scale constant (default $1000) that sets the mid-point.</li>
 * </ul>
 *
 * <p>Weights sum to 1.0, ensuring the raw combination is naturally ≤ 1.0 and
 * the final clamp is a safety net for floating-point edge cases.
 *
 * <p>This class is a Spring {@code @Component} for dependency injection while
 * remaining a pure function with no side effects and no state.
 */
@Component
public class RiskScoreCalculator {

    /** Current formula version identifier recorded in Decision_Snapshot. */
    public static final String FORMULA_VERSION = "v1";

    // --- Weights (sum to 1.0) ---
    private static final BigDecimal W_MAGNITUDE = new BigDecimal("0.35");
    private static final BigDecimal W_CONFIDENCE = new BigDecimal("0.25");
    private static final BigDecimal W_VOLATILITY = new BigDecimal("0.20");
    private static final BigDecimal W_DOLLAR = new BigDecimal("0.20");

    /**
     * Dollar scale constant: the absolute dollar impact at which the dollar
     * sub-score reaches 0.5. Default $1000.
     */
    private static final BigDecimal DOLLAR_SCALE = new BigDecimal("1000");

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final int RESULT_SCALE = 6;

    /**
     * Compute the risk score for the given inputs.
     *
     * <p>This is a pure function: same inputs always produce the same result,
     * no randomness, no side effects (Requirement 7.9).
     *
     * @param input the risk score input dimensions
     * @return a {@link RiskScoreResult} with score ∈ [0.0, 1.0] and formula version
     * @throws IllegalArgumentException if input is null
     */
    public RiskScoreResult calculate(RiskScoreInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }

        // Sub-score 1: change magnitude → [0, 1) via x/(1+x)
        BigDecimal magnitudeScore = asymptoticNormalize(input.changeMagnitude(), BigDecimal.ONE);

        // Sub-score 2: data confidence inverted → higher confidence = lower risk
        BigDecimal confidenceScore = BigDecimal.ONE.subtract(input.dataConfidence(), MC);

        // Sub-score 3: historical volatility (already [0,1])
        BigDecimal volatilityScore = input.historicalVolatility();

        // Sub-score 4: absolute dollar impact → [0, 1) via x/(scale+x)
        BigDecimal dollarScore = asymptoticNormalize(input.absoluteDollarImpact(), DOLLAR_SCALE);

        // Weighted combination
        BigDecimal raw = W_MAGNITUDE.multiply(magnitudeScore, MC)
                .add(W_CONFIDENCE.multiply(confidenceScore, MC), MC)
                .add(W_VOLATILITY.multiply(volatilityScore, MC), MC)
                .add(W_DOLLAR.multiply(dollarScore, MC), MC);

        // Clamp to [0.0, 1.0] as a safety net
        BigDecimal clamped = clamp01(raw);

        // Round to a fixed scale for consistent results
        BigDecimal score = clamped.setScale(RESULT_SCALE, RoundingMode.HALF_UP);

        return new RiskScoreResult(score, FORMULA_VERSION);
    }

    /**
     * Asymptotic normalization: maps {@code value} from [0, ∞) to [0, 1)
     * via {@code value / (scale + value)}.
     *
     * <p>Monotonically increasing: larger value → larger result.
     */
    private BigDecimal asymptoticNormalize(BigDecimal value, BigDecimal scale) {
        // value / (scale + value)
        BigDecimal denominator = scale.add(value, MC);
        if (denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return value.divide(denominator, MC);
    }

    /**
     * Clamps a value to the [0.0, 1.0] range.
     */
    private BigDecimal clamp01(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return value;
    }
}
