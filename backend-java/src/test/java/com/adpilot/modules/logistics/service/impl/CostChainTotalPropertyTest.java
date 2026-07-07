package com.adpilot.modules.logistics.service.impl;

import com.adpilot.modules.logistics.entity.CustomsClearanceEntity;
import com.adpilot.modules.logistics.entity.HandlingCostEntity;
import com.adpilot.modules.logistics.entity.ShipmentEntity;
import com.adpilot.modules.logistics.entity.ShipmentLegEntity;
import com.adpilot.modules.logistics.mapper.CustomsClearanceMapper;
import com.adpilot.modules.logistics.mapper.HandlingCostMapper;
import com.adpilot.modules.logistics.mapper.ShipmentLegMapper;
import com.adpilot.modules.logistics.mapper.ShipmentMapper;
import com.adpilot.modules.logistics.service.ExchangeRateProvider;
import com.adpilot.modules.logistics.vo.CostChainVo;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the cost-chain aggregation in
 * {@link CostChainServiceImpl#compute(String)}.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 1: Cost chain total
 * equals the sum of its components.
 *
 * <p>For any shipment with any set of leg costs, a customs duties-and-taxes
 * amount, and any set of handling-cost lines — all expressed in (or already
 * converted to) the shipment's reporting currency — the computed
 * {@code totalLandedCost} equals the arithmetic sum of those components, where a
 * component with no recorded amount contributes zero (Req 10.1, 10.2, 10.6,
 * 18.5).
 *
 * <p>There is no live database in unit tests, so the four mappers are mocked to
 * return the generated leg / customs / handling data. To isolate this property
 * to pure aggregation (independent of currency conversion, which is exercised by
 * a separate property), every handling-cost line is recorded in the reporting
 * currency, so no conversion is applied and each component contributes its own
 * recorded amount. Leg costs and customs duties-and-taxes carry no currency and
 * are treated as already in the reporting currency by the service.
 *
 * <p>Generated amounts are 2-decimal-place {@code BigDecimal}s (whole numbers of
 * cents), matching the {@code scale(2)} columns, so the independent oracle's
 * arithmetic sum is exact and the comparison is by numeric value
 * ({@code isEqualByComparingTo}). Some generated amounts are {@code null} to
 * exercise the "no recorded amount contributes zero" rule (Req 10.6).
 *
 * Validates: Requirements 10.1, 10.2, 10.6, 18.5
 */
class CostChainTotalPropertyTest {

    private static final int MONEY_SCALE = 2;
    private static final String REPORTING_CURRENCY = "USD";

    // Feature: platform-ux-logistics-enhancements, Property 1: Cost chain total equals the sum of its components
    @Property(tries = 100)
    void totalEqualsSumOfComponents(
            @ForAll("amountsWithNulls") List<BigDecimal> legCosts,
            @ForAll("nullableAmount") BigDecimal customsDutiesTaxes,
            @ForAll("amountsWithNulls") List<BigDecimal> handlingAmounts) {

        UUID shipmentUuid = UUID.randomUUID();
        String shipmentId = shipmentUuid.toString();

        ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);
        ShipmentLegMapper shipmentLegMapper = mock(ShipmentLegMapper.class);
        CustomsClearanceMapper customsClearanceMapper = mock(CustomsClearanceMapper.class);
        HandlingCostMapper handlingCostMapper = mock(HandlingCostMapper.class);
        ExchangeRateProvider exchangeRateProvider = mock(ExchangeRateProvider.class);

        // Shipment with a reporting currency so handling lines need no conversion.
        ShipmentEntity shipment = ShipmentEntity.builder()
                .id(shipmentUuid)
                .reportingCurrency(REPORTING_CURRENCY)
                .build();
        when(shipmentMapper.selectById(shipmentUuid)).thenReturn(shipment);

        // --- Shipment legs (a missing leg_cost contributes zero) ---
        List<ShipmentLegEntity> legs = new ArrayList<>();
        int sequenceNo = 0;
        for (BigDecimal cost : legCosts) {
            legs.add(ShipmentLegEntity.builder()
                    .id(UUID.randomUUID())
                    .shipmentId(shipmentUuid)
                    .legType("first_leg")
                    .sequenceNo(sequenceNo++)
                    .legCost(cost)
                    .build());
        }
        when(shipmentLegMapper.selectList(any())).thenReturn(legs);

        // --- Customs duties & taxes (null generated value => no customs record) ---
        if (customsDutiesTaxes == null) {
            when(customsClearanceMapper.selectOne(any())).thenReturn(null);
        } else {
            CustomsClearanceEntity customs = CustomsClearanceEntity.builder()
                    .id(UUID.randomUUID())
                    .shipmentId(shipmentUuid)
                    .dutiesTaxes(customsDutiesTaxes)
                    .build();
            when(customsClearanceMapper.selectOne(any())).thenReturn(customs);
        }

        // --- Handling-cost lines in the reporting currency (no conversion) ---
        List<HandlingCostEntity> handling = new ArrayList<>();
        for (BigDecimal amount : handlingAmounts) {
            handling.add(HandlingCostEntity.builder()
                    .id(UUID.randomUUID())
                    .shipmentId(shipmentUuid)
                    .amount(amount)
                    .currencyCode(REPORTING_CURRENCY)
                    .description("handling")
                    .build());
        }
        when(handlingCostMapper.selectList(any())).thenReturn(handling);

        CostChainServiceImpl service = new CostChainServiceImpl(
                shipmentMapper, shipmentLegMapper, customsClearanceMapper,
                handlingCostMapper, exchangeRateProvider);

        // Independent oracle: arithmetic sum with missing amounts treated as zero.
        BigDecimal expected = BigDecimal.ZERO;
        for (BigDecimal cost : legCosts) {
            expected = expected.add(nvl(cost));
        }
        expected = expected.add(nvl(customsDutiesTaxes));
        for (BigDecimal amount : handlingAmounts) {
            expected = expected.add(nvl(amount));
        }
        expected = expected.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        CostChainVo result = service.compute(shipmentId);

        assertThat(result.getTotalLandedCost())
                .as("total landed cost must equal the arithmetic sum of all components")
                .isEqualByComparingTo(expected);
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    // --- generators -----------------------------------------------------------

    /** A non-negative monetary amount with 2 decimal places (whole cents). */
    private Arbitrary<BigDecimal> amount() {
        // 0.00 .. 1,000,000.00 expressed as whole numbers of cents.
        return Arbitraries.longs().between(0L, 100_000_000L)
                .map(cents -> BigDecimal.valueOf(cents, MONEY_SCALE));
    }

    /** A monetary amount that is sometimes {@code null} (no recorded amount). */
    @Provide
    Arbitrary<BigDecimal> nullableAmount() {
        return amount().injectNull(0.2);
    }

    /** 0..20 component amounts, any of which may be {@code null} (Req 10.6). */
    @Provide
    Arbitrary<List<BigDecimal>> amountsWithNulls() {
        return amount().injectNull(0.2).list().ofMinSize(0).ofMaxSize(20);
    }
}
