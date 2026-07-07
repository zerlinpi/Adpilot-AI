package com.adpilot.modules.advertising.support;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable, <em>partial</em> set of Safety_Boundary limits contributed by a
 * single {@link SafetyBoundaryLevel}.
 *
 * <p>"Partial" is the key idea behind the resolution hierarchy (Req 22.11): a
 * level need not define every limit. A limit that the level leaves
 * <strong>undefined</strong> (absent / {@code null}) does not participate at that
 * level, so resolution falls through to the next level. A limit that is
 * <strong>defined</strong> wins as soon as it is reached in precedence order.
 *
 * <p>This type is a pure value object: it holds no Spring dependencies and is
 * safe to construct directly in tests, which is what the Safety_Boundary
 * resolution-precedence property test (Property 51, task 13.9) relies on.
 */
public final class SafetyBoundaryLimits {

    private static final SafetyBoundaryLimits EMPTY =
            new SafetyBoundaryLimits(new EnumMap<>(SafetyBoundaryLimit.class));

    private final EnumMap<SafetyBoundaryLimit, BigDecimal> values;

    private SafetyBoundaryLimits(EnumMap<SafetyBoundaryLimit, BigDecimal> values) {
        // Defensive copy so the instance is genuinely immutable.
        this.values = new EnumMap<>(values);
    }

    /** A limit set that defines nothing — every limit falls through to a lower level. */
    public static SafetyBoundaryLimits empty() {
        return EMPTY;
    }

    /** Start building a partial limit set; only the limits you set are "defined". */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Whether this level defines {@code limit}. Only defined limits participate
     * in resolution at this level.
     */
    public boolean isDefined(SafetyBoundaryLimit limit) {
        return limit != null && values.containsKey(limit);
    }

    /**
     * The value this level assigns to {@code limit}, or empty when this level
     * leaves the limit undefined.
     */
    public Optional<BigDecimal> get(SafetyBoundaryLimit limit) {
        return Optional.ofNullable(limit == null ? null : values.get(limit));
    }

    /** Read-only view of every limit this level defines. */
    public Map<SafetyBoundaryLimit, BigDecimal> asMap() {
        return new EnumMap<>(values);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SafetyBoundaryLimits)) {
            return false;
        }
        return values.equals(((SafetyBoundaryLimits) o).values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values);
    }

    @Override
    public String toString() {
        return "SafetyBoundaryLimits" + values;
    }

    /**
     * Fluent builder for a partial limit set. Setting a limit to {@code null}
     * (or never calling its setter) leaves the limit undefined at this level.
     */
    public static final class Builder {

        private final EnumMap<SafetyBoundaryLimit, BigDecimal> values =
                new EnumMap<>(SafetyBoundaryLimit.class);

        private Builder() {
        }

        /** Define (or, with {@code null}, clear) an arbitrary limit. */
        public Builder limit(SafetyBoundaryLimit limit, BigDecimal value) {
            Objects.requireNonNull(limit, "limit must not be null");
            if (value == null) {
                values.remove(limit);
            } else {
                values.put(limit, value);
            }
            return this;
        }

        public Builder minBid(BigDecimal value) {
            return limit(SafetyBoundaryLimit.MIN_BID, value);
        }

        public Builder maxBid(BigDecimal value) {
            return limit(SafetyBoundaryLimit.MAX_BID, value);
        }

        public Builder maxCpc(BigDecimal value) {
            return limit(SafetyBoundaryLimit.MAX_CPC, value);
        }

        public Builder maxDailyBudget(BigDecimal value) {
            return limit(SafetyBoundaryLimit.MAX_DAILY_BUDGET, value);
        }

        public Builder maxBidAdjustmentRatio(BigDecimal value) {
            return limit(SafetyBoundaryLimit.MAX_BID_ADJUSTMENT_RATIO, value);
        }

        public Builder maxDailyBudgetIncreaseRatio(BigDecimal value) {
            return limit(SafetyBoundaryLimit.MAX_DAILY_BUDGET_INCREASE_RATIO, value);
        }

        public Builder maxDailyBudgetDecreaseRatio(BigDecimal value) {
            return limit(SafetyBoundaryLimit.MAX_DAILY_BUDGET_DECREASE_RATIO, value);
        }

        public Builder minDailyBudget(BigDecimal value) {
            return limit(SafetyBoundaryLimit.MIN_DAILY_BUDGET, value);
        }

        public Builder maxOperationsPerRun(BigDecimal value) {
            return limit(SafetyBoundaryLimit.MAX_OPERATIONS_PER_RUN, value);
        }

        public Builder maxOperationsPerDay(BigDecimal value) {
            return limit(SafetyBoundaryLimit.MAX_OPERATIONS_PER_DAY, value);
        }

        public Builder minimumDataDays(BigDecimal value) {
            return limit(SafetyBoundaryLimit.MINIMUM_DATA_DAYS, value);
        }

        public Builder learningPeriodDays(BigDecimal value) {
            return limit(SafetyBoundaryLimit.LEARNING_PERIOD_DAYS, value);
        }

        public Builder cooldownHours(BigDecimal value) {
            return limit(SafetyBoundaryLimit.COOLDOWN_HOURS, value);
        }

        public Builder inventorySafetyDays(BigDecimal value) {
            return limit(SafetyBoundaryLimit.INVENTORY_SAFETY_DAYS, value);
        }

        public Builder inventoryHealthyDays(BigDecimal value) {
            return limit(SafetyBoundaryLimit.INVENTORY_HEALTHY_DAYS, value);
        }

        public Builder inventoryCriticalDays(BigDecimal value) {
            return limit(SafetyBoundaryLimit.INVENTORY_CRITICAL_DAYS, value);
        }

        public Builder breakEvenAcos(BigDecimal value) {
            return limit(SafetyBoundaryLimit.BREAK_EVEN_ACOS, value);
        }

        public Builder emergencySpendToBudgetMultiplier(BigDecimal value) {
            return limit(SafetyBoundaryLimit.EMERGENCY_SPEND_TO_BUDGET_MULTIPLIER, value);
        }

        public Builder emergencyAcosToTargetMultiplier(BigDecimal value) {
            return limit(SafetyBoundaryLimit.EMERGENCY_ACOS_TO_TARGET_MULTIPLIER, value);
        }

        public Builder maxKeywordsPerDay(BigDecimal value) {
            return limit(SafetyBoundaryLimit.MAX_KEYWORDS_PER_DAY, value);
        }

        public Builder maxNegativesPerDay(BigDecimal value) {
            return limit(SafetyBoundaryLimit.MAX_NEGATIVES_PER_DAY, value);
        }

        public Builder emergencyStop(BigDecimal value) {
            return limit(SafetyBoundaryLimit.EMERGENCY_STOP, value);
        }

        public SafetyBoundaryLimits build() {
            return new SafetyBoundaryLimits(values);
        }
    }
}
