package com.adpilot.modules.automation.support;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link RuleConditionEvaluator}.
 *
 * <p>Feature: app-functionality-completion, Property 5: Condition-to-action rule
 * evaluation is sound.
 *
 * <p>Validates: Requirements 25.3.
 *
 * <p>For any linked object with arbitrary metrics and any rule template with a
 * condition and an action, the template's action is applied to the object
 * <strong>exactly when</strong> the condition evaluates {@code true} for that
 * object's metrics, and is <strong>never</strong> applied when the condition
 * evaluates {@code false}.
 *
 * <p>Soundness is checked two ways for each generated (condition, metrics)
 * pair:
 * <ul>
 *   <li>against an <em>independent reference evaluator</em>
 *       ({@link #reference(RuleCondition, Map)}) re-implemented from the
 *       documented semantics, so the production evaluator's truth value is not
 *       merely compared against itself; and</li>
 *   <li>against {@link RuleConditionEvaluator#resolveAction}, asserting the
 *       action is present iff (and only iff) the condition is true.</li>
 * </ul>
 */
class RuleConditionEvaluatorProperties {

    /** The metric keys the generators draw from (a mix of present/absent). */
    private static final List<String> METRIC_KEYS =
            List.of("acos", "tacos", "spend", "sales", "clicks", "orders");

    /**
     * Feature: app-functionality-completion, Property 5: Condition-to-action rule
     * evaluation is sound.
     *
     * <p>Validates: Requirements 25.3.
     *
     * <p>The action returned by {@link RuleConditionEvaluator#resolveAction} is
     * present exactly when the condition is true for the object's metrics, and
     * absent exactly when it is false — cross-checked against an independent
     * reference evaluator.
     */
    @Property(tries = 300)
    void actionAppliedExactlyWhenConditionTrue(
            @ForAll("conditions") RuleCondition condition,
            @ForAll("metricMaps") Map<String, BigDecimal> metrics,
            @ForAll("actions") RuleAction action) {

        boolean expected = reference(condition, metrics);
        boolean actual = RuleConditionEvaluator.evaluate(condition, metrics);

        // Production evaluator agrees with the independent reference semantics.
        assertThat(actual).isEqualTo(expected);

        Optional<RuleAction> resolved =
                RuleConditionEvaluator.resolveAction(condition, action, metrics);

        // The action is applied (present) exactly when the condition is true,
        // and never when it is false.
        assertThat(resolved.isPresent()).isEqualTo(expected);
        if (expected) {
            assertThat(resolved).contains(action);
        } else {
            assertThat(resolved).isEmpty();
        }
    }

    /**
     * Feature: app-functionality-completion, Property 5: Condition-to-action rule
     * evaluation is sound.
     *
     * <p>Validates: Requirements 25.3.
     *
     * <p>Determinism: evaluating the same condition against the same metrics
     * always yields the same decision, so the action decision is stable.
     */
    @Property(tries = 200)
    void evaluationIsDeterministic(
            @ForAll("conditions") RuleCondition condition,
            @ForAll("metricMaps") Map<String, BigDecimal> metrics) {
        boolean first = RuleConditionEvaluator.evaluate(condition, metrics);
        boolean second = RuleConditionEvaluator.evaluate(condition, metrics);
        assertThat(second).isEqualTo(first);
    }

    // --- Independent reference evaluator ------------------------------------

    /**
     * A from-scratch re-implementation of the documented evaluation semantics,
     * used purely as an oracle. An absent/null metric makes a comparison false.
     */
    private static boolean reference(RuleCondition condition, Map<String, BigDecimal> metrics) {
        if (condition instanceof RuleCondition.Comparison cmp) {
            BigDecimal value = referenceLookup(metrics, cmp.metric());
            if (value == null) {
                return false;
            }
            int c = value.compareTo(cmp.threshold());
            return switch (cmp.comparator()) {
                case GT -> c > 0;
                case GTE -> c >= 0;
                case LT -> c < 0;
                case LTE -> c <= 0;
                case EQ -> c == 0;
                case NE -> c != 0;
            };
        }
        if (condition instanceof RuleCondition.And and) {
            return and.conditions().stream().allMatch(child -> reference(child, metrics));
        }
        if (condition instanceof RuleCondition.Or or) {
            return or.conditions().stream().anyMatch(child -> reference(child, metrics));
        }
        if (condition instanceof RuleCondition.Not not) {
            return !reference(not.condition(), metrics);
        }
        if (condition instanceof RuleCondition.Constant constant) {
            return constant.value();
        }
        throw new IllegalStateException("unhandled condition: " + condition);
    }

    private static BigDecimal referenceLookup(Map<String, BigDecimal> metrics, String key) {
        for (Map.Entry<String, BigDecimal> e : metrics.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    // --- Generators ---------------------------------------------------------

    /** Metric values at 2-decimal scale, spanning a range that straddles thresholds. */
    @Provide
    Arbitrary<BigDecimal> metricValue() {
        return Arbitraries.longs().between(0L, 30_000L)
                .map(hundredths -> BigDecimal.valueOf(hundredths, 2));
    }

    /**
     * A metric map over an arbitrary subset of {@link #METRIC_KEYS}; an empty map
     * (no metrics) is a valid value, exercising the absent-metric branch.
     */
    @Provide
    Arbitrary<Map<String, BigDecimal>> metricMaps() {
        return Arbitraries.subsetOf(METRIC_KEYS).flatMap(keys -> {
            List<Arbitrary<BigDecimal>> valueArbs = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) {
                valueArbs.add(metricValue());
            }
            if (valueArbs.isEmpty()) {
                return Arbitraries.just(new HashMap<>());
            }
            return Combinators.combine(valueArbs).as(values -> {
                Map<String, BigDecimal> map = new HashMap<>();
                List<String> keyList = new ArrayList<>(keys);
                for (int i = 0; i < keyList.size(); i++) {
                    map.put(keyList.get(i), values.get(i));
                }
                return map;
            });
        });
    }

    /** A single comparison leaf over a (possibly absent) metric key. */
    @Provide
    Arbitrary<RuleCondition> comparisons() {
        Arbitrary<String> metric = Arbitraries.of(METRIC_KEYS);
        Arbitrary<RuleComparator> comparator = Arbitraries.of(RuleComparator.values());
        return Combinators.combine(metric, comparator, metricValue())
                .as(RuleCondition.Comparison::new);
    }

    /**
     * An arbitrary, depth-bounded condition tree mixing comparison leaves,
     * constants, and And/Or/Not combinators (including empty And/Or, which are
     * vacuously true/false respectively).
     */
    @Provide
    Arbitrary<RuleCondition> conditions() {
        Arbitrary<RuleCondition> constants = Arbitraries.of(
                RuleCondition.Constant.TRUE, RuleCondition.Constant.FALSE);
        Arbitrary<RuleCondition> leaves = Arbitraries.oneOf(comparisons(), constants);

        return Arbitraries.recursive(
                () -> leaves,
                this::combine,
                4); // up to 4 levels of nesting
    }

    private Arbitrary<RuleCondition> combine(Arbitrary<RuleCondition> sub) {
        Arbitrary<RuleCondition> and = sub.list().ofMaxSize(3)
                .map(RuleCondition.And::new);
        Arbitrary<RuleCondition> or = sub.list().ofMaxSize(3)
                .map(RuleCondition.Or::new);
        Arbitrary<RuleCondition> not = sub.map(RuleCondition.Not::new);
        return Arbitraries.oneOf(and, or, not, sub);
    }

    /** An arbitrary action with a non-blank type and possibly-empty params. */
    @Provide
    Arbitrary<RuleAction> actions() {
        Arbitrary<String> type = Arbitraries.of(
                "bid_adjustment", "negative_keyword", "pause", "budget_change");
        return type.map(RuleAction::of);
    }
}
