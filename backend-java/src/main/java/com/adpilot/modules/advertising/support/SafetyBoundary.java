package com.adpilot.modules.advertising.support;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * A Campaign's <em>resolved</em> effective Safety_Boundary: for each
 * {@link SafetyBoundaryLimit} it carries the winning value and the
 * {@link SafetyBoundaryLevel} that supplied it (Req 22.11 / 49.11).
 *
 * <p>A limit may still be absent here when no level in the hierarchy defined it;
 * callers decide how to treat an undefined limit (typically as "no extra
 * constraint" so an existing hard floor/ceiling still applies). The companion
 * {@link #sourceOf(SafetyBoundaryLimit)} lets the optimizer record on each AI
 * Operation exactly which level a binding limit came from.
 */
public final class SafetyBoundary {

    private final EnumMap<SafetyBoundaryLimit, BigDecimal> values;
    private final EnumMap<SafetyBoundaryLimit, SafetyBoundaryLevel> sources;

    SafetyBoundary(EnumMap<SafetyBoundaryLimit, BigDecimal> values,
                   EnumMap<SafetyBoundaryLimit, SafetyBoundaryLevel> sources) {
        this.values = new EnumMap<>(values);
        this.sources = new EnumMap<>(sources);
    }

    /** The effective value of {@code limit}, or empty when no level defined it. */
    public Optional<BigDecimal> get(SafetyBoundaryLimit limit) {
        return Optional.ofNullable(limit == null ? null : values.get(limit));
    }

    /** The level that supplied the effective {@code limit}, or empty when undefined. */
    public Optional<SafetyBoundaryLevel> sourceOf(SafetyBoundaryLimit limit) {
        return Optional.ofNullable(limit == null ? null : sources.get(limit));
    }

    /** Whether {@code limit} was defined by some level in the hierarchy. */
    public boolean isDefined(SafetyBoundaryLimit limit) {
        return limit != null && values.containsKey(limit);
    }

    public Optional<BigDecimal> getMinBid() {
        return get(SafetyBoundaryLimit.MIN_BID);
    }

    public Optional<BigDecimal> getMaxBid() {
        return get(SafetyBoundaryLimit.MAX_BID);
    }

    public Optional<BigDecimal> getMaxCpc() {
        return get(SafetyBoundaryLimit.MAX_CPC);
    }

    public Optional<BigDecimal> getMaxDailyBudget() {
        return get(SafetyBoundaryLimit.MAX_DAILY_BUDGET);
    }

    /** Read-only view of every resolved limit value. */
    public Map<SafetyBoundaryLimit, BigDecimal> values() {
        return Collections.unmodifiableMap(new EnumMap<>(values));
    }

    /** Read-only view of the winning level for every resolved limit. */
    public Map<SafetyBoundaryLimit, SafetyBoundaryLevel> sources() {
        return Collections.unmodifiableMap(new EnumMap<>(sources));
    }

    @Override
    public String toString() {
        return "SafetyBoundary{values=" + values + ", sources=" + sources + '}';
    }
}
