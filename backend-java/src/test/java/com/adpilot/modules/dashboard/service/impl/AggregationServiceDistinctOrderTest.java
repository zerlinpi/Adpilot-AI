package com.adpilot.modules.dashboard.service.impl;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.dashboard.vo.AggregatedMetricsVo;
import com.adpilot.modules.dashboard.vo.DateRange;
import com.adpilot.modules.finance.service.CurrencyService;
import com.adpilot.modules.finance.vo.ConvertedAmount;
import com.adpilot.modules.order.entity.OrderEntity;
import com.adpilot.modules.order.mapper.OrderMapper;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L3 — distinct-order counting for order rows that carry no {@code orderId}.
 *
 * <p>The fallback distinct-order key must be a stable identifier (DB id, then a
 * natural key, then a monotonic per-accumulation sequence) so two distinct rows
 * are never collapsed together. It must never use {@link System#identityHashCode}
 * which is not guaranteed unique across objects and can therefore undercount.</p>
 */
class AggregationServiceDistinctOrderTest {

    private DataScopeService dataScopeService;
    private StoreMapper storeMapper;
    private MarketplaceMapper marketplaceMapper;
    private OrderMapper orderMapper;
    private CurrencyService currencyService;
    private AggregationServiceImpl service;

    private final UUID storeId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        dataScopeService = mock(DataScopeService.class);
        storeMapper = mock(StoreMapper.class);
        marketplaceMapper = mock(MarketplaceMapper.class);
        orderMapper = mock(OrderMapper.class);
        currencyService = mock(CurrencyService.class);

        StoreEntity store = StoreEntity.builder()
                .id(storeId)
                .name("Store")
                .marketplaceId(UUID.randomUUID())
                .build();
        when(storeMapper.selectList(any())).thenReturn(List.of(store));
        when(marketplaceMapper.selectList(any())).thenReturn(List.of());
        // Currency conversion is irrelevant to order counting (counts are
        // currency-independent); return the amount unchanged.
        when(currencyService.convert(any(), anyString(), anyString(), any()))
                .thenAnswer(inv -> ConvertedAmount.converted(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(0),
                        BigDecimal.ONE, null));

        service = new AggregationServiceImpl(dataScopeService, storeMapper, marketplaceMapper,
                orderMapper, currencyService, "USD");
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoRowsWithoutOrderIdButDistinctDbIdCountAsTwo() {
        OrderEntity a = order(UUID.randomUUID(), null);
        OrderEntity b = order(UUID.randomUUID(), null);
        when(orderMapper.selectList(any())).thenReturn(List.of(a, b));

        AggregatedMetricsVo vo = service.aggregate(null, new DateRange(null, null));

        assertThat(vo.getTotals().getOrderCount()).isEqualTo(2L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoIndistinguishableRowsWithoutOrderIdOrIdStillCountAsTwo() {
        // No orderId, no DB id, and no natural-key fields at all: the only thing
        // that keeps these two rows distinct is the monotonic fallback sequence.
        // identityHashCode (the old fallback) could collide and undercount here.
        OrderEntity a = order(null, null);
        OrderEntity b = order(null, null);
        when(orderMapper.selectList(any())).thenReturn(List.of(a, b));

        AggregatedMetricsVo vo = service.aggregate(null, new DateRange(null, null));

        assertThat(vo.getTotals().getOrderCount()).isEqualTo(2L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rowsSharingAnOrderIdStillCollapseToOne() {
        // Primary (orderId-present) path is unchanged: same orderId -> one order.
        OrderEntity a = order(UUID.randomUUID(), "ORDER-1");
        OrderEntity b = order(UUID.randomUUID(), "ORDER-1");
        when(orderMapper.selectList(any())).thenReturn(List.of(a, b));

        AggregatedMetricsVo vo = service.aggregate(null, new DateRange(null, null));

        assertThat(vo.getTotals().getOrderCount()).isEqualTo(1L);
    }

    private OrderEntity order(UUID id, String orderId) {
        return OrderEntity.builder()
                .id(id)
                .storeId(storeId)
                .orderId(orderId)
                .currency("USD")
                .quantityOrdered(1)
                .itemPrice(new BigDecimal("10.00"))
                .build();
    }
}
