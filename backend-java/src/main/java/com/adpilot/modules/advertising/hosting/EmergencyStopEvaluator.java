package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.support.SafetyBoundary;

import java.math.BigDecimal;

/**
 * Evaluates the emergency stop condition (Requirement 6.5).
 *
 * <p>The emergency condition is a <b>boolean OR</b> across:
 * <ul>
 *   <li>Daily spend &gt; emergencySpendToBudgetMultiplier × campaign daily budget</li>
 *   <li>ACoS &gt; emergencyAcosToTargetMultiplier × target ACoS</li>
 * </ul>
 *
 * <p>When the condition fires, the internal AI Kill Switch for the campaign takes
 * effect immediately and automatically (no approval needed). Amazon-facing actions
 * follow the campaign's Execution_Mode.</p>
 *
 * <p>Validates: Requirements 6.5, 25.1.</p>
 */
public interface EmergencyStopEvaluator {

    /**
     * Evaluate the emergency stop condition for a campaign.
     *
     * @param dailySpend       the campaign's current daily spend
     * @param dailyBudget      the campaign's daily budget
     * @param actualAcos       the campaign's actual ACoS over the lookback window
     * @param targetAcos       the campaign's target ACoS
     * @param resolvedBoundary the campaign's resolved safety boundary (provides multipliers)
     * @return the evaluation result indicating whether emergency stop triggered and why
     */
    EmergencyStopResult evaluate(BigDecimal dailySpend,
                                 BigDecimal dailyBudget,
                                 BigDecimal actualAcos,
                                 BigDecimal targetAcos,
                                 SafetyBoundary resolvedBoundary);

    /**
     * Result of the emergency stop evaluation.
     *
     * @param triggered       whether the emergency stop condition was met
     * @param spendBreached   whether the spend condition was breached
     * @param acosBreached    whether the ACoS condition was breached
     * @param spendThreshold  the spend threshold that was evaluated (multiplier × budget)
     * @param acosThreshold   the ACoS threshold that was evaluated (multiplier × target)
     * @param reason          description of why the emergency was triggered (null if not triggered)
     */
    record EmergencyStopResult(
            boolean triggered,
            boolean spendBreached,
            boolean acosBreached,
            BigDecimal spendThreshold,
            BigDecimal acosThreshold,
            String reason
    ) {
        /** Convenience factory for no emergency. */
        public static EmergencyStopResult safe(BigDecimal spendThreshold, BigDecimal acosThreshold) {
            return new EmergencyStopResult(false, false, false, spendThreshold, acosThreshold, null);
        }

        /** Convenience factory for triggered emergency. */
        public static EmergencyStopResult triggered(boolean spendBreached, boolean acosBreached,
                                                    BigDecimal spendThreshold, BigDecimal acosThreshold,
                                                    String reason) {
            return new EmergencyStopResult(true, spendBreached, acosBreached,
                    spendThreshold, acosThreshold, reason);
        }
    }
}
