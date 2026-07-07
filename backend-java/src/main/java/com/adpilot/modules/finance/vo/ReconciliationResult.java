package com.adpilot.modules.finance.vo;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Outcome of reconciling a settlement (Req 9.1). Carries the aggregated fees
 * broken out by category (Req 9.1.1), the comparison of the platform-reported
 * amount against the internally computed expected amount (Req 9.1.2), whether
 * the difference exceeds the configured tolerance (Req 9.1.3), and the profit
 * with the aggregated fees deducted (Req 9.1.4).
 *
 * @param settlementId    the reconciled settlement, or {@code null} for a pure
 *                        computation that is not tied to a persisted row
 * @param referralFees    aggregated referral fees
 * @param fulfillmentFees aggregated fulfillment fees
 * @param advertisingSpend aggregated advertising spend
 * @param aggregatedFees  the sum of all transaction fee amounts (Req 9.1.1)
 * @param grossProceeds   the sum of all transaction amounts before fees
 * @param reportedAmount  the platform-reported settlement amount
 * @param expectedAmount  the internally computed expected amount
 *                        ({@code grossProceeds - aggregatedFees})
 * @param difference      {@code reportedAmount - expectedAmount}
 * @param tolerance       the configured discrepancy tolerance
 * @param discrepancy     {@code true} iff {@code abs(difference) > tolerance}
 * @param profit          the profit including (reduced by) the aggregated fees
 *                        (Req 9.1.4)
 */
public record ReconciliationResult(UUID settlementId,
                                   BigDecimal referralFees,
                                   BigDecimal fulfillmentFees,
                                   BigDecimal advertisingSpend,
                                   BigDecimal aggregatedFees,
                                   BigDecimal grossProceeds,
                                   BigDecimal reportedAmount,
                                   BigDecimal expectedAmount,
                                   BigDecimal difference,
                                   BigDecimal tolerance,
                                   boolean discrepancy,
                                   BigDecimal profit) {

    /** Settlement status string when the reported amount is within tolerance. */
    public static final String STATUS_MATCHED = "matched";

    /** Settlement status string when the reported amount is out of tolerance. */
    public static final String STATUS_DISCREPANCY = "discrepancy";

    /**
     * The reconciliation status string for persistence (Req 9.1.3):
     * {@link #STATUS_DISCREPANCY} when flagged, {@link #STATUS_MATCHED} otherwise.
     */
    public String status() {
        return discrepancy ? STATUS_DISCREPANCY : STATUS_MATCHED;
    }
}
