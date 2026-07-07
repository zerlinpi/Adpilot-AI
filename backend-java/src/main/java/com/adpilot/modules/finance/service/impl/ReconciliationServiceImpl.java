package com.adpilot.modules.finance.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.finance.service.ReconciliationService;
import com.adpilot.modules.finance.vo.ReconciliationResult;
import com.adpilot.modules.settlement.entity.SettlementEntity;
import com.adpilot.modules.settlement.entity.SettlementTransactionEntity;
import com.adpilot.modules.settlement.mapper.SettlementMapper;
import com.adpilot.modules.settlement.mapper.SettlementTransactionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Default {@link ReconciliationService}. Aggregates settlement fees, compares
 * the platform-reported amount against the internally computed expected amount,
 * flags discrepancies beyond a configured tolerance, and deducts the aggregated
 * fees from gross proceeds to produce profit (Req 9.1).
 *
 * <p>The fee aggregation, comparison, and profit math are implemented as a pure
 * computation in {@link #reconcile(UUID, BigDecimal, List)} so they can be
 * exercised independently of persistence.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationServiceImpl implements ReconciliationService {

    /** Scale used for monetary amounts; matches the 4-dp monetary columns. */
    private static final int MONEY_SCALE = 4;

    private final SettlementMapper settlementMapper;
    private final SettlementTransactionMapper settlementTransactionMapper;

    /**
     * Absolute tolerance (in the settlement's currency) within which a reported
     * amount is considered to match the expected amount (Req 9.1.3). A reported
     * amount differing by more than this is flagged as a discrepancy.
     */
    @Value("${adpilot.finance.reconciliation.tolerance:0.01}")
    private BigDecimal tolerance;

    @Override
    @Transactional
    public ReconciliationResult reconcile(UUID settlementId) {
        if (settlementId == null) {
            throw new IllegalArgumentException("settlementId is required");
        }

        SettlementEntity settlement = settlementMapper.selectById(settlementId);
        if (settlement == null) {
            throw new BusinessException("SETTLEMENT_NOT_FOUND", "Settlement not found: " + settlementId);
        }

        List<SettlementTransactionEntity> transactions = settlementTransactionMapper.selectList(
                new LambdaQueryWrapper<SettlementTransactionEntity>()
                        .eq(SettlementTransactionEntity::getSettlementId, settlementId));

        ReconciliationResult result = reconcile(settlementId, settlement.getTotalAmount(), transactions);

        // Req 9.1.3: persist the reconciliation outcome on the settlement.
        settlement.setReconciliationStatus(result.status());
        settlementMapper.updateById(settlement);

        log.info("Reconciled settlement {}: aggregatedFees={}, expected={}, reported={}, status={}",
                settlementId, result.aggregatedFees(), result.expectedAmount(),
                result.reportedAmount(), result.status());

        return result;
    }

    @Override
    public ReconciliationResult reconcile(UUID settlementId,
                                          BigDecimal reportedAmount,
                                          List<SettlementTransactionEntity> transactions) {
        List<SettlementTransactionEntity> txns = transactions != null ? transactions : List.of();

        // Req 9.1.1: aggregate the referral, fulfillment, and advertising fees,
        // plus the overall fee total (the sum of every transaction fee amount).
        BigDecimal referralFees = BigDecimal.ZERO;
        BigDecimal fulfillmentFees = BigDecimal.ZERO;
        BigDecimal advertisingSpend = BigDecimal.ZERO;
        BigDecimal aggregatedFees = BigDecimal.ZERO;
        BigDecimal grossProceeds = BigDecimal.ZERO;

        for (SettlementTransactionEntity txn : txns) {
            BigDecimal fee = nonNull(txn.getFeeAmount());
            BigDecimal amount = nonNull(txn.getAmount());

            aggregatedFees = aggregatedFees.add(fee);
            grossProceeds = grossProceeds.add(amount);

            switch (categorize(txn.getFeeType())) {
                case REFERRAL -> referralFees = referralFees.add(fee);
                case FULFILLMENT -> fulfillmentFees = fulfillmentFees.add(fee);
                case ADVERTISING -> advertisingSpend = advertisingSpend.add(fee);
                case OTHER -> { /* counted only in the aggregate total */ }
            }
        }

        aggregatedFees = scale(aggregatedFees);
        grossProceeds = scale(grossProceeds);
        referralFees = scale(referralFees);
        fulfillmentFees = scale(fulfillmentFees);
        advertisingSpend = scale(advertisingSpend);

        // Req 9.1.2: the internally computed expected amount is gross proceeds
        // less the aggregated fees; compare it to the platform-reported amount.
        BigDecimal expectedAmount = scale(grossProceeds.subtract(aggregatedFees));
        BigDecimal reported = scale(nonNull(reportedAmount));
        BigDecimal difference = scale(reported.subtract(expectedAmount));

        // Req 9.1.3: flag a discrepancy when the absolute difference exceeds the
        // configured tolerance.
        BigDecimal tol = scale(nonNull(tolerance));
        boolean discrepancy = difference.abs().compareTo(tol) > 0;

        // Req 9.1.4: profit includes (is reduced by) the aggregated fees.
        BigDecimal profit = scale(grossProceeds.subtract(aggregatedFees));

        return new ReconciliationResult(settlementId, referralFees, fulfillmentFees,
                advertisingSpend, aggregatedFees, grossProceeds, reported, expectedAmount,
                difference, tol, discrepancy, profit);
    }

    private enum FeeCategory { REFERRAL, FULFILLMENT, ADVERTISING, OTHER }

    /**
     * Classifies a transaction fee type into one of the aggregated categories
     * named in Req 9.1.1. Matching is case-insensitive and keyword-based so it
     * tolerates platform-specific labels (for example "FBA Fulfillment Fee").
     */
    private FeeCategory categorize(String feeType) {
        if (feeType == null) {
            return FeeCategory.OTHER;
        }
        String type = feeType.toLowerCase(Locale.ROOT);
        if (type.contains("referral")) {
            return FeeCategory.REFERRAL;
        }
        if (type.contains("fulfillment") || type.contains("fulfilment") || type.contains("fba")) {
            return FeeCategory.FULFILLMENT;
        }
        if (type.contains("advertis") || type.contains("ad spend") || type.contains("ppc")) {
            return FeeCategory.ADVERTISING;
        }
        return FeeCategory.OTHER;
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
