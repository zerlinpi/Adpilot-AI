package com.adpilot.modules.advertising.support;

/**
 * Defines how "more restrictive" is computed for each {@link SafetyBoundaryLimit}.
 *
 * <p>When folding boundary values across the 5-level hierarchy
 * (campaign → goal → store → organization → system), the comparison semantics
 * determine which value wins when two levels both define the same limit
 * (Req 6.2, 6.4).
 */
public enum BoundaryComparison {

    /**
     * The smaller value is more restrictive (e.g., maxBid, maxCpc, maxDailyBudget).
     * Resolution picks {@code min(a, b)}.
     */
    UPPER_BOUND,

    /**
     * The larger value is more restrictive (e.g., minBid, minimumDataDays, cooldownHours).
     * Resolution picks {@code max(a, b)}.
     */
    LOWER_BOUND,

    /**
     * Boolean OR: if any level enables it, it is enabled (e.g., emergencyStop).
     * Resolution picks {@code a || b}.
     */
    BOOLEAN_OR,

    /**
     * Set intersection: an action is permitted only if permitted at every level.
     * Resolution picks the intersection of the two sets.
     */
    SET_INTERSECTION
}
