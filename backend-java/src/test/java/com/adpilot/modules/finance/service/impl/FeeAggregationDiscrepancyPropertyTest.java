package com.adpilot.modules.finance.service.impl;

import com.adpilot.modules.finance.vo.ReconciliationResult;
import com.adpilot.modules.settlement.entity.SettlementTransactionEntity;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the pure reconciliation logic of
 * {@link ReconciliationServiceImpl#reconcile(java.util.UUID, BigDecimal, List)}.
 *
 * Feature: core-platform-completion, Property 19: Fee aggregation, discrepancy
 * flagging, and profit inclusion are exact.
 *
 * <p>For any set of settlement transactions and any reported amount, the
 * aggregated fees equal the sum of every transaction fee amount and each fee
 * category subtotal (referral, fulfillment, advertising) is exact (Req 9.1.1);
 * the expected amount is gross proceeds less aggregated fees (Req 9.1.2); a
 * discrepancy is flagged exactly when the absolute difference between the
 * reported and expected amount exceeds the configured tolerance (Req 9.1.3);
 * and profit equals gross proceeds less the aggregated fees (Req 9.1.4).</p>
 *
 * <p>The computation is pure (no persistence), so the service is constructed
 * with {@code null} mappers and only its tolerance is injected via reflection,
 * mirroring {@link ReconciliationServiceImplTest}.</p>
 *
 * Validates: Requirements 9.1.1, 9.1.2, 9.1.3, 9.1.4
 */
class FeeAggregationDiscrepancyPropertyTest {

    /** Scale used for monetary amounts; mirrors {@link ReconciliationServiceImpl}. */
    private static final int MONEY_SCALE = 4;

    /** Fixed tolerance used for the property; injected via reflection. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.0100");

    /** The fee categories the service aggregates separately (Req 9.1.1). */
    private enum Category { REFERRAL, FULFILLMENT, ADVERTISING, OTHER }

    /** A single transaction's reconciliation-relevant inputs plus its known category. */
    record TxnSpec(BigDecimal amount, String feeType, BigDecimal feeAmount, Category category) {
    }

    /** A set of transactions together with a delta off the expected amount. */
    record Scenario(List<TxnSpec> transactions, BigDecimal reportedDelta) {
    }

    // Feature: core-platform-completion, Property 19: Fee aggregation, discrepancy flagging, and profit inclusion are exact
    @Property(tries = 200)
    void feeAggregationDiscrepancyAndProfitAreExact(@ForAll("scenarios") Scenario scenario) {
        ReconciliationServiceImpl service = new ReconciliationServiceImpl(null, null);
        ReflectionTestUtils.setField(service, "tolerance", TOLERANCE);

        List<SettlementTransactionEntity> txns = new ArrayList<>();
        BigDecimal expectedReferral = BigDecimal.ZERO;
        BigDecimal expectedFulfillment = BigDecimal.ZERO;
        BigDecimal expectedAdvertising = BigDecimal.ZERO;
        BigDecimal expectedAggregated = BigDecimal.ZERO;
        BigDecimal expectedGross = BigDecimal.ZERO;

        for (TxnSpec spec : scenario.transactions()) {
            txns.add(SettlementTransactionEntity.builder()
                    .amount(spec.amount())
                    .feeType(spec.feeType())
                    .feeAmount(spec.feeAmount())
                    .build());

            expectedAggregated = expectedAggregated.add(spec.feeAmount());
            expectedGross = expectedGross.add(spec.amount());
            switch (spec.category()) {
                case REFERRAL -> expectedReferral = expectedReferral.add(spec.feeAmount());
                case FULFILLMENT -> expectedFulfillment = expectedFulfillment.add(spec.feeAmount());
                case ADVERTISING -> expectedAdvertising = expectedAdvertising.add(spec.feeAmount());
                case OTHER -> { /* only in the aggregate total */ }
            }
        }

        BigDecimal expectedAmount = scale(expectedGross.subtract(expectedAggregated));
        // Reported amount is expected +/- a delta that straddles the tolerance,
        // so both the matched and discrepancy branches are exercised.
        BigDecimal reported = scale(expectedAmount.add(scenario.reportedDelta()));

        ReconciliationResult result = service.reconcile(null, reported, txns);

        // Req 9.1.1: aggregated fees == sum of every transaction fee amount.
        assertThat(result.aggregatedFees()).isEqualByComparingTo(scale(expectedAggregated));
        // Req 9.1.1: each category subtotal is exact.
        assertThat(result.referralFees()).isEqualByComparingTo(scale(expectedReferral));
        assertThat(result.fulfillmentFees()).isEqualByComparingTo(scale(expectedFulfillment));
        assertThat(result.advertisingSpend()).isEqualByComparingTo(scale(expectedAdvertising));
        // The three category subtotals never exceed the aggregate total.
        assertThat(result.referralFees().add(result.fulfillmentFees()).add(result.advertisingSpend()))
                .isLessThanOrEqualTo(result.aggregatedFees());

        // Req 9.1.2: expected amount == gross proceeds - aggregated fees.
        assertThat(result.grossProceeds()).isEqualByComparingTo(scale(expectedGross));
        assertThat(result.expectedAmount())
                .isEqualByComparingTo(scale(result.grossProceeds().subtract(result.aggregatedFees())));
        // difference == reported - expected.
        assertThat(result.difference())
                .isEqualByComparingTo(scale(result.reportedAmount().subtract(result.expectedAmount())));

        // Req 9.1.3: discrepancy flagged iff abs(reported - expected) > tolerance.
        boolean expectedDiscrepancy = result.difference().abs().compareTo(TOLERANCE) > 0;
        assertThat(result.discrepancy()).isEqualTo(expectedDiscrepancy);
        assertThat(result.status()).isEqualTo(expectedDiscrepancy
                ? ReconciliationResult.STATUS_DISCREPANCY
                : ReconciliationResult.STATUS_MATCHED);

        // Req 9.1.4: profit == gross proceeds - aggregated fees.
        assertThat(result.profit())
                .isEqualByComparingTo(scale(result.grossProceeds().subtract(result.aggregatedFees())));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<List<TxnSpec>> transactions = txnSpecs().list().ofMinSize(0).ofMaxSize(20);
        return Combinators.combine(transactions, reportedDeltas())
                .as(Scenario::new);
    }

    /**
     * A transaction spec whose fee type maps unambiguously to a known category,
     * pairing each candidate label with the category the service must derive.
     */
    @Provide
    Arbitrary<TxnSpec> txnSpecs() {
        Arbitrary<LabeledFeeType> feeTypes = labeledFeeTypes();
        return Combinators.combine(amounts(), feeTypes, fees())
                .as((amount, labeled, fee) ->
                        new TxnSpec(amount, labeled.label(), fee, labeled.category()));
    }

    /** A fee-type label paired with the category the service classifies it into. */
    private record LabeledFeeType(String label, Category category) {
    }

    /**
     * Candidate fee-type labels covering every category, including mixed casing
     * and platform-specific phrasings, so the keyword categorization is exercised.
     * Each label maps to exactly one category under the service's rules.
     */
    private Arbitrary<LabeledFeeType> labeledFeeTypes() {
        return Arbitraries.of(
                new LabeledFeeType("ReferralFee", Category.REFERRAL),
                new LabeledFeeType("Referral Fee", Category.REFERRAL),
                new LabeledFeeType("referral commission", Category.REFERRAL),
                new LabeledFeeType("FBA Fulfillment Fee", Category.FULFILLMENT),
                new LabeledFeeType("Fulfillment Fee", Category.FULFILLMENT),
                new LabeledFeeType("fulfilment", Category.FULFILLMENT),
                new LabeledFeeType("FBA", Category.FULFILLMENT),
                new LabeledFeeType("Advertising Spend", Category.ADVERTISING),
                new LabeledFeeType("advertisement", Category.ADVERTISING),
                new LabeledFeeType("PPC", Category.ADVERTISING),
                new LabeledFeeType("ad spend", Category.ADVERTISING),
                new LabeledFeeType("Subscription", Category.OTHER),
                new LabeledFeeType("Storage", Category.OTHER),
                new LabeledFeeType("Closing", Category.OTHER),
                new LabeledFeeType(null, Category.OTHER));
    }

    /** Transaction amounts in the range +/-1,000,000.0000 with up to 4 dp. */
    private Arbitrary<BigDecimal> amounts() {
        return Arbitraries.longs().between(-10_000_000_000L, 10_000_000_000L)
                .map(l -> new BigDecimal(l).movePointLeft(MONEY_SCALE));
    }

    /** Non-negative fee amounts up to 1,000,000.0000 with up to 4 dp. */
    private Arbitrary<BigDecimal> fees() {
        return Arbitraries.longs().between(0L, 10_000_000_000L)
                .map(l -> new BigDecimal(l).movePointLeft(MONEY_SCALE));
    }

    /**
     * Deltas off the expected amount, in 4-dp steps straddling the tolerance
     * (which is 0.0100). Values from -0.0150 to 0.0150 in 0.0001 steps guarantee
     * coverage of within-tolerance, at-boundary, and beyond-tolerance cases.
     */
    private Arbitrary<BigDecimal> reportedDeltas() {
        return Arbitraries.longs().between(-150L, 150L)
                .map(l -> new BigDecimal(l).movePointLeft(MONEY_SCALE));
    }
}
