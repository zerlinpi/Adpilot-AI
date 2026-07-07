package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Default implementation of {@link EmergencyStopEvaluator} (Requirement 6.5).
 *
 * <p>Evaluates the emergency stop condition as a <b>boolean OR</b>:
 * <ul>
 *   <li>Daily spend &gt; emergencySpendToBudgetMultiplier × campaign daily budget</li>
 *   <li>ACoS &gt; emergencyAcosToTargetMultiplier × target ACoS</li>
 * </ul>
 *
 * <p>When either condition fires, the internal AI Kill Switch for the campaign
 * takes effect immediately (no approval needed). The implementation is a pure
 * evaluator — it does not itself activate the kill switch or create Operations;
 * that is the responsibility of the calling orchestration layer.</p>
 *
 * <p>Validates: Requirements 6.5, 25.1.</p>
 */
@Service
public class EmergencyStopEvaluatorImpl implements EmergencyStopEvaluator {

    private static final Logger log = LoggerFactory.getLogger(EmergencyStopEvaluatorImpl.class);
    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);

    /** Default spend-to-budget multiplier if not configured in the boundary. */
    private static final BigDecimal DEFAULT_SPEND_MULTIPLIER = new BigDecimal("2.0");

    /** Default ACoS-to-target multiplier if not configured in the boundary. */
    private static final BigDecimal DEFAULT_ACOS_MULTIPLIER = new BigDecimal("3.0");

    @Override
    public EmergencyStopResult evaluate(BigDecimal dailySpend,
                                        BigDecimal dailyBudget,
                                        BigDecimal actualAcos,
                                        BigDecimal targetAcos,
                                        SafetyBoundary resolvedBoundary) {
        // Resolve emergency multipliers from the safety boundary
        BigDecimal spendMultiplier = resolvedBoundary
                .get(SafetyBoundaryLimit.EMERGENCY_SPEND_TO_BUDGET_MULTIPLIER)
                .orElse(DEFAULT_SPEND_MULTIPLIER);

        BigDecimal acosMultiplier = resolvedBoundary
                .get(SafetyBoundaryLimit.EMERGENCY_ACOS_TO_TARGET_MULTIPLIER)
                .orElse(DEFAULT_ACOS_MULTIPLIER);

        // Compute thresholds
        BigDecimal spendThreshold = (dailyBudget != null)
                ? spendMultiplier.multiply(dailyBudget, MC)
                : null;

        BigDecimal acosThreshold = (targetAcos != null && targetAcos.compareTo(BigDecimal.ZERO) > 0)
                ? acosMultiplier.multiply(targetAcos, MC)
                : null;

        // Evaluate spend condition: dailySpend > multiplier × budget
        boolean spendBreached = false;
        if (dailySpend != null && spendThreshold != null) {
            spendBreached = dailySpend.compareTo(spendThreshold) > 0;
        }

        // Evaluate ACoS condition: actualAcos > multiplier × targetAcos
        boolean acosBreached = false;
        if (actualAcos != null && acosThreshold != null) {
            acosBreached = actualAcos.compareTo(acosThreshold) > 0;
        }

        // Boolean OR: either condition triggers the emergency stop
        boolean triggered = spendBreached || acosBreached;

        if (triggered) {
            String reason = buildReason(spendBreached, acosBreached,
                    dailySpend, spendThreshold, actualAcos, acosThreshold);
            log.warn("Emergency stop triggered: {}", reason);
            return EmergencyStopResult.triggered(spendBreached, acosBreached,
                    spendThreshold, acosThreshold, reason);
        }

        return EmergencyStopResult.safe(spendThreshold, acosThreshold);
    }

    private String buildReason(boolean spendBreached, boolean acosBreached,
                               BigDecimal dailySpend, BigDecimal spendThreshold,
                               BigDecimal actualAcos, BigDecimal acosThreshold) {
        StringBuilder sb = new StringBuilder("EMERGENCY_STOP:");
        if (spendBreached) {
            sb.append(" spend(").append(dailySpend != null ? dailySpend.toPlainString() : "null")
                    .append(")>threshold(").append(spendThreshold != null ? spendThreshold.toPlainString() : "null")
                    .append(")");
        }
        if (acosBreached) {
            if (spendBreached) sb.append(" AND");
            sb.append(" acos(").append(actualAcos != null ? actualAcos.toPlainString() : "null")
                    .append(")>threshold(").append(acosThreshold != null ? acosThreshold.toPlainString() : "null")
                    .append(")");
        }
        return sb.toString();
    }
}
