package com.adpilot.modules.finance.service.impl;

import com.adpilot.modules.finance.vo.ReconciliationResult;
import com.adpilot.modules.settlement.entity.SettlementTransactionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure reconciliation logic of {@link ReconciliationServiceImpl}
 * covering fee aggregation (Req 9.1.1), reported-vs-expected comparison
 * (Req 9.1.2), discrepancy flagging beyond tolerance (Req 9.1.3), and profit
 * inclusion of aggregated fees (Req 9.1.4).
 */
class ReconciliationServiceImplTest {

    private ReconciliationServiceImpl newService(String tolerance) {
        ReconciliationServiceImpl service = new ReconciliationServiceImpl(null, null);
        ReflectionTestUtils.setField(service, "tolerance", new BigDecimal(tolerance));
        return service;
    }

    private SettlementTransactionEntity txn(String amount, String feeType, String feeAmount) {
        return SettlementTransactionEntity.builder()
                .amount(new BigDecimal(amount))
                .feeType(feeType)
                .feeAmount(new BigDecimal(feeAmount))
                .build();
    }

    @Test
    void aggregatesFeesByCategoryAndTotal() {
        ReconciliationServiceImpl service = newService("0.01");
        List<SettlementTransactionEntity> txns = List.of(
                txn("100.00", "ReferralFee", "15.00"),
                txn("0.00", "FBA Fulfillment Fee", "5.00"),
                txn("0.00", "Advertising Spend", "8.00"),
                txn("0.00", "Subscription", "2.00")
        );

        // reported equals expected (100 gross - 30 fees = 70)
        ReconciliationResult result = service.reconcile(null, new BigDecimal("70.00"), txns);

        assertThat(result.referralFees()).isEqualByComparingTo("15.00");
        assertThat(result.fulfillmentFees()).isEqualByComparingTo("5.00");
        assertThat(result.advertisingSpend()).isEqualByComparingTo("8.00");
        // Req 9.1.1: aggregated fees == sum of every transaction fee amount.
        assertThat(result.aggregatedFees()).isEqualByComparingTo("30.00");
        assertThat(result.grossProceeds()).isEqualByComparingTo("100.00");
    }

    @Test
    void profitIsReducedByExactlyTheAggregatedFees() {
        ReconciliationServiceImpl service = newService("0.01");
        List<SettlementTransactionEntity> txns = List.of(
                txn("200.00", "ReferralFee", "20.00"),
                txn("0.00", "FBAFee", "10.00")
        );

        ReconciliationResult result = service.reconcile(null, new BigDecimal("170.00"), txns);

        // Req 9.1.4: profit == gross proceeds - aggregated fees.
        assertThat(result.profit())
                .isEqualByComparingTo(result.grossProceeds().subtract(result.aggregatedFees()));
        assertThat(result.profit()).isEqualByComparingTo("170.00");
    }

    @Test
    void matchedWhenWithinTolerance() {
        ReconciliationServiceImpl service = newService("0.05");
        List<SettlementTransactionEntity> txns = List.of(
                txn("100.00", "ReferralFee", "10.00")
        );

        // expected = 90.00, reported off by 0.04 (<= tolerance 0.05)
        ReconciliationResult result = service.reconcile(null, new BigDecimal("90.04"), txns);

        assertThat(result.expectedAmount()).isEqualByComparingTo("90.00");
        assertThat(result.discrepancy()).isFalse();
        assertThat(result.status()).isEqualTo(ReconciliationResult.STATUS_MATCHED);
    }

    @Test
    void discrepancyWhenBeyondTolerance() {
        ReconciliationServiceImpl service = newService("0.05");
        List<SettlementTransactionEntity> txns = List.of(
                txn("100.00", "ReferralFee", "10.00")
        );

        // expected = 90.00, reported off by 0.06 (> tolerance 0.05)
        ReconciliationResult result = service.reconcile(null, new BigDecimal("90.06"), txns);

        // Req 9.1.3: flagged as a discrepancy when difference exceeds tolerance.
        assertThat(result.discrepancy()).isTrue();
        assertThat(result.status()).isEqualTo(ReconciliationResult.STATUS_DISCREPANCY);
    }

    @Test
    void handlesEmptyTransactions() {
        ReconciliationServiceImpl service = newService("0.01");

        ReconciliationResult result = service.reconcile(null, BigDecimal.ZERO, List.of());

        assertThat(result.aggregatedFees()).isEqualByComparingTo("0.00");
        assertThat(result.expectedAmount()).isEqualByComparingTo("0.00");
        assertThat(result.discrepancy()).isFalse();
    }
}
