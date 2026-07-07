package com.adpilot.modules.dashboard.service.impl;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.dashboard.vo.AggregatedMetricsVo;
import com.adpilot.modules.dashboard.vo.DateRange;
import com.adpilot.modules.dashboard.vo.StoreMetricsVo;
import com.adpilot.modules.finance.service.CurrencyService;
import com.adpilot.modules.finance.vo.ConvertedAmount;
import com.adpilot.modules.order.entity.OrderEntity;
import com.adpilot.modules.order.mapper.OrderMapper;
import com.adpilot.modules.store.entity.MarketplaceEntity;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link AggregationServiceImpl#aggregate}.
 *
 * <p>Feature: core-platform-completion, Property 15: All-stores aggregation equals
 * the sum over accessible stores only.
 *
 * <p>For any set of stores with per-store metrics, the all-stores totals equal the
 * sum of the per-store metrics across exactly the stores the user may access
 * (converted to the reporting currency where a rate exists), and no inaccessible
 * store contributes to either the totals or the per-store breakdown.
 *
 * <p>The collaborators are replaced with Mockito stubs so accessible stores,
 * per-store metrics, and rate availability can be controlled precisely:
 * {@link DataScopeService#applyScope} is a no-op and {@link StoreMapper} returns
 * <em>only</em> the accessible stores (modelling the data-scope-filtered query),
 * {@link OrderMapper} returns each store's generated orders, {@link MarketplaceMapper}
 * supplies each store's currency, and {@link CurrencyService} converts at a known
 * rate for currencies that have one and flags the rest unconverted.
 *
 * Validates: Requirements 5.1.1, 5.1.2, 5.1.3, 5.1.4
 */
class AllStoresAggregationPropertyTest {

    private static final int MONEY_SCALE = 2;
    private static final String REPORTING_CURRENCY = "USD";
    private static final List<String> CURRENCY_POOL = List.of("USD", "EUR", "GBP", "JPY");

    // Feature: core-platform-completion, Property 15: All-stores aggregation equals the sum over accessible stores only
    @Property(tries = 200)
    void totalsEqualSumOverAccessibleStoresOnly(@ForAll("scenarios") Scenario scenario) {
        // --- materialize the scenario into entities with unique ids ----------
        Map<UUID, List<OrderEntity>> ordersByStore = new HashMap<>();
        List<StoreEntity> accessibleStores = new ArrayList<>();
        List<MarketplaceEntity> marketplaces = new ArrayList<>();
        Map<UUID, String> currencyByStore = new HashMap<>();
        List<UUID> inaccessibleIds = new ArrayList<>();

        materialize(scenario.accessible, true, accessibleStores, marketplaces, ordersByStore,
                currencyByStore, inaccessibleIds);
        materialize(scenario.inaccessible, false, accessibleStores, marketplaces, ordersByStore,
                currencyByStore, inaccessibleIds);

        // --- wire collaborators ---------------------------------------------
        DataScopeService dataScopeService = mock(DataScopeService.class); // applyScope is a no-op void
        StoreMapper storeMapper = mock(StoreMapper.class);
        MarketplaceMapper marketplaceMapper = mock(MarketplaceMapper.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        CurrencyService currencyService = mock(CurrencyService.class);

        // Req 5.1.3: the data-scope-filtered query yields exactly the accessible stores.
        when(storeMapper.selectList(any())).thenReturn(accessibleStores);
        when(marketplaceMapper.selectList(any())).thenReturn(marketplaces);

        // The impl queries orders once per accessible store, iterating the store list in
        // order; return each store's generated orders in that same sequence.
        java.util.concurrent.atomic.AtomicInteger orderCallIndex = new java.util.concurrent.atomic.AtomicInteger();
        when(orderMapper.selectList(any())).thenAnswer(invocation -> {
            int i = orderCallIndex.getAndIncrement();
            if (i >= accessibleStores.size()) {
                return List.of();
            }
            UUID storeId = accessibleStores.get(i).getId();
            return ordersByStore.getOrDefault(storeId, List.of());
        });

        // Req 5.1.4: convert via CurrencyService where a rate exists; otherwise flag unconverted.
        when(currencyService.convert(any(), any(), any(), any())).thenAnswer(invocation -> {
            BigDecimal amount = invocation.getArgument(0);
            String from = invocation.getArgument(1);
            BigDecimal rate = scenario.currencyRates.get(from);
            if (rate == null) {
                return ConvertedAmount.unconverted(amount, from);
            }
            return ConvertedAmount.converted(amount, from, amount.multiply(rate), rate, null);
        });

        AggregationServiceImpl service = new AggregationServiceImpl(
                dataScopeService, storeMapper, marketplaceMapper, orderMapper, currencyService, REPORTING_CURRENCY);

        // --- act -------------------------------------------------------------
        AggregatedMetricsVo result = service.aggregate(user(), new DateRange(null, null));

        // --- expected values computed independently over accessible stores only
        BigDecimal expectedConvertedTotal = BigDecimal.ZERO;
        long expectedOrderCount = 0L;
        long expectedUnits = 0L;
        for (StoreEntity store : accessibleStores) {
            List<OrderEntity> orders = ordersByStore.get(store.getId());
            BigDecimal revenue = sumRevenue(orders);
            String currency = currencyByStore.get(store.getId());
            BigDecimal rate = scenario.currencyRates.get(currency);
            if (rate != null) {
                expectedConvertedTotal = expectedConvertedTotal.add(revenue.multiply(rate));
            }
            expectedOrderCount += orders.size();
            expectedUnits += orders.stream().mapToLong(o -> o.getQuantityOrdered()).sum();
        }
        BigDecimal expectedRevenue = expectedConvertedTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // --- assert ----------------------------------------------------------
        // Req 5.1.4: converted totals are the sum over accessible stores with a rate.
        assertThat(result.getTotals().getRevenue()).isEqualByComparingTo(expectedRevenue);
        // Currency-independent counts always include every accessible store (Req 5.1.1).
        assertThat(result.getTotals().getOrderCount()).isEqualTo(expectedOrderCount);
        assertThat(result.getTotals().getUnitsSold()).isEqualTo(expectedUnits);

        // Req 5.1.2 / 5.1.3: the per-store breakdown is exactly the accessible stores.
        Set<String> reportedIds = result.getByStore().stream()
                .map(StoreMetricsVo::getStoreId)
                .collect(Collectors.toSet());
        Set<String> accessibleIdSet = accessibleStores.stream()
                .map(s -> s.getId().toString())
                .collect(Collectors.toSet());
        assertThat(reportedIds).isEqualTo(accessibleIdSet);

        // Req 5.1.3: no inaccessible store appears in the breakdown.
        Set<String> inaccessibleIdSet = inaccessibleIds.stream().map(UUID::toString).collect(Collectors.toSet());
        if (!inaccessibleIdSet.isEmpty()) {
            assertThat(reportedIds).doesNotContainAnyElementsOf(inaccessibleIdSet);
        }

        // Per-store: revenue is reported in the store's own currency, converted where a rate exists (Req 5.1.4).
        for (StoreMetricsVo row : result.getByStore()) {
            UUID storeId = UUID.fromString(row.getStoreId());
            BigDecimal revenue = sumRevenue(ordersByStore.get(storeId));
            String currency = currencyByStore.get(storeId);
            BigDecimal rate = scenario.currencyRates.get(currency);
            assertThat(row.getRevenue()).isEqualByComparingTo(revenue.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            if (rate != null) {
                assertThat(row.isUnconverted()).isFalse();
                assertThat(row.getConvertedRevenue())
                        .isEqualByComparingTo(revenue.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            } else {
                assertThat(row.isUnconverted()).isTrue();
                assertThat(row.getConvertedRevenue()).isNull();
            }
        }
    }

    // --- helpers --------------------------------------------------------------

    private void materialize(List<StoreBlueprint> blueprints,
                             boolean accessible,
                             List<StoreEntity> accessibleStores,
                             List<MarketplaceEntity> marketplaces,
                             Map<UUID, List<OrderEntity>> ordersByStore,
                             Map<UUID, String> currencyByStore,
                             List<UUID> inaccessibleIds) {
        for (StoreBlueprint bp : blueprints) {
            UUID storeId = UUID.randomUUID();
            UUID marketplaceId = UUID.randomUUID();

            marketplaces.add(MarketplaceEntity.builder()
                    .id(marketplaceId)
                    .code(bp.currency)
                    .name("MP-" + bp.currency)
                    .currency(bp.currency)
                    .vatApplicable(Boolean.FALSE)
                    .build());

            currencyByStore.put(storeId, bp.currency);

            List<OrderEntity> orders = new ArrayList<>();
            for (OrderBlueprint ob : bp.orders) {
                orders.add(OrderEntity.builder()
                        .id(UUID.randomUUID())
                        .storeId(storeId)
                        .orderId(UUID.randomUUID().toString())
                        .currency(bp.currency)
                        .quantityOrdered(ob.quantity)
                        .itemPrice(cents(ob.itemPriceCents))
                        .shippingPrice(cents(ob.shippingCents))
                        .itemPromotionDiscount(cents(ob.itemPromoCents))
                        .shipPromotionDiscount(cents(ob.shipPromoCents))
                        .build());
            }
            ordersByStore.put(storeId, orders);

            if (accessible) {
                accessibleStores.add(StoreEntity.builder()
                        .id(storeId)
                        .name("Store-" + storeId)
                        .marketplaceId(marketplaceId)
                        .build());
            } else {
                inaccessibleIds.add(storeId);
            }
        }
    }

    /** Mirror of {@code AggregationServiceImpl.lineRevenue} summed over a store's orders. */
    private static BigDecimal sumRevenue(List<OrderEntity> orders) {
        BigDecimal total = BigDecimal.ZERO;
        for (OrderEntity o : orders) {
            total = total
                    .add(o.getItemPrice())
                    .add(o.getShippingPrice())
                    .subtract(o.getItemPromotionDiscount())
                    .subtract(o.getShipPromotionDiscount());
        }
        return total;
    }

    private static BigDecimal cents(long cents) {
        return new BigDecimal(cents).movePointLeft(MONEY_SCALE);
    }

    private static CurrentUser user() {
        return CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .orgId(UUID.randomUUID().toString())
                .email("manager@example.com")
                .name("Manager")
                .roles(Set.of("operator"))
                .permissions(List.of())
                .build();
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<List<StoreBlueprint>> accessible = storeBlueprints().list().ofMaxSize(5);
        Arbitrary<List<StoreBlueprint>> inaccessible = storeBlueprints().list().ofMaxSize(4);
        // A random subset of the currency pool has a rate; the rest are unconvertible.
        Arbitrary<Map<String, BigDecimal>> rates = currencyRates();
        return Combinators.combine(accessible, inaccessible, rates).as(Scenario::new);
    }

    private Arbitrary<StoreBlueprint> storeBlueprints() {
        Arbitrary<String> currency = Arbitraries.of(CURRENCY_POOL);
        Arbitrary<List<OrderBlueprint>> orders = orderBlueprints().list().ofMaxSize(6);
        return Combinators.combine(currency, orders).as(StoreBlueprint::new);
    }

    private Arbitrary<OrderBlueprint> orderBlueprints() {
        Arbitrary<Long> item = Arbitraries.longs().between(0L, 1_000_000L);
        Arbitrary<Long> shipping = Arbitraries.longs().between(0L, 100_000L);
        Arbitrary<Long> itemPromo = Arbitraries.longs().between(0L, 50_000L);
        Arbitrary<Long> shipPromo = Arbitraries.longs().between(0L, 50_000L);
        Arbitrary<Integer> quantity = Arbitraries.integers().between(0, 50);
        return Combinators.combine(item, shipping, itemPromo, shipPromo, quantity).as(OrderBlueprint::new);
    }

    private Arbitrary<Map<String, BigDecimal>> currencyRates() {
        // For each currency, decide independently whether a rate exists and its value.
        Arbitrary<Boolean> present = Arbitraries.of(true, false);
        Arbitrary<Long> rate = Arbitraries.longs().between(1L, 5_000_000L);
        return Combinators.combine(
                present, rate, present, rate, present, rate, present, rate
        ).as((p0, r0, p1, r1, p2, r2, p3, r3) -> {
            Map<String, BigDecimal> map = new LinkedHashMap<>();
            boolean[] flags = {p0, p1, p2, p3};
            long[] values = {r0, r1, r2, r3};
            for (int i = 0; i < CURRENCY_POOL.size(); i++) {
                if (flags[i]) {
                    map.put(CURRENCY_POOL.get(i), new BigDecimal(values[i]).movePointLeft(6));
                }
            }
            return map;
        });
    }

    // --- fixtures -------------------------------------------------------------

    static final class Scenario {
        final List<StoreBlueprint> accessible;
        final List<StoreBlueprint> inaccessible;
        final Map<String, BigDecimal> currencyRates;

        Scenario(List<StoreBlueprint> accessible, List<StoreBlueprint> inaccessible,
                 Map<String, BigDecimal> currencyRates) {
            this.accessible = accessible;
            this.inaccessible = inaccessible;
            this.currencyRates = currencyRates;
        }
    }

    static final class StoreBlueprint {
        final String currency;
        final List<OrderBlueprint> orders;

        StoreBlueprint(String currency, List<OrderBlueprint> orders) {
            this.currency = currency;
            this.orders = orders;
        }
    }

    static final class OrderBlueprint {
        final long itemPriceCents;
        final long shippingCents;
        final long itemPromoCents;
        final long shipPromoCents;
        final int quantity;

        OrderBlueprint(long itemPriceCents, long shippingCents, long itemPromoCents,
                       long shipPromoCents, int quantity) {
            this.itemPriceCents = itemPriceCents;
            this.shippingCents = shippingCents;
            this.itemPromoCents = itemPromoCents;
            this.shipPromoCents = shipPromoCents;
            this.quantity = quantity;
        }
    }
}
