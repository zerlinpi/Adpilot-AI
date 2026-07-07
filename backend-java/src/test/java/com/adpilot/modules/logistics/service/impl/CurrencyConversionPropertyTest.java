package com.adpilot.modules.logistics.service.impl;

import com.adpilot.modules.logistics.entity.HandlingCostEntity;
import com.adpilot.modules.logistics.entity.ShipmentEntity;
import com.adpilot.modules.logistics.mapper.CustomsClearanceMapper;
import com.adpilot.modules.logistics.mapper.HandlingCostMapper;
import com.adpilot.modules.logistics.mapper.ShipmentLegMapper;
import com.adpilot.modules.logistics.mapper.ShipmentMapper;
import com.adpilot.modules.logistics.service.ExchangeRateProvider;
import com.adpilot.modules.logistics.vo.CostChainVo;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link CostChainServiceImpl} currency conversion.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 2: Currency
 * conversion preserves the original and rounds half-up.
 *
 * <p>For any cost component recorded in a currency other than the shipment's
 * reporting currency, the converted amount equals the original amount
 * multiplied by the applicable exchange rate, rounded to 2 decimal places using
 * round-half-up, and the original amount and original currency code are retained
 * alongside the converted component (Req 10.4, 10.5).
 *
 * <p>In this design only handling-cost lines carry their own currency code;
 * shipment-leg costs and the customs duties-and-taxes amount are treated as
 * already expressed in the reporting currency. The cost chain therefore applies
 * conversion exclusively to handling-cost components, so the property is
 * exercised over generated handling-cost lines recorded in a non-reporting
 * currency. Two conversion paths are covered: the per-component
 * {@code exchange_rate}, and the documented store rate resolved via
 * {@link ExchangeRateProvider} when no per-component rate is present.
 *
 * <p>The four mappers and the {@link ExchangeRateProvider} are mocked so the
 * conversion arithmetic is exercised over many inputs without a database: the
 * shipment carries the generated reporting currency, there are no legs and no
 * customs record, and the handling-cost mapper returns the generated lines.
 *
 * Validates: Requirements 10.4, 10.5
 */
class CurrencyConversionPropertyTest {

    /** Monetary scale for converted amounts (Req 10.5). */
    private static final int MONEY_SCALE = 2;

    /** The shipment's reporting currency for every scenario. */
    private static final String REPORTING_CURRENCY = "USD";

    /** Non-reporting currency codes used for generated handling-cost lines. */
    private static final List<String> NON_REPORTING_CURRENCIES =
            List.of("EUR", "GBP", "JPY", "CNY", "CAD", "AUD", "CHF", "SGD", "HKD", "KRW");

    /** A single generated handling-cost line in a non-reporting currency. */
    record HandlingLine(BigDecimal amount, String currency, BigDecimal rate) {
    }

    // Feature: platform-ux-logistics-enhancements, Property 2: Currency conversion preserves the original and rounds half-up
    // Path A: each line carries its own per-component exchange rate (Req 10.4, 10.5).
    @Property(tries = 100)
    void perComponentRateConvertsAndRetainsOriginal(@ForAll("handlingLines") List<HandlingLine> lines) {
        UUID shipmentId = UUID.randomUUID();

        ExchangeRateProvider provider = mock(ExchangeRateProvider.class);
        // Per-component rate is present, so the provider must never be consulted;
        // return empty if it is, which would surface as a pass-through (rate 1)
        // and fail the assertions below.
        when(provider.findRate(any(), any(), any())).thenReturn(Optional.empty());

        List<HandlingCostEntity> entities = new ArrayList<>();
        for (HandlingLine line : lines) {
            entities.add(HandlingCostEntity.builder()
                    .id(UUID.randomUUID())
                    .shipmentId(shipmentId)
                    .amount(line.amount())
                    .currencyCode(line.currency())
                    .description("handling")
                    .exchangeRate(line.rate())   // per-component rate wins
                    .costDate(null)
                    .build());
        }

        CostChainServiceImpl service = newService(shipmentId, entities, provider);
        CostChainVo result = service.compute(shipmentId.toString());

        assertConvertedComponents(result, lines);
    }

