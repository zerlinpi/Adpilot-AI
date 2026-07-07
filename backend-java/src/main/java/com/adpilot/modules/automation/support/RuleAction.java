package com.adpilot.modules.automation.support;

import java.util.Collections;
import java.util.Map;

/**
 * The action a rule template applies to a linked object when its
 * {@link RuleCondition} holds (Req 25.3) — for example a bid adjustment or
 * adding a negative keyword.
 *
 * <p>The action is intentionally opaque to the {@link RuleConditionEvaluator}:
 * the evaluator only decides <em>whether</em> the action applies (exactly when
 * the condition is true), never <em>what</em> the action does. The
 * {@code params} carry the action-type-specific payload (e.g. a bid delta
 * percentage, a keyword to negate).
 *
 * @param type   the action type (e.g. {@code bid_adjustment},
 *               {@code negative_keyword}); must not be blank
 * @param params the action parameters; never {@code null} (an empty map when
 *               none were supplied)
 */
public record RuleAction(String type, Map<String, Object> params) {

    public RuleAction {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("action type must not be blank");
        }
        type = type.trim();
        params = params == null ? Collections.emptyMap() : Map.copyOf(params);
    }

    /** Convenience factory for an action with no parameters. */
    public static RuleAction of(String type) {
        return new RuleAction(type, Collections.emptyMap());
    }
}
