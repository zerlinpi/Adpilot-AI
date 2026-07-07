package com.adpilot.modules.automation.support;

import java.math.BigDecimal;

/**
 * The numeric comparison operators usable in a rule-template
 * {@link RuleCondition.Comparison} leaf (Req 25.3).
 *
 * <p>Each operator compares a linked object's observed metric value against the
 * condition's threshold using {@link BigDecimal#compareTo(BigDecimal)} so that
 * numerically-equal values with different scales (e.g. {@code 30} and
 * {@code 30.00}) compare equal.
 */
public enum RuleComparator {

    /** Strictly greater than: {@code value > threshold}. */
    GT(">") {
        @Override
        public boolean test(BigDecimal value, BigDecimal threshold) {
            return value.compareTo(threshold) > 0;
        }
    },
    /** Greater than or equal: {@code value >= threshold}. */
    GTE(">=") {
        @Override
        public boolean test(BigDecimal value, BigDecimal threshold) {
            return value.compareTo(threshold) >= 0;
        }
    },
    /** Strictly less than: {@code value < threshold}. */
    LT("<") {
        @Override
        public boolean test(BigDecimal value, BigDecimal threshold) {
            return value.compareTo(threshold) < 0;
        }
    },
    /** Less than or equal: {@code value <= threshold}. */
    LTE("<=") {
        @Override
        public boolean test(BigDecimal value, BigDecimal threshold) {
            return value.compareTo(threshold) <= 0;
        }
    },
    /** Numerically equal: {@code value == threshold}. */
    EQ("==") {
        @Override
        public boolean test(BigDecimal value, BigDecimal threshold) {
            return value.compareTo(threshold) == 0;
        }
    },
    /** Numerically not equal: {@code value != threshold}. */
    NE("!=") {
        @Override
        public boolean test(BigDecimal value, BigDecimal threshold) {
            return value.compareTo(threshold) != 0;
        }
    };

    private final String symbol;

    RuleComparator(String symbol) {
        this.symbol = symbol;
    }

    /** The human-readable operator symbol. */
    public String symbol() {
        return symbol;
    }

    /**
     * Apply the operator. Both operands must be non-null; callers guarantee a
     * present metric value before invoking.
     *
     * @param value     the observed metric value
     * @param threshold the condition's threshold
     * @return the result of the comparison
     */
    public abstract boolean test(BigDecimal value, BigDecimal threshold);

    /**
     * Parse a comparator from its token, accepting both the enum name
     * (case-insensitive, e.g. {@code "gt"}) and the symbol (e.g. {@code ">="}).
     *
     * @param token the comparator token
     * @return the matching comparator
     * @throws IllegalArgumentException if {@code token} is null/blank or unknown
     */
    public static RuleComparator from(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("comparator must not be blank");
        }
        String t = token.trim();
        for (RuleComparator c : values()) {
            if (c.name().equalsIgnoreCase(t) || c.symbol.equals(t)) {
                return c;
            }
        }
        throw new IllegalArgumentException("unknown comparator: " + token);
    }
}
