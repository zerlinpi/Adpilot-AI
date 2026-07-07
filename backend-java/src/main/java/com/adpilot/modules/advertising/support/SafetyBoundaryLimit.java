package com.adpilot.modules.advertising.support;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The individual hard limits that make up a Campaign's effective Safety_Boundary
 * (安全边界) — the bounds the AI optimizer can never cross regardless of the
 * resolved AI_Personality (Req 6, 22, 49).
 *
 * <p>Each constant names one limit that is resolved independently through the
 * {@link SafetyBoundaryLevel} precedence hierarchy by
 * {@link SafetyBoundaryResolver}. Every limit declares its
 * {@link #comparisonSemantics()} so the resolver can fold values across levels
 * using {@link #moreRestrictive(BigDecimal, BigDecimal)} — inheritance can only
 * tighten (Req 6.2, 6.4).
 */
public enum SafetyBoundaryLimit {

    // ──────────────────────────────────────────────────────────────────────────
    // Upper-bound limits: smaller is more restrictive (Req 6.2)
    // ──────────────────────────────────────────────────────────────────────────

    /** Absolute bid ceiling; a hosted bid is never clamped above this. */
    MAX_BID(BoundaryComparison.UPPER_BOUND, "amount"),

    /** Absolute maximum cost-per-click the optimizer may target. */
    MAX_CPC(BoundaryComparison.UPPER_BOUND, "amount"),

    /** Absolute maximum daily budget the optimizer may set for a Campaign. */
    MAX_DAILY_BUDGET(BoundaryComparison.UPPER_BOUND, "amount"),

    /** Maximum ratio by which a single bid adjustment may change the bid (e.g., 1.5 = +50%). */
    MAX_BID_ADJUSTMENT_RATIO(BoundaryComparison.UPPER_BOUND, "ratio"),

    /** Maximum ratio by which daily budget may be increased in a single run. */
    MAX_DAILY_BUDGET_INCREASE_RATIO(BoundaryComparison.UPPER_BOUND, "ratio"),

    /** Maximum ratio by which daily budget may be decreased in a single run. */
    MAX_DAILY_BUDGET_DECREASE_RATIO(BoundaryComparison.UPPER_BOUND, "ratio"),

    /** Maximum number of Operations the optimizer may create per optimization run. */
    MAX_OPERATIONS_PER_RUN(BoundaryComparison.UPPER_BOUND, "integer"),

    /** Maximum number of Operations the optimizer may create per calendar day. */
    MAX_OPERATIONS_PER_DAY(BoundaryComparison.UPPER_BOUND, "integer"),

    /** Maximum keyword additions per day. */
    MAX_KEYWORDS_PER_DAY(BoundaryComparison.UPPER_BOUND, "integer"),

    /** Maximum negative keyword additions per day. */
    MAX_NEGATIVES_PER_DAY(BoundaryComparison.UPPER_BOUND, "integer"),

    /** Break-even ACoS: advertising spend equals gross margin; hard stop boundary. */
    BREAK_EVEN_ACOS(BoundaryComparison.UPPER_BOUND, "ratio"),

    /** Emergency multiplier: daily spend / budget threshold that triggers emergency stop. */
    EMERGENCY_SPEND_TO_BUDGET_MULTIPLIER(BoundaryComparison.UPPER_BOUND, "ratio"),

    /** Emergency multiplier: ACoS / target ACoS threshold that triggers emergency stop. */
    EMERGENCY_ACOS_TO_TARGET_MULTIPLIER(BoundaryComparison.UPPER_BOUND, "ratio"),

    // ──────────────────────────────────────────────────────────────────────────
    // Lower-bound limits: larger is more restrictive (Req 6.2)
    // ──────────────────────────────────────────────────────────────────────────

    /** Absolute bid floor; a hosted bid is never clamped below this. */
    MIN_BID(BoundaryComparison.LOWER_BOUND, "amount"),

    /** Absolute minimum daily budget the optimizer may set for a Campaign. */
    MIN_DAILY_BUDGET(BoundaryComparison.LOWER_BOUND, "amount"),

    /** Minimum days of performance data required before the optimizer will act. */
    MINIMUM_DATA_DAYS(BoundaryComparison.LOWER_BOUND, "integer"),

    /** Learning period in days during which the optimizer is conservative. */
    LEARNING_PERIOD_DAYS(BoundaryComparison.LOWER_BOUND, "integer"),

    /** Hours of cooldown between adjustments to the same entity. */
    COOLDOWN_HOURS(BoundaryComparison.LOWER_BOUND, "integer"),

    /** Inventory safety threshold: below this, no increases allowed. */
    INVENTORY_SAFETY_DAYS(BoundaryComparison.LOWER_BOUND, "integer"),

    /** Inventory healthy threshold: above this, normal optimization allowed. */
    INVENTORY_HEALTHY_DAYS(BoundaryComparison.LOWER_BOUND, "integer"),

    /** Inventory critical threshold: below this, emergency response triggered. */
    INVENTORY_CRITICAL_DAYS(BoundaryComparison.LOWER_BOUND, "integer"),

    // ──────────────────────────────────────────────────────────────────────────
    // Boolean OR: if any level enables it, it is enabled (Req 6.2)
    // ──────────────────────────────────────────────────────────────────────────

    /** Emergency stop flag: halts all AI operations for a campaign when enabled. */
    EMERGENCY_STOP(BoundaryComparison.BOOLEAN_OR, "boolean");

    // Note: allowed-action sets (SET_INTERSECTION) are not modeled as individual
    // enum constants here because they represent a set-valued limit, not a scalar.
    // They are handled separately via the SafetyBoundary set-intersection logic.

    private final BoundaryComparison comparisonSemantics;
    private final String valueType;

    SafetyBoundaryLimit(BoundaryComparison comparisonSemantics, String valueType) {
        this.comparisonSemantics = comparisonSemantics;
        this.valueType = valueType;
    }

    /**
     * The comparison semantics for this limit, governing how "more restrictive"
     * is determined when folding across boundary levels.
     */
    public BoundaryComparison comparisonSemantics() {
        return comparisonSemantics;
    }

    /**
     * The value type stored in the persistence layer for this limit
     * (amount, ratio, integer, boolean).
     */
    public String valueType() {
        return valueType;
    }

    /**
     * Compute the more-restrictive of two values for this limit's comparison semantics.
     *
     * <p>Returns the value that should win when two levels both define this limit:
     * <ul>
     *   <li>{@link BoundaryComparison#UPPER_BOUND}: {@code min(a, b)} — smaller is tighter</li>
     *   <li>{@link BoundaryComparison#LOWER_BOUND}: {@code max(a, b)} — larger is tighter</li>
     *   <li>{@link BoundaryComparison#BOOLEAN_OR}: {@code max(a, b)} — any non-zero enables</li>
     * </ul>
     *
     * <p>For {@link BoundaryComparison#SET_INTERSECTION} this method is not applicable
     * (sets are not representable as a single BigDecimal); callers must use the
     * set-specific intersection logic.
     *
     * @param a first value (non-null)
     * @param b second value (non-null)
     * @return the more-restrictive value
     * @throws IllegalArgumentException if called on a SET_INTERSECTION limit
     */
    public BigDecimal moreRestrictive(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) {
            return null;
        }
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return switch (comparisonSemantics) {
            case UPPER_BOUND -> a.compareTo(b) <= 0 ? a : b;
            case LOWER_BOUND -> a.compareTo(b) >= 0 ? a : b;
            case BOOLEAN_OR -> (a.compareTo(BigDecimal.ZERO) != 0 || b.compareTo(BigDecimal.ZERO) != 0)
                    ? BigDecimal.ONE : BigDecimal.ZERO;
            case SET_INTERSECTION -> throw new IllegalArgumentException(
                    "moreRestrictive(BigDecimal, BigDecimal) is not applicable for SET_INTERSECTION limits; " +
                    "use the set-specific intersection logic instead.");
        };
    }

    /**
     * Convenience: fold an optional incoming value with a new candidate,
     * returning the more-restrictive result.
     *
     * @param existing the existing resolved value (may be empty)
     * @param candidate the new candidate from a level (non-null)
     * @return the more-restrictive value
     */
    public BigDecimal moreRestrictive(Optional<BigDecimal> existing, BigDecimal candidate) {
        return existing.map(e -> moreRestrictive(e, candidate)).orElse(candidate);
    }
}
