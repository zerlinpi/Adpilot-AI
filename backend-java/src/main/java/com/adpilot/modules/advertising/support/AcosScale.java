package com.adpilot.modules.advertising.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The single, authoritative encoding of the ACoS scale convention (Requirement 17): ACoS is
 * <strong>stored and compared as a decimal ratio</strong> (so {@code 0.1492} denotes 14.92% and a
 * 25% target is stored as {@code 0.25}) and is multiplied by 100 <strong>only for display</strong>.
 *
 * <p>This is a <strong>pure</strong>, stateless utility: every method is side-effect free and free of
 * persistence, JSON, or framework concerns, so it can be unit- and property-tested in isolation
 * (Properties 39 and 40, tasks 14.17 / 14.18). It is the one place that defines:</p>
 * <ul>
 *   <li>storage / parsing of a ratio from a percentage ({@link #ratioFromPercent}),</li>
 *   <li>display conversion of a ratio to a percentage ({@link #percentForDisplay}),</li>
 *   <li>threshold comparison performed on decimal ratios ({@link #compareRatios}, {@link #ratiosEqual}),
 *       and</li>
 *   <li>the per-column historical migration classifier ({@link #migrate}).</li>
 * </ul>
 *
 * <p>Keeping the convention in one pure place is what lets every layer — the database stored values,
 * the API request/response fields, the filter/query parameters ({@code targetAcosMin}/{@code
 * targetAcosMax}), the form-input parsing, and the keyword-intelligence module — represent ACoS as
 * the same decimal ratio without any layer mixing a percentage-scaled value with a ratio
 * (Requirement 17.7).</p>
 *
 * <p>Validates: Requirements 17.1, 17.2, 17.3, 17.4, 17.5, 17.7.</p>
 */
public final class AcosScale {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** Decimal places retained for a stored/compared ratio (matches {@code DECIMAL(10,6)} columns). */
    public static final int RATIO_SCALE = 6;

    /** Decimal places used when rendering a ratio as a display percentage. */
    public static final int DISPLAY_SCALE = 2;

    /**
     * Upper bound on a <em>plausible</em> ACoS decimal ratio. ACoS above 100% (ratio {@code > 1}) is
     * legitimate for an under-performing target, but a ratio above {@code 100} (i.e. 10,000% ACoS) is
     * almost certainly a percentage-scaled value that leaked into a ratio column (or vice versa), so
     * such a value is treated as contradicting its column's declared semantics and is flagged for
     * manual review rather than guessed (Requirement 17.6).
     */
    public static final BigDecimal MAX_PLAUSIBLE_RATIO = new BigDecimal("100");

    private AcosScale() {
        // Utility class — not instantiable.
    }

    // ---------------------------------------------------------------------
    // Storage / display / parsing (Req 17.1, 17.2, 17.7)
    // ---------------------------------------------------------------------

    /**
     * Convert a stored decimal ratio into the percentage value used for display
     * (Requirement 17.2): {@code ratio * 100}, rounded to {@link #DISPLAY_SCALE} decimals. For
     * example {@code 0.1492} renders as {@code 14.92}.
     *
     * @param ratio the stored decimal ratio; {@code null} is treated as zero
     * @return the percentage for display, never {@code null}
     */
    public static BigDecimal percentForDisplay(BigDecimal ratio) {
        return nvl(ratio).multiply(HUNDRED).setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Parse / convert a percentage (as typed in a form or carried on a percentage-scaled field) into
     * the canonical stored decimal ratio (Requirement 17.1, 17.7): {@code percent / 100}, rounded to
     * {@link #RATIO_SCALE} decimals. For example {@code 25} (a 25% target) stores as {@code 0.250000}.
     *
     * @param percent the percentage value; {@code null} is treated as zero
     * @return the canonical stored decimal ratio, never {@code null}
     */
    public static BigDecimal ratioFromPercent(BigDecimal percent) {
        return nvl(percent).divide(HUNDRED, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Normalize an already-decimal-ratio value to the canonical {@link #RATIO_SCALE} so two ratios
     * that denote the same ACoS compare and store identically.
     *
     * @param ratio the decimal ratio; {@code null} is treated as zero
     * @return the ratio rounded to {@link #RATIO_SCALE} decimals, never {@code null}
     */
    public static BigDecimal normalizeRatio(BigDecimal ratio) {
        return nvl(ratio).setScale(RATIO_SCALE, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------------
    // Comparison on decimal ratios (Req 17.3, 17.4)
    // ---------------------------------------------------------------------

    /**
     * Compare two ACoS values <strong>as stored decimal ratios</strong> (Requirement 17.3), the
     * comparison the recommendation engine MUST use when testing an ACoS against a threshold.
     *
     * @param a the first ratio; {@code null} is treated as zero
     * @param b the second ratio; {@code null} is treated as zero
     * @return a negative, zero, or positive value as {@code a} is less than, equal to, or greater
     *         than {@code b} as ratios
     */
    public static int compareRatios(BigDecimal a, BigDecimal b) {
        return nvl(a).compareTo(nvl(b));
    }

    /**
     * Report whether two ACoS ratios denote the same value, ignoring trailing-zero scale differences
     * (Requirement 17.4): a stored {@code 0.1492} and a display of {@code 14.92%} are the same ratio.
     *
     * @param a the first ratio; {@code null} is treated as zero
     * @param b the second ratio; {@code null} is treated as zero
     * @return {@code true} iff the two ratios are numerically equal
     */
    public static boolean ratiosEqual(BigDecimal a, BigDecimal b) {
        return compareRatios(a, b) == 0;
    }

    /**
     * Report whether a percentage display value and a stored decimal ratio denote the same ACoS
     * (Requirement 17.4). For example {@code displayMatchesRatio(14.92, 0.1492)} is {@code true}.
     *
     * @param displayPercent the percentage as displayed; {@code null} is treated as zero
     * @param storedRatio    the stored decimal ratio; {@code null} is treated as zero
     * @return {@code true} iff {@code displayPercent / 100} equals {@code storedRatio} as a ratio
     */
    public static boolean displayMatchesRatio(BigDecimal displayPercent, BigDecimal storedRatio) {
        return ratiosEqual(ratioFromPercent(displayPercent), storedRatio);
    }

    // ---------------------------------------------------------------------
    // Per-column historical migration (Req 17.5, 17.6)
    // ---------------------------------------------------------------------

    /**
     * Classify a single historical ACoS value against its column's {@link AcosColumnScale known
     * source semantics}, producing the migration {@link AcosMigrationOutcome outcome} on the canonical
     * decimal-ratio scale (Requirement 17.5). This never applies a naive "divide by 100 if greater
     * than 1" rule; instead it migrates strictly by the declared per-column scale and flags any value
     * it cannot safely convert (Requirement 17.6).
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>A {@code null} value migrates {@link AcosMigrationOutcome.Kind#UNCHANGED unchanged}
     *       (nothing to convert).</li>
     *   <li>{@link AcosColumnScale#DECIMAL_RATIO}: the value is already a ratio and is kept unchanged,
     *       unless it is negative or exceeds {@link #MAX_PLAUSIBLE_RATIO} (contradicting the declared
     *       ratio semantics), in which case it is {@link AcosMigrationOutcome.Kind#AMBIGUOUS
     *       flagged}.</li>
     *   <li>{@link AcosColumnScale#PERCENT}: the value is divided by 100 to a ratio, unless it is
     *       negative or the resulting ratio exceeds {@link #MAX_PLAUSIBLE_RATIO}, in which case it is
     *       flagged.</li>
     *   <li>{@link AcosColumnScale#UNKNOWN}: a zero value migrates unchanged (zero is unambiguous on
     *       either scale); every other value is flagged, because it could denote either a ratio or a
     *       percentage (for example {@code 1.5} could be 150% or 1.5%).</li>
     * </ul>
     *
     * @param raw   the historical stored value (may be {@code null})
     * @param scale the column's known source semantics; must not be {@code null}
     * @return the migration outcome, never {@code null}
     * @throws IllegalArgumentException if {@code scale} is {@code null}
     */
    public static AcosMigrationOutcome migrate(BigDecimal raw, AcosColumnScale scale) {
        if (scale == null) {
            throw new IllegalArgumentException("scale must not be null");
        }
        if (raw == null) {
            return AcosMigrationOutcome.unchanged(null);
        }
        switch (scale) {
            case DECIMAL_RATIO:
                if (raw.signum() < 0) {
                    return AcosMigrationOutcome.ambiguous(
                            "negative value " + raw + " under decimal-ratio semantics");
                }
                if (raw.compareTo(MAX_PLAUSIBLE_RATIO) > 0) {
                    return AcosMigrationOutcome.ambiguous(
                            "value " + raw + " exceeds the plausible decimal-ratio bound "
                                    + MAX_PLAUSIBLE_RATIO + "; it may be a percentage-scaled value");
                }
                return AcosMigrationOutcome.unchanged(normalizeRatio(raw));

            case PERCENT:
                if (raw.signum() < 0) {
                    return AcosMigrationOutcome.ambiguous(
                            "negative value " + raw + " under percentage semantics");
                }
                BigDecimal converted = ratioFromPercent(raw);
                if (converted.compareTo(MAX_PLAUSIBLE_RATIO) > 0) {
                    return AcosMigrationOutcome.ambiguous(
                            "value " + raw + " converts to ratio " + converted
                                    + ", which exceeds the plausible bound " + MAX_PLAUSIBLE_RATIO
                                    + "; it may already be a decimal ratio");
                }
                return AcosMigrationOutcome.converted(converted);

            case UNKNOWN:
            default:
                if (raw.signum() == 0) {
                    return AcosMigrationOutcome.unchanged(normalizeRatio(raw));
                }
                return AcosMigrationOutcome.ambiguous(
                        "column scale is unknown; value " + raw
                                + " could denote either a decimal ratio or a percentage");
        }
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
