package com.adpilot.modules.advertising.support;

import java.math.BigDecimal;

/**
 * Immutable result of a risk-score computation.
 *
 * <p>Contains the bounded score (0.0–1.0) and the formula version string that
 * identifies which version of the scoring algorithm produced it. The formula
 * version is recorded in the Decision_Snapshot so auditors can verify which
 * algorithm was in effect (Requirement 7.9).
 *
 * @param score          deterministic risk score in [0.0, 1.0]
 * @param formulaVersion version identifier of the formula (e.g., "v1")
 */
public record RiskScoreResult(
        BigDecimal score,
        String formulaVersion
) {
    public RiskScoreResult {
        if (score == null) throw new IllegalArgumentException("score must not be null");
        if (formulaVersion == null || formulaVersion.isBlank()) {
            throw new IllegalArgumentException("formulaVersion must not be null or blank");
        }
        if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("score must be in [0.0, 1.0], got: " + score);
        }
    }
}
