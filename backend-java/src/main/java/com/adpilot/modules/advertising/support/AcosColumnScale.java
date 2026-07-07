package com.adpilot.modules.advertising.support;

/**
 * The <em>known source semantics</em> of a single historical ACoS column, used to drive the
 * per-column migration to the canonical decimal-ratio scale (Requirement 17.5).
 *
 * <p>The migration deliberately keys off this declared, per-column scale rather than applying a
 * naive "divide by 100 if greater than 1" heuristic, because the same raw number can be legitimate
 * under more than one scale (for example {@code 1.5} is a perfectly valid 150% ACoS ratio, but it
 * is also a perfectly valid 1.5% percentage). Only when a column's scale is genuinely undetermined
 * is a value treated as ambiguous and flagged for manual review (Requirement 17.6).</p>
 */
public enum AcosColumnScale {

    /**
     * The column already stores a canonical decimal ratio (for example {@code 0.1492} for 14.92%).
     * No conversion is applied; values are validated and any value contradicting the declared
     * semantics (negative, or implausibly large) is flagged rather than guessed.
     */
    DECIMAL_RATIO,

    /**
     * The column stores a percentage-scaled value (for example {@code 14.92} for 14.92%). Migration
     * divides by 100 to produce the canonical decimal ratio.
     */
    PERCENT,

    /**
     * The column's source scale cannot be determined. Every non-zero value is ambiguous under
     * Requirement 17.6 and is flagged for manual review rather than guessed. Zero is unambiguous
     * (it denotes the same ratio on either scale) and migrates unchanged.
     */
    UNKNOWN
}
