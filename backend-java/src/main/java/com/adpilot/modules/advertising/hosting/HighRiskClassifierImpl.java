package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.service.HostingAdjustmentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Default implementation of {@link HighRiskClassifier} (Requirement 7.5).
 *
 * <p>Classification logic is PURE — no I/O, no state — so it can be
 * property-tested in isolation. The three high-risk categories:
 * <ol>
 *   <li><b>Campaign state changes</b> (changeType == "state"): pausing or enabling
 *       a campaign is always high-risk because the impact is immediate and broad.</li>
 *   <li><b>Keyword/negative-keyword additions</b> (adjustmentType == KEYWORD or NEGATIVE):
 *       additions create new entities that cannot be trivially reversed.</li>
 *   <li><b>Large budget reductions</b> (adjustmentType == BUDGET with
 *       budgetDecreaseRatio > maxDailyBudgetDecreaseRatio): a large cut can
 *       immediately throttle campaign delivery.</li>
 * </ol>
 *
 * <p>Validates: Requirements 7.5.</p>
 */
@Slf4j
@Component
public class HighRiskClassifierImpl implements HighRiskClassifier {

    static final String REASON_STATE_CHANGE = "high_risk_state_change";
    static final String REASON_KEYWORD_ADDITION = "high_risk_keyword_addition";
    static final String REASON_NEGATIVE_KEYWORD_ADDITION = "high_risk_negative_keyword_addition";
    static final String REASON_LARGE_BUDGET_DECREASE = "high_risk_large_budget_decrease";
    static final String REASON_NOT_HIGH_RISK = "not_high_risk";

    @Override
    public boolean isHighRisk(CandidateDecisionContext context) {
        if (context == null) {
            return false;
        }

        // 1. Campaign state change (pause/enable) — always high-risk
        if (isCampaignStateChange(context)) {
            return true;
        }

        // 2. Keyword additions — always high-risk (non-reversible)
        if (isKeywordAddition(context)) {
            return true;
        }

        // 3. Negative keyword additions — always high-risk (non-reversible)
        if (isNegativeKeywordAddition(context)) {
            return true;
        }

        // 4. Large budget reductions exceeding the configurable threshold
        if (isLargeBudgetDecrease(context)) {
            return true;
        }

        return false;
    }

    @Override
    public String classificationReason(CandidateDecisionContext context) {
        if (context == null) {
            return REASON_NOT_HIGH_RISK;
        }

        if (isCampaignStateChange(context)) {
            return REASON_STATE_CHANGE;
        }
        if (isKeywordAddition(context)) {
            return REASON_KEYWORD_ADDITION;
        }
        if (isNegativeKeywordAddition(context)) {
            return REASON_NEGATIVE_KEYWORD_ADDITION;
        }
        if (isLargeBudgetDecrease(context)) {
            return REASON_LARGE_BUDGET_DECREASE;
        }
        return REASON_NOT_HIGH_RISK;
    }

    /**
     * Campaign state changes (pause/enable) are high-risk.
     */
    private boolean isCampaignStateChange(CandidateDecisionContext context) {
        return "state".equalsIgnoreCase(context.changeType());
    }

    /**
     * Keyword additions (positive keyword expansion) are high-risk.
     */
    private boolean isKeywordAddition(CandidateDecisionContext context) {
        if (context.adjustmentType() == HostingAdjustmentType.KEYWORD) {
            return true;
        }
        return "keyword".equalsIgnoreCase(context.changeType())
                && context.adjustmentType() != HostingAdjustmentType.BID;
    }

    /**
     * Negative keyword additions are high-risk.
     */
    private boolean isNegativeKeywordAddition(CandidateDecisionContext context) {
        if (context.adjustmentType() == HostingAdjustmentType.NEGATIVE) {
            return true;
        }
        return "negative_keyword".equalsIgnoreCase(context.changeType());
    }

    /**
     * A budget decrease exceeding the configured {@code maxDailyBudgetDecreaseRatio}
     * is classified as a large budget reduction (high-risk).
     *
     * <p>Only applies when:
     * <ul>
     *   <li>The adjustment type is BUDGET.</li>
     *   <li>The budgetDecreaseRatio is positive (it IS a decrease).</li>
     *   <li>The ratio exceeds the threshold.</li>
     * </ul>
     */
    private boolean isLargeBudgetDecrease(CandidateDecisionContext context) {
        if (context.adjustmentType() != HostingAdjustmentType.BUDGET) {
            return false;
        }
        BigDecimal decreaseRatio = context.budgetDecreaseRatio();
        if (decreaseRatio == null || decreaseRatio.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal threshold = context.maxDailyBudgetDecreaseRatio();
        return decreaseRatio.compareTo(threshold) > 0;
    }
}