    // Feature: platform-ux-logistics-enhancements, Property 2: Currency conversion preserves the original and rounds half-up
    // Path B: lines have no per-component rate; the documented store rate is resolved via the provider (Req 10.4, 10.5).
    @Property(tries = 100)
    void documentedStoreRateConvertsAndRetainsOriginal(@ForAll("handlingLines") List<HandlingLine> lines) {
        UUID shipmentId = UUID.randomUUID();

        // One documented rate per currency (a currency maps to a single rate),
        // so the expected applicable rate is well-defined even when several lines
        // share a currency.
        Map<String, BigDecimal> documentedRates = new HashMap<>();
        for (HandlingLine line : lines) {
            documentedRates.putIfAbsent(line.currency(), line.rate());
        }

        ExchangeRateProvider provider = mock(ExchangeRateProvider.class);
        when(provider.findRate(any(), any(), any())).thenAnswer(invocation -> {
            String base = invocation.getArgument(0);
            return Optional.ofNullable(documentedRates.get(base));
        });

        List<HandlingCostEntity> entities = new ArrayList<>();
        for (HandlingLine line : lines) {
            entities.add(HandlingCostEntity.builder()
                    .id(UUID.randomUUID())
                    .shipmentId(shipmentId)
                    .amount(line.amount())
                    .currencyCode(line.currency())
                    .description("handling")
                    .exchangeRate(null)          // forces the documented-rate path
                    .costDate(LocalDate.of(2024, 1, 1))
                    .build());
        }

        CostChainServiceImpl service = newService(shipmentId, entities, provider);
        CostChainVo result = service.compute(shipmentId.toString());

        // Expected applicable rate is the documented rate for the line's currency.
        List<HandlingLine> expected = new ArrayList<>();
        for (HandlingLine line : lines) {
            expected.add(new HandlingLine(line.amount(), line.currency(), documentedRates.get(line.currency())));
        }
        assertConvertedComponents(result, expected);
    }

    // --- shared assertions ----------------------------------------------------

    /**
     * Each handling-cost component must carry the converted amount
     * (original × applicable rate, 2 dp half-up) and retain the original amount,
     * original currency, and applied rate, in the same order as the input lines.
     */
    private void assertConvertedComponents(CostChainVo result, List<HandlingLine> lines) {
        List<CostChainVo.HandlingCostComponent> components = result.getHandlingCosts();
        assertThat(components).hasSize(lines.size());

        for (int i = 0; i < lines.size(); i++) {
            HandlingLine line = lines.get(i);
            CostChainVo.HandlingCostComponent component = components.get(i);

            BigDecimal expectedConverted = line.amount()
                    .multiply(line.rate())
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

            assertThat(component.amount())
                    .as("converted amount = original × rate rounded half-up to 2 dp")
                    .isEqualByComparingTo(expectedConverted);
            assertThat(component.amount().scale())
                    .as("converted amount is scaled to 2 decimal places")
                    .isEqualTo(MONEY_SCALE);
            assertThat(component.originalAmount())
                    .as("original amount is retained alongside the converted component")
                    .isEqualByComparingTo(line.amount());
            assertThat(component.originalCurrency())
                    .as("original currency code is retained alongside the converted component")
                    .isEqualTo(line.currency());
            assertThat(component.rate())
                    .as("the applied conversion rate is retained")
                    .isEqualByComparingTo(line.rate());
        }
    }

    // --- service wiring -------------------------------------------------------

    /**
     * Build a {@link CostChainServiceImpl} whose shipment exists with the
     * reporting currency, has no legs and no customs record, and whose
     * handling-cost mapper returns the supplied lines.
     */
    private CostChainServiceImpl newService(UUID shipmentId,
                                            List<HandlingCostEntity> handlingCosts,
                                            ExchangeRateProvider provider) {
        ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);
        ShipmentLegMapper shipmentLegMapper = mock(ShipmentLegMapper.class);
        CustomsClearanceMapper customsClearanceMapper = mock(CustomsClearanceMapper.class);
        HandlingCostMapper handlingCostMapper = mock(HandlingCostMapper.class);

        ShipmentEntity shipment = ShipmentEntity.builder()
                .id(shipmentId)
                .storeId(UUID.randomUUID())
                .reportingCurrency(REPORTING_CURRENCY)
                .build();
        when(shipmentMapper.selectById(shipmentId)).thenReturn(shipment);
        when(shipmentLegMapper.selectList(any())).thenReturn(List.of());
        when(customsClearanceMapper.selectOne(any())).thenReturn(null);
        when(handlingCostMapper.selectList(any())).thenReturn(handlingCosts);

        return new CostChainServiceImpl(
                shipmentMapper, shipmentLegMapper, customsClearanceMapper, handlingCostMapper, provider);
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<List<HandlingLine>> handlingLines() {
        // Amount within the documented monetary bound (Req 18: 0.00 .. 999,999,999.99), 2 dp.
        Arbitrary<BigDecimal> amount = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("999999999.99"))
                .ofScale(MONEY_SCALE);
        // A non-reporting currency code (always different from REPORTING_CURRENCY).
        Arbitrary<String> currency = Arbitraries.of(NON_REPORTING_CURRENCIES);
        // A positive exchange rate with up to 8 dp (matching exchange_rate DECIMAL(18,8)).
        Arbitrary<BigDecimal> rate = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00000001"), new BigDecimal("10000"))
                .ofScale(8);

        Arbitrary<HandlingLine> line =
                Combinators.combine(amount, currency, rate).as(HandlingLine::new);
        // At least one line so the property always exercises a real conversion.
        return line.list().ofMinSize(1).ofMaxSize(20);
    }
}
