package com.adpilot.modules.finance.service;

import com.adpilot.modules.finance.vo.ReconciliationResult;
import com.adpilot.modules.settlement.entity.SettlementTransactionEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Reconciles platform settlements against internally computed expected amounts
 * and aggregates the fees associated with a settlement (Req 9.1).
 *
 * <p>Reconciliation aggregates the referral fee, fulfillment fee, and
 * advertising spend from the settlement's transactions (Req 9.1.1), compares
 * the platform-reported settlement amount against the internally computed
 * expected amount (Req 9.1.2), and flags the settlement as a discrepancy when
 * the difference exceeds a configured tolerance (Req 9.1.3). The aggregated
 * fees are deducted from gross proceeds to produce profit (Req 9.1.4).</p>
 */
public interface ReconciliationService {

    /**
     * Reconciles the settlement with the given id: aggregates its fees, compares
     * the reported amount against the expected amount, flags discrepancies
     * beyond tolerance, and persists the resulting {@code reconciliation_status}.
     *
     * @param settlementId the settlement to reconcile
     * @return the reconciliation result
     */
    ReconciliationResult reconcile(UUID settlementId);

    /**
     * Pure reconciliation computation over an explicit reported amount and a set
     * of transactions, independent of persistence. Aggregates fees (Req 9.1.1),
     * computes the expected amount and difference (Req 9.1.2), flags a
     * discrepancy when the absolute difference exceeds the tolerance (Req 9.1.3),
     * and computes profit with the aggregated fees deducted (Req 9.1.4).
     *
     * @param settlementId  the settlement id for reference, may be {@code null}
     * @param reportedAmount the platform-reported settlement amount
     * @param transactions  the settlement's transactions
     * @return the reconciliation result
     */
    ReconciliationResult reconcile(UUID settlementId,
                                   BigDecimal reportedAmount,
                                   List<SettlementTransactionEntity> transactions);
}
