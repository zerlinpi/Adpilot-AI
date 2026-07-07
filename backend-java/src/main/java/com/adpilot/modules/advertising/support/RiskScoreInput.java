package com.adpilot.modules.advertising.support;

import java.math.BigDecimal;

/**
 * Immutable input record for risk-score computation.
 *
 * <p>All fields are non-null and represent the four dimensions that feed the
 * deterministic {@link RiskScoreCalculator} formula (Requirement 7.1):
 *
 * <ul>
 *   <li>{@code changeMagnitude} — ratio of absolute difference to current value
 *       (e.g., a bid going from 1.00 to 1.50 → magnitude 0.5). Must be ≥ 0.</li>
 *   <li>{@code dataConfidence} — 0.0–1.0 measure of how reliable the underlying
 *       data is (derived from days of data and statistical significance;
 *       higher = more confident).</li>
 *   <li>{@code historicalVolatility} — 0.0–1.0 normalized volatility of the metric
 *       over the lookback window (higher = more volatile).</li>
 *   <li>{@code absoluteDollarImpact} — estimated absolute daily dollar impact of
 *       the change (≥ 0; larger values increase risk).</li>
 * </ul>
 *
 * @param changeMagnitude     ratio of change (≥ 0)
 * @param dataConfidence      confidence in data quality (0.0–1.0, higher is better)
 * @param historicalVolatility normalized metric volatility (0.0–1.0)
 * @param absoluteDollarImpact estimated daily dollar impact (≥ 0)
 */
public record RiskScoreInput(
        BigDecimal changeMagnitude,
        BigDecimal dataConfidence,
        BigDecimal historicalVolatility,
        BigDecimal absoluteDollarImpact
) {
    public RiskScoreInput {
        if (changeMagnitude == null) throw new IllegalArgumentException("changeMagnitude must not be null");
        if (dataConfidence == null) throw new IllegalArgumentException("dataConfidence must not be null");
        if (historicalVolatility == null) throw new IllegalArgumentException("historicalVolatility must not be null");
        if (absoluteDollarImpact == null) throw new IllegalArgumentException("absoluteDollarImpact must not be null");

        if (changeMagnitude.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("changeMagnitude must be >= 0, got: " + changeMagnitude);
        }
        if (dataConfidence.compareTo(BigDecimal.ZERO) < 0 || dataConfidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("dataConfidence must be in [0.0, 1.0], got: " + dataConfidence);
        }
        if (historicalVolatility.compareTo(BigDecimal.ZERO) < 0 || historicalVolatility.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("historicalVolatility must be in [0.0, 1.0], got: " + historicalVolatility);
        }
        if (absoluteDollarImpact.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("absoluteDollarImpact must be >= 0, got: " + absoluteDollarImpact);
        }
    }
}
