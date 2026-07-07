package com.adpilot.modules.advertising.hosting;

/**
 * Result of a {@link DataQualityGate} check for a campaign.
 *
 * <p>When {@code passed} is {@code true}, the campaign is eligible for optimization.
 * When {@code false}, the {@code reason} field indicates why the gate refused
 * optimization (DATA_STALE or DATA_INCOMPLETE).</p>
 *
 * <p>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6.</p>
 *
 * @param passed whether the data quality check passed
 * @param reason the rejection reason ({@code null} on pass; one of
 *               {@link #DATA_STALE} or {@link #DATA_INCOMPLETE} on failure)
 */
public record DataQualityResult(boolean passed, String reason) {

    /** Rejection reason: performance data is older than the configured freshness window. */
    public static final String DATA_STALE = "DATA_STALE";

    /** Rejection reason: finalized report coverage does not cover the required lookback range. */
    public static final String DATA_INCOMPLETE = "DATA_INCOMPLETE";

    /** A passing result indicating the campaign is eligible for optimization. */
    public static DataQualityResult pass() {
        return new DataQualityResult(true, null);
    }

    /** A failing result due to stale data (Req 3.3). */
    public static DataQualityResult stale() {
        return new DataQualityResult(false, DATA_STALE);
    }

    /** A failing result due to incomplete coverage (Req 3.4). */
    public static DataQualityResult incomplete() {
        return new DataQualityResult(false, DATA_INCOMPLETE);
    }
}
