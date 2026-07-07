package com.adpilot.modules.automation.support;

import java.math.BigDecimal;
import java.util.List;

/**
 * An immutable, database-free condition tree for an automation rule template
 * (Req 25.3). A condition is either a numeric {@link Comparison} leaf over a
 * named metric, a boolean combinator ({@link And}, {@link Or}, {@link Not}), or
 * a {@link Constant}.
 *
 * <p>This is the value type the pure {@link RuleConditionEvaluator} operates on,
 * so condition-to-action soundness (Property 5, task 11.6) can be exercised
 * without a database or any JSON parsing. Parsing from the stored
 * {@code condition_json} blob is handled separately by {@link RuleConditionCodec}.
 */
public sealed interface RuleCondition
        permits RuleCondition.Comparison, RuleCondition.And, RuleCondition.Or,
                RuleCondition.Not, RuleCondition.Constant {

    /**
     * A numeric comparison of a single named metric against a threshold. When
     * the named metric is absent from an object's metric map the comparison is
     * {@code false} (an absent metric cannot satisfy a constraint).
     *
     * @param metric     the metric key (normalized lower-case, e.g. {@code acos})
     * @param comparator the comparison operator
     * @param threshold  the threshold to compare against
     */
    record Comparison(String metric, RuleComparator comparator, BigDecimal threshold)
            implements RuleCondition {
        public Comparison {
            if (metric == null || metric.isBlank()) {
                throw new IllegalArgumentException("metric must not be blank");
            }
            if (comparator == null) {
                throw new IllegalArgumentException("comparator must not be null");
            }
            if (threshold == null) {
                throw new IllegalArgumentException("threshold must not be null");
            }
            metric = metric.trim().toLowerCase();
        }
    }

    /** Logical conjunction; an empty list is vacuously {@code true}. */
    record And(List<RuleCondition> conditions) implements RuleCondition {
        public And {
            if (conditions == null) {
                throw new IllegalArgumentException("conditions must not be null");
            }
            conditions = List.copyOf(conditions);
        }
    }

    /** Logical disjunction; an empty list is vacuously {@code false}. */
    record Or(List<RuleCondition> conditions) implements RuleCondition {
        public Or {
            if (conditions == null) {
                throw new IllegalArgumentException("conditions must not be null");
            }
            conditions = List.copyOf(conditions);
        }
    }

    /** Logical negation. */
    record Not(RuleCondition condition) implements RuleCondition {
        public Not {
            if (condition == null) {
                throw new IllegalArgumentException("condition must not be null");
            }
        }
    }

    /** A constant truth value, independent of any metric. */
    record Constant(boolean value) implements RuleCondition {
        /** The always-true condition. */
        public static final Constant TRUE = new Constant(true);
        /** The always-false condition. */
        public static final Constant FALSE = new Constant(false);
    }
}
