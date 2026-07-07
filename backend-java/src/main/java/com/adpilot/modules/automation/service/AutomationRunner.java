package com.adpilot.modules.automation.service;

import com.adpilot.modules.automation.entity.AutomationRuleEntity;
import com.adpilot.modules.automation.vo.AutomationRunSummaryVo;

import java.math.BigDecimal;

/**
 * Evaluates enabled {@link AutomationRuleEntity automation rules} and executes
 * the resulting changes against the live platform (Req 13.2).
 *
 * <p>On each scheduled run (wired in task 26.2), {@link #runEnabledRules()}
 * evaluates every enabled rule against current performance data (Req 13.2.1).
 * For a matching bid-adjustment rule it computes the adjusted bid, clamps it
 * within the rule's {@code [min_bid, max_bid]} bounds (Req 13.2.2, 13.2.5), and
 * submits the change to the live platform through the store's
 * {@link com.adpilot.modules.apisync.connector.PlatformWriteConnector}. For a
 * matching negative-keyword rule it adds the qualifying search term as a
 * negative keyword and submits it (Req 13.2.3). Every executed change is
 * recorded as automated in the audit trail (Req 13.2.4); execution is gated by
 * {@code @RequiresApproval} where an enabled policy threshold is met
 * (Req 13.2.6); and on platform rejection the failure reason is recorded while
 * the internal record is left unchanged (Req 13.2.7).
 */
public interface AutomationRunner {

    /**
     * Evaluate and execute all enabled automation rules across every store
     * (Req 13.2.1). Intended to be invoked by the scheduler.
     *
     * @return an aggregate summary of the run
     */
    AutomationRunSummaryVo runEnabledRules();

    /**
     * Evaluate and execute the enabled automation rules for a single store.
     *
     * @param storeId the store whose rules to evaluate; must not be {@code null}
     * @return an aggregate summary of the run for the store
     */
    AutomationRunSummaryVo runEnabledRules(java.util.UUID storeId);

    /**
     * Execute a single rule. Annotated {@code @RequiresApproval} in the
     * implementation so that, when a governing approval policy's threshold is
     * met, execution is placed in a pending-approval state and no change is
     * submitted to the live platform until the approval completes (Req 13.2.6).
     *
     * <p>Must be invoked through the Spring proxy for approval gating to apply.
     *
     * @param rule the rule to execute
     * @return the per-rule outcome, or {@code null} when gated for approval
     */
    AutomationRunSummaryVo executeRule(AutomationRuleEntity rule);

    /**
     * Clamp a proposed bid value to the rule's configured bounds (Req 13.2.5).
     * Pure function: {@code result = min(max(value, lower), upper)} so the
     * returned value is always within {@code [lower, upper]} when
     * {@code lower <= upper}. A {@code null} bound is treated as unbounded on
     * that side, and a {@code null} value returns {@code null}.
     *
     * @param value the proposed (pre-clamp) bid
     * @param lower the minimum bound, or {@code null} for no lower bound
     * @param upper the maximum bound, or {@code null} for no upper bound
     * @return the clamped bid
     */
    static BigDecimal clampBid(BigDecimal value, BigDecimal lower, BigDecimal upper) {
        if (value == null) {
            return null;
        }
        BigDecimal result = value;
        if (lower != null && result.compareTo(lower) < 0) {
            result = lower;
        }
        if (upper != null && result.compareTo(upper) > 0) {
            result = upper;
        }
        return result;
    }
}
