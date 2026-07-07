package com.adpilot.modules.advertising.support;

import java.math.BigDecimal;

/**
 * An honest AI sales-change / spend-savings <strong>estimate</strong>
 * (Requirement 19.6, 19.7, 19.8, 50.12).
 *
 * <p>An estimate is always computed against a recorded <em>baseline</em> (a
 * pre-period baseline or a control/forecast) and carries its full estimation
 * method: the baseline used, the attribution window, a confidence indicator,
 * and the algorithm version (Req 19.7). It is therefore an estimate — never a
 * naive before/after delta of two raw period totals (Req 19.6).</p>
 *
 * <p>When there is no valid baseline the figure <strong>degrades honestly</strong>:
 * it is signaled as not-estimable, carries no fabricated value, and exposes the
 * fixed {@link #NOT_ESTIMABLE_SIGNAL} for the frontend to display verbatim
 * (Req 19.8, 50.12).</p>
 *
 * <p>This value object is the single source of truth targeted by the honest
 * AI-estimate property test (Property 46, task 14.24) and consumed by the AI
 * hosting trend area (task 14.10).</p>
 *
 * @param estimable             whether a valid baseline existed and a figure
 *                              could be estimated
 * @param value                 the estimated change (e.g. estimated sales lift
 *                              or spend saved), or {@code null} when not estimable
 * @param baseline              the pre-period baseline / control-forecast the
 *                              estimate was measured against, or {@code null}
 *                              when not estimable
 * @param attributionWindowDays the attribution window in days, or {@code 0}
 *                              when not estimable
 * @param confidence            the confidence indicator, or {@code null} when
 *                              not estimable
 * @param algorithmVersion      the estimation algorithm version, or {@code null}
 *                              when not estimable
 * @param signal                {@link #NOT_ESTIMABLE_SIGNAL} when not estimable,
 *                              otherwise {@code null}
 */
public record AiEstimate(boolean estimable,
                         BigDecimal value,
                         BigDecimal baseline,
                         int attributionWindowDays,
                         Confidence confidence,
                         String algorithmVersion,
                         String signal) {

    /**
     * The fixed indication the frontend displays for a not-estimable figure
     * (Requirement 19.8, 50.12). This exact string must not be altered.
     */
    public static final String NOT_ESTIMABLE_SIGNAL = "暂不可估算：缺少有效基线";

    /** Confidence indicator carried by an AI estimate (Requirement 19.7). */
    public enum Confidence {
        LOW, MEDIUM, HIGH
    }

    /**
     * The not-estimable result: no valid baseline, no fabricated value, and the
     * fixed display signal (Requirement 19.8, 50.12).
     */
    public static AiEstimate notEstimable() {
        return new AiEstimate(false, null, null, 0, null, null, NOT_ESTIMABLE_SIGNAL);
    }

    /**
     * Computes an AI estimate of {@code observed} against a {@code baseline}
     * control/forecast, recording the full estimation method (Req 19.6, 19.7).
     *
     * <p>When {@code baseline} is {@code null} there is no valid baseline, so the
     * figure degrades honestly to {@link #notEstimable()} (Req 19.8). When a
     * baseline is present the estimate is {@code observed - baseline} measured
     * over the attribution window — an estimate relative to the counterfactual
     * baseline, not a naive before/after delta of two raw period totals.</p>
     *
     * @param observed              the observed value over the attribution window
     *                              (treated as {@code 0} when {@code null})
     * @param baseline              the pre-period baseline / control-forecast; a
     *                              {@code null} baseline yields a not-estimable result
     * @param attributionWindowDays the attribution window in days (must be
     *                              positive when a baseline is present)
     * @param confidence            the confidence indicator (required when a
     *                              baseline is present)
     * @param algorithmVersion      the estimation algorithm version (required and
     *                              non-blank when a baseline is present)
     * @return an estimable {@link AiEstimate} carrying its method, or
     *         {@link #notEstimable()} when no valid baseline exists
     * @throws IllegalArgumentException when a baseline is present but the
     *         estimation method components (window, confidence, version) are
     *         missing — an estimate must always carry its method (Req 19.7)
     */
    public static AiEstimate of(BigDecimal observed,
                                BigDecimal baseline,
                                int attributionWindowDays,
                                Confidence confidence,
                                String algorithmVersion) {
        if (baseline == null) {
            // Req 19.8: no valid baseline -> signal not-estimable, never fabricate.
            return notEstimable();
        }
        if (attributionWindowDays <= 0 || confidence == null
                || algorithmVersion == null || algorithmVersion.isBlank()) {
            // Req 19.7: an estimable figure must carry its full method.
            throw new IllegalArgumentException(
                    "an AI estimate with a baseline must record attribution window, confidence, and algorithm version");
        }
        BigDecimal observedValue = observed == null ? BigDecimal.ZERO : observed;
        BigDecimal estimate = observedValue.subtract(baseline);
        return new AiEstimate(true, estimate, baseline, attributionWindowDays,
                confidence, algorithmVersion.trim(), null);
    }
}
