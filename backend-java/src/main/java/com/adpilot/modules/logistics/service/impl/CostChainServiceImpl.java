package com.adpilot.modules.logistics.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.logistics.entity.CustomsClearanceEntity;
import com.adpilot.modules.logistics.entity.HandlingCostEntity;
import com.adpilot.modules.logistics.entity.ShipmentEntity;
import com.adpilot.modules.logistics.entity.ShipmentLegEntity;
import com.adpilot.modules.logistics.mapper.CustomsClearanceMapper;
import com.adpilot.modules.logistics.mapper.HandlingCostMapper;
import com.adpilot.modules.logistics.mapper.ShipmentLegMapper;
import com.adpilot.modules.logistics.mapper.ShipmentMapper;
import com.adpilot.modules.logistics.service.CostChainService;
import com.adpilot.modules.logistics.service.ExchangeRateProvider;
import com.adpilot.modules.logistics.vo.CostChainVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Default {@link CostChainService} implementation (Req 10).
 *
 * <p>Aggregation: {@code totalLandedCost} is the arithmetic sum, in the
 * shipment's reporting currency, of all shipment-leg costs, the customs
 * duties-and-taxes amount, and all handling-cost lines (Req 10.1, 10.2, 18.5).
 * A component with no recorded amount contributes zero (Req 10.6).</p>
 *
 * <p>Currency conversion: shipment legs ({@code leg_cost}) and customs
 * duties-and-taxes carry no currency of their own and are treated as already
 * expressed in the reporting currency. Handling-cost lines carry a currency
 * code; when it differs from the reporting currency the line is converted using
 * its per-component {@code exchange_rate} when present, otherwise the documented
 * store reporting-currency rate effective on its {@code cost_date}
 * ({@link ExchangeRateProvider}). Converted amounts are rounded to 2 decimal
 * places half-up and the original amount, original currency, and applied rate
 * are retained alongside the converted component (Req 10.4, 10.5).</p>
 *
 * <p><b>Assumption (documented):</b> when no per-component rate is present and
 * the documented store rate source has no rate for the currency pair on/before
 * the cost date, the conversion falls back to a pass-through rate of {@code 1}
 * (the original amount is carried through unchanged). This avoids inventing an
 * external FX integration while keeping the total well-defined.</p>
 *
 * <p>The cost chain is computed at the shipment level only; SKU-level allocation
 * is out of scope (Req 10.7). An unknown shipment is rejected with a not-found
 * error and no cost data (Req 10.8); store-scope enforcement is applied by the
 * controller.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostChainServiceImpl implements CostChainService {

    /** Monetary scale for converted amounts and the aggregated total (Req 10.5). */
    private static final int MONEY_SCALE = 2;

    /** Pass-through rate used when no documented rate is available. */
    private static final BigDecimal PASS_THROUGH_RATE = BigDecimal.ONE;

    private final ShipmentMapper shipmentMapper;
    private final ShipmentLegMapper shipmentLegMapper;
    private final CustomsClearanceMapper customsClearanceMapper;
    private final HandlingCostMapper handlingCostMapper;
    private final ExchangeRateProvider exchangeRateProvider;

    @Override
    public CostChainVo compute(String shipmentId) {
        UUID shipmentUuid = parseShipmentId(shipmentId);
        ShipmentEntity shipment = shipmentMapper.selectById(shipmentUuid);
        if (shipment == null) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + shipmentId);
        }

        String reportingCurrency = shipment.getReportingCurrency();

        BigDecimal total = BigDecimal.ZERO;

        // --- Shipment legs (no per-leg currency; treated as reporting currency) ---
        List<CostChainVo.LegCostComponent> legComponents = new ArrayList<>();
        for (ShipmentLegEntity leg : listLegs(shipmentUuid)) {
            BigDecimal amount = nvl(leg.getLegCost());
            legComponents.add(new CostChainVo.LegCostComponent(
                    leg.getId() != null ? leg.getId().toString() : null,
                    amount,
                    null,
                    null,
                    null));
            total = total.add(amount);
        }

        // --- Customs duties & taxes (single amount; no currency of its own) ---
        BigDecimal customsDutiesTaxes = BigDecimal.ZERO;
        CustomsClearanceEntity customs = findCustoms(shipmentUuid);
        if (customs != null) {
            customsDutiesTaxes = nvl(customs.getDutiesTaxes());
        }
        total = total.add(customsDutiesTaxes);

        // --- Handling-cost lines (carry currency + optional conversion provenance) ---
        List<CostChainVo.HandlingCostComponent> handlingComponents = new ArrayList<>();
        for (HandlingCostEntity handling : listHandlingCosts(shipmentUuid)) {
            CostChainVo.HandlingCostComponent component = convertHandling(handling, reportingCurrency);
            handlingComponents.add(component);
            total = total.add(component.amount());
        }

        BigDecimal totalLandedCost = total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        return CostChainVo.builder()
                .shipmentId(shipmentUuid.toString())
                .reportingCurrency(reportingCurrency)
                .legCosts(legComponents)
                .customsDutiesTaxes(customsDutiesTaxes)
                .handlingCosts(handlingComponents)
                .totalLandedCost(totalLandedCost)
                .build();
    }

    /**
     * Convert a handling-cost line into the reporting currency, retaining
     * original amount/currency/rate when a conversion is applied (Req 10.4,
     * 10.5). A missing amount is treated as zero (Req 10.6).
     */
    private CostChainVo.HandlingCostComponent convertHandling(HandlingCostEntity handling, String reportingCurrency) {
        String id = handling.getId() != null ? handling.getId().toString() : null;
        BigDecimal originalAmount = nvl(handling.getAmount());
        String currencyCode = handling.getCurrencyCode();

        // No conversion needed when the line is already in the reporting currency
        // (or when no reporting currency is configured / no currency recorded).
        if (isBlank(reportingCurrency) || isBlank(currencyCode)
                || currencyCode.equalsIgnoreCase(reportingCurrency)) {
            return new CostChainVo.HandlingCostComponent(
                    id,
                    originalAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                    null,
                    null,
                    null);
        }

        // Per-component rate wins; otherwise the documented store rate at cost date;
        // otherwise a pass-through rate of 1 (documented assumption).
        BigDecimal rate = handling.getExchangeRate();
        if (rate == null) {
            rate = exchangeRateProvider
                    .findRate(currencyCode, reportingCurrency, handling.getCostDate())
                    .orElse(PASS_THROUGH_RATE);
        }

        BigDecimal converted = originalAmount.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        return new CostChainVo.HandlingCostComponent(
                id,
                converted,
                originalAmount,
                currencyCode,
                rate);
    }

    private List<ShipmentLegEntity> listLegs(UUID shipmentUuid) {
        LambdaQueryWrapper<ShipmentLegEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShipmentLegEntity::getShipmentId, shipmentUuid);
        wrapper.orderByAsc(ShipmentLegEntity::getSequenceNo);
        return shipmentLegMapper.selectList(wrapper);
    }

    private CustomsClearanceEntity findCustoms(UUID shipmentUuid) {
        LambdaQueryWrapper<CustomsClearanceEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CustomsClearanceEntity::getShipmentId, shipmentUuid);
        wrapper.last("LIMIT 1");
        return customsClearanceMapper.selectOne(wrapper);
    }

    private List<HandlingCostEntity> listHandlingCosts(UUID shipmentUuid) {
        LambdaQueryWrapper<HandlingCostEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(HandlingCostEntity::getShipmentId, shipmentUuid);
        wrapper.orderByAsc(HandlingCostEntity::getCreatedAt);
        return handlingCostMapper.selectList(wrapper);
    }

    private UUID parseShipmentId(String shipmentId) {
        if (isBlank(shipmentId)) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + shipmentId);
        }
        try {
            return UUID.fromString(shipmentId);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + shipmentId);
        }
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
