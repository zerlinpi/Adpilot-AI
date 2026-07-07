package com.adpilot.modules.automation.support;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Pure, side-effect-free condition-to-action evaluator for automation rule
 * templates (Req 25.3).
 *
 * <p>This helper embodies the soundness contract validated by Property 5
 * (task 11.6): for a given object's metrics, the template's action is applied
 * <strong>exactly when</strong> the condition evaluates to {@code true}, and
 * <strong>never</strong> when it evaluates to {@code false}. The evaluator
 * therefore exposes two operations:
 * <ul>
 *   <li>{@link #evaluate(RuleCondition, Map)} — the deterministic truth value of
 *       a condition tree against a metric map; and</li>
 *   <li>{@link #resolveAction(RuleCondition, RuleAction, Map)} — the action,
 *       present iff {@link #evaluate} is {@code true}.</li>
 * </ul>
 *
 * <p>Evaluation semantics:
 * <ul>
 *   <li>{@link RuleCondition.Comparison} — looks up the (lower-cased) metric in
 *       the supplied map; when the metric is <em>absent or null</em> the
 *       comparison is {@code false} (an unknown metric cannot satisfy a
 *       constraint). Otherwise the {@link RuleComparator} decides.</li>
 *   <li>{@link RuleCondition.And} — all sub-conditions true ({@code true} for an
 *       empty conjunction).</li>
 *   <li>{@link RuleCondition.Or} — any sub-condition true ({@code false} for an
 *       empty disjunction).</li>
 *   <li>{@link RuleCondition.Not} — negation of its sub-condition.</li>
 *   <li>{@link RuleCondition.Constant} — its fixed truth value.</li>
 * </ul>
 *
 * <p>The class is stateless and holds no Spring or JSON dependencies so it can
 * be unit- and property-tested in isolation.
 */
public final class RuleConditionEvaluator {

    private RuleConditionEvaluator() {
    }

    /**
     * Evaluate a condition tree against an object's metric values.
     *
     * @param condition the condition tree; must not be {@code null}
     * @param metrics   the object's metric values keyed by metric name; must not
     *                  be {@code null} (use an empty map for "no metrics")
     * @return {@code true} iff the condition holds for {@code metrics}
     * @throws NullPointerException if {@code condition} or {@code metrics} is null
     */
    public static boolean evaluate(RuleCondition condition, Map<String, BigDecimal> metrics) {
        if (condition == null) {
            throw new NullPointerException("condition must not be null");
        }
        if (metrics == null) {
            throw new NullPointerException("metrics must not be null");
        }
        if (condition instanceof RuleCondition.Comparison cmp) {
            BigDecimal value = lookup(metrics, cmp.metric());
            if (value == null) {
                return false;
            }
            return cmp.comparator().test(value, cmp.threshold());
        }
        if (condition instanceof RuleCondition.And and) {
            for (RuleCondition child : and.conditions()) {
                if (!evaluate(child, metrics)) {
                    return false;
                }
            }
            return true;
        }
        if (condition instanceof RuleCondition.Or or) {
            for (RuleCondition child : or.conditions()) {
                if (evaluate(child, metrics)) {
                    return true;
                }
            }
            return false;
        }
        if (condition instanceof RuleCondition.Not not) {
            return !evaluate(not.condition(), metrics);
        }
        if (condition instanceof RuleCondition.Constant constant) {
            return constant.value();
        }
        // Sealed hierarchy: unreachable.
        throw new IllegalStateException("unhandled condition type: " + condition.getClass());
    }

    /**
     * Resolve the action to apply for an object's metrics: the {@code action}
     * wrapped in a present {@link Optional} exactly when the {@code condition}
     * holds, and {@link Optional#empty()} otherwise.
     *
     * <p>This is the single decision point for condition-to-action soundness:
     * callers apply the action if and only if the returned optional is present.
     *
     * @param condition the condition tree; must not be {@code null}
     * @param action    the action to apply when the condition holds; must not be
     *                  {@code null}
     * @param metrics   the object's metric values; must not be {@code null}
     * @return the action when the condition is true, otherwise empty
     */
    public static Optional<RuleAction> resolveAction(RuleCondition condition,
                                                     RuleAction action,
                                                     Map<String, BigDecimal> metrics) {
        if (action == null) {
            throw new NullPointerException("action must not be null");
        }
        return evaluate(condition, metrics) ? Optional.of(action) : Optional.empty();
    }

    /**
     * Look up a metric case-insensitively. The condition's metric key is already
     * normalized to lower-case (see {@link RuleCondition.Comparison}); this
     * tolerates metric maps whose keys differ only in case.
     */
    private static BigDecimal lookup(Map<String, BigDecimal> metrics, String key) {
        BigDecimal direct = metrics.get(key);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, BigDecimal> e : metrics.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }
}
