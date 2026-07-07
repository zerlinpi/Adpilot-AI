package com.adpilot.modules.inventory.service.impl;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.inventory.entity.InventoryForecastEntity;
import com.adpilot.modules.inventory.entity.InventorySnapshotEntity;
import com.adpilot.modules.inventory.entity.ReplenishmentPlanEntity;
import com.adpilot.modules.inventory.mapper.InventoryForecastMapper;
import com.adpilot.modules.inventory.mapper.InventorySnapshotMapper;
import com.adpilot.modules.inventory.mapper.ReplenishmentPlanMapper;
import com.adpilot.modules.order.entity.OrderEntity;
import com.adpilot.modules.order.mapper.OrderMapper;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.MarketplaceReferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class InventoryServiceImplTest {

    private InventorySnapshotMapper snapshotMapper;
    private InventoryForecastMapper forecastMapper;
    private ReplenishmentPlanMapper replenishmentMapper;
    private ProductMapper productMapper;
    private OrderMapper orderMapper;
    private DataScopeService dataScopeService;
    private InventoryServiceImpl service;

    @BeforeEach
    void setUp() {
        snapshotMapper = mock(InventorySnapshotMapper.class);
        forecastMapper = mock(InventoryForecastMapper.class);
        replenishmentMapper = mock(ReplenishmentPlanMapper.class);
        productMapper = mock(ProductMapper.class);
        orderMapper = mock(OrderMapper.class);
        dataScopeService = mock(DataScopeService.class);
        StoreMapper storeMapper = mock(StoreMapper.class);
        MarketplaceReferenceService marketplaceReferenceService = mock(MarketplaceReferenceService.class);
        when(marketplaceReferenceService.timezoneForMarketplace(any())).thenReturn(ZoneId.of("UTC"));
        service = new InventoryServiceImpl(
                snapshotMapper,
                forecastMapper,
                replenishmentMapper,
                productMapper,
                orderMapper,
                storeMapper,
                marketplaceReferenceService,
                mock(AuditLogService.class),
                dataScopeService);
    }

    @Test
    void latestLowRiskForecastSuppressesStaleHighRiskPlan() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        InventoryForecastEntity latestLow = new InventoryForecastEntity();
        latestLow.setStoreId(storeId);
        latestLow.setProductId(productId);
        latestLow.setForecastDate(LocalDate.now());
        latestLow.setStockoutRisk("low");

        InventoryForecastEntity staleHigh = new InventoryForecastEntity();
        staleHigh.setStoreId(storeId);
        staleHigh.setProductId(productId);
        staleHigh.setForecastDate(LocalDate.now().minusDays(1));
        staleHigh.setStockoutRisk("high");

        when(productMapper.selectList(any())).thenReturn(List.of());
        when(forecastMapper.selectList(any())).thenReturn(List.of(latestLow, staleHigh));

        assertThat(service.generateReplenishmentPlans(storeId.toString(), null)).isEmpty();
        verify(dataScopeService).assertCanWrite(any(), isNull());
        verifyNoInteractions(replenishmentMapper);
    }

    @Test
    void recentOrdersProduceARealReplenishmentForecast() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        ProductEntity product = ProductEntity.builder()
                .id(productId).storeId(storeId).sku("SKU-1").name("Product")
                .inventory(5).cost(new BigDecimal("2.00")).build();
        InventorySnapshotEntity snapshot = new InventorySnapshotEntity();
        snapshot.setId(UUID.randomUUID());
        snapshot.setStoreId(storeId);
        snapshot.setProductId(productId);
        snapshot.setSnapshotDate(LocalDate.now());
        snapshot.setTotalInventory(5);
        snapshot.setInventoryValue(new BigDecimal("10.00"));

        OrderEntity order = OrderEntity.builder()
                .id(UUID.randomUUID()).storeId(storeId).sku("SKU-1")
                .purchaseDate(LocalDateTime.now().minusDays(1))
                .orderStatus("shipped").quantityOrdered(60).build();

        List<InventoryForecastEntity> forecasts = new ArrayList<>();
        when(productMapper.selectList(any())).thenReturn(List.of(product));
        when(orderMapper.selectList(any())).thenReturn(List.of(order));
        when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot));
        when(forecastMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(forecasts));
        when(forecastMapper.insert(any(InventoryForecastEntity.class))).thenAnswer(invocation -> {
            forecasts.add(invocation.getArgument(0));
            return 1;
        });
        when(replenishmentMapper.selectList(any())).thenReturn(List.of());
        when(replenishmentMapper.insert(any(ReplenishmentPlanEntity.class))).thenAnswer(invocation -> {
            ReplenishmentPlanEntity plan = invocation.getArgument(0);
            plan.setId(UUID.randomUUID());
            return 1;
        });

        var plans = service.generateReplenishmentPlans(storeId.toString(), null);

        assertThat(forecasts).hasSize(1);
        assertThat(forecasts.get(0).getDailySalesVelocity()).isEqualByComparingTo("2.0000");
        assertThat(forecasts.get(0).getStockoutRisk()).isEqualTo("high");
        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).getRecommendedQty()).isEqualTo(115);
    }

    @Test
    void inventoryHealthChecksStoreScopeBeforeReading() {
        UUID storeId = UUID.randomUUID();
        when(productMapper.selectList(any())).thenReturn(List.of());
        when(snapshotMapper.selectList(any())).thenReturn(List.of());
        when(forecastMapper.selectList(any())).thenReturn(List.of());

        service.getInventoryHealth(storeId.toString());

        verify(dataScopeService).assertCanRead(any(), isNull());
        verify(snapshotMapper).selectList(any());
    }
}
