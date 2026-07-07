package com.adpilot.modules.profit.service.impl;

import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.GoalMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.profit.entity.ProductProfitDailyEntity;
import com.adpilot.modules.profit.mapper.ProductProfitDailyMapper;
import com.adpilot.modules.profit.vo.ProductProfitVo;
import com.adpilot.modules.profit.vo.ProfitDashboardVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProfitServiceImpl} — the profit attribution / aggregation service.
 *
 * <p>Covers the empty-data path, the dashboard aggregation totals and the documented ratio formulas
 * (net margin, ACOS, TACOS, ROAS), and the per-product aggregation with top-profitable / loss-making
 * SKU classification. Amounts are asserted against values recomputed with the same {@code BigDecimal}
 * arithmetic the service uses.
 */
@DisplayName("ProfitServiceImpl unit tests")
class ProfitServiceImplTest {

    private ProductProfitDailyMapper profitMapper;
    private ProductMapper productMapper;
    private ProfitServiceImpl service;

    private static final String STORE_ID = UUID.randomUUID().toString();
    private static final UUID PRODUCT_A = UUID.randomUUID();
    private static final UUID PRODUCT_B = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        profitMapper = mock(ProductProfitDailyMapper.class);
        productMapper = mock(ProductMapper.class);
        CampaignMapper campaignMapper = mock(CampaignMapper.class);
        GoalMapper goalMapper = mock(GoalMapper.class);
        PerformanceDailyMapper performanceDailyMapper = mock(PerformanceDailyMapper.class);
        com.adpilot.modules.store.mapper.StoreMapper storeMapper =
                mock(com.adpilot.modules.store.mapper.StoreMapper.class);
        com.adpilot.modules.store.service.MarketplaceReferenceService marketplaceReferenceService =
                mock(com.adpilot.modules.store.service.MarketplaceReferenceService.class);
        when(marketplaceReferenceService.timezoneForMarketplace(any()))
                .thenReturn(java.time.ZoneId.of("UTC"));
        service = new ProfitServiceImpl(profitMapper, productMapper, campaignMapper, goalMapper,
                performanceDailyMapper, storeMapper, marketplaceReferenceService);
        // Product lookup is not the subject here; SKU/ASIN then fall back to the daily rows.
        when(productMapper.selectById(any())).thenReturn(null);
    }

    @Test
    @DisplayName("getProfitDashboard: no rows returns an all-zero dashboard with empty SKU lists")
    void emptyDashboard() {
        stubProfits(List.of());

        ProfitDashboardVo vo = service.getProfitDashboard(STORE_ID, "2024-01-01", "2024-01-31");

        assertThat(vo.getTotalSales()).isZero();
        assertThat(vo.getNetProfit()).isZero();
        assertThat(vo.getNetMargin()).isZero();
        assertThat(vo.getAcos()).isZero();
        assertThat(vo.getRoas()).isZero();
        assertThat(vo.getTopProfitableSkus()).isEmpty();
        assertThat(vo.getProfitLosingSkus()).isEmpty();
        assertThat(vo.getAiSummary()).isNotBlank();
    }

    @Test
    @DisplayName("getProfitDashboard: totals sum the rows and ratios follow the documented formulas")
    void dashboardAggregationAndRatios() {
        // Product A: two profitable days; Product B: one loss-making day.
        List<ProductProfitDailyEntity> rows = List.of(
                row(PRODUCT_A, "SKU-A", "1000", "400", "600", "100", "300", "200"),
                row(PRODUCT_A, "SKU-A", "500", "200", "300", "50", "150", "100"),
                row(PRODUCT_B, "SKU-B", "200", "0", "200", "0", "-50", "-80"));
        stubProfits(rows);

        ProfitDashboardVo vo = service.getProfitDashboard(STORE_ID, "2024-01-01", "2024-01-31");

        // Totals: straight sums of the source rows.
        assertThat(vo.getTotalSales()).isEqualTo(1700.0);
        assertThat(vo.getAdSales()).isEqualTo(600.0);
        assertThat(vo.getOrganicSales()).isEqualTo(1100.0);
        assertThat(vo.getAdSpend()).isEqualTo(150.0);
        assertThat(vo.getGrossProfit()).isEqualTo(400.0);
        assertThat(vo.getNetProfit()).isEqualTo(220.0);

        // Ratios (scale 2, HALF_UP): netMargin=220*100/1700, acos=150*100/600,
        // tacos=150*100/1700, roas=600/150.
        assertThat(vo.getNetMargin()).isEqualTo(12.94);
        assertThat(vo.getAcos()).isEqualTo(25.00);
        assertThat(vo.getTacos()).isEqualTo(8.82);
        assertThat(vo.getRoas()).isEqualTo(4.00);

        // Product A aggregates to +300 net; Product B to -80 net.
        assertThat(vo.getTopProfitableSkus())
                .extracting(ProductProfitVo::getNetProfit)
                .containsExactly(300.0, -80.0); // sorted descending by net profit
        assertThat(vo.getProfitLosingSkus())
                .extracting(ProductProfitVo::getSku)
                .containsExactly("SKU-B");
    }

    @Test
    @DisplayName("getProductProfits: per-product aggregation is sorted by net profit descending")
    void productProfitsAggregation() {
        List<ProductProfitDailyEntity> rows = List.of(
                row(PRODUCT_A, "SKU-A", "1000", "400", "600", "100", "300", "200"),
                row(PRODUCT_A, "SKU-A", "500", "200", "300", "50", "150", "100"),
                row(PRODUCT_B, "SKU-B", "200", "0", "200", "0", "-50", "-80"));
        stubProfits(rows);

        List<ProductProfitVo> result = service.getProductProfits(STORE_ID, "2024-01-01", "2024-01-31");

        assertThat(result).hasSize(2);
        // Descending by net profit: A(+300) before B(-80).
        assertThat(result).isSortedAccordingTo(Comparator.comparingDouble(ProductProfitVo::getNetProfit).reversed());
        ProductProfitVo a = result.stream().filter(v -> "SKU-A".equals(v.getSku())).findFirst().orElseThrow();
        assertThat(a.getGrossSales()).isEqualTo(1500.0);
        assertThat(a.getAdSpend()).isEqualTo(150.0);
        assertThat(a.getNetProfit()).isEqualTo(300.0);
        // acos for A = adSpend*100/adSales = 150*100/600 = 25.00
        assertThat(a.getAcos()).isEqualTo(25.00);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stubProfits(List<ProductProfitDailyEntity> rows) {
        when(profitMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(rows);
    }

    private ProductProfitDailyEntity row(UUID productId, String sku,
                                         String grossSales, String adSales, String organicSales,
                                         String adSpend, String grossProfit, String netProfit) {
        ProductProfitDailyEntity e = new ProductProfitDailyEntity();
        e.setStoreId(UUID.fromString(STORE_ID));
        e.setProductId(productId);
        e.setSku(sku);
        e.setDate(LocalDate.of(2024, 1, 10));
        e.setUnitsSold(1);
        e.setGrossSales(new BigDecimal(grossSales));
        e.setAdSales(new BigDecimal(adSales));
        e.setOrganicSales(new BigDecimal(organicSales));
        e.setAdSpend(new BigDecimal(adSpend));
        e.setGrossProfit(new BigDecimal(grossProfit));
        e.setNetProfit(new BigDecimal(netProfit));
        return e;
    }
}
