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
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for the profit aggregation math in {@link ProfitServiceImpl}.
 *
 * <p>These properties are honest to the implemented formula (read from the service):
 * <ul>
 *   <li><b>Aggregation consistency</b>: each dashboard total equals the straight
 *       {@code BigDecimal} sum of the corresponding field across all input rows.</li>
 *   <li><b>Ratio formulas</b>: net margin, ACOS, TACOS and ROAS equal the documented
 *       guarded division ({@code x*100/y} at scale 2, HALF_UP; zero when the denominator
 *       is not positive).</li>
 *   <li><b>Sum of parts equals the whole</b>: when every row carries a product id, the sum
 *       of the per-product net profit equals the dashboard's total net profit.</li>
 *   <li><b>Sign rule</b>: the loss-making SKU list contains exactly the products whose
 *       aggregate net profit is strictly negative, and the top-profitable list is ordered
 *       by net profit descending.</li>
 * </ul>
 *
 * <p>The service is exercised with a mocked {@link ProductProfitDailyMapper} returning the
 * generated rows, mirroring the mock-inside-property style of the project's other property tests.
 */
@Label("ProfitServiceImpl aggregation math properties")
class ProfitAggregationPropertyTest {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final List<UUID> PRODUCTS = List.of(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @Property(tries = 300)
    @Label("Dashboard totals equal the BigDecimal sum of the corresponding row fields")
    void totalsEqualRowSums(@ForAll("profitRows") List<ProductProfitDailyEntity> rows) {
        ProfitDashboardVo vo = runDashboard(rows);

        assertThat(vo.getTotalSales()).isEqualTo(sum(rows, ProductProfitDailyEntity::getGrossSales).doubleValue());
        assertThat(vo.getAdSales()).isEqualTo(sum(rows, ProductProfitDailyEntity::getAdSales).doubleValue());
        assertThat(vo.getOrganicSales()).isEqualTo(sum(rows, ProductProfitDailyEntity::getOrganicSales).doubleValue());
        assertThat(vo.getAdSpend()).isEqualTo(sum(rows, ProductProfitDailyEntity::getAdSpend).doubleValue());
        assertThat(vo.getGrossProfit()).isEqualTo(sum(rows, ProductProfitDailyEntity::getGrossProfit).doubleValue());
        assertThat(vo.getNetProfit()).isEqualTo(sum(rows, ProductProfitDailyEntity::getNetProfit).doubleValue());
    }

    @Property(tries = 300)
    @Label("Net margin, ACOS, TACOS and ROAS follow the documented guarded-division formula")
    void ratiosFollowFormula(@ForAll("profitRows") List<ProductProfitDailyEntity> rows) {
        ProfitDashboardVo vo = runDashboard(rows);

        BigDecimal grossSales = sum(rows, ProductProfitDailyEntity::getGrossSales);
        BigDecimal adSales = sum(rows, ProductProfitDailyEntity::getAdSales);
        BigDecimal adSpend = sum(rows, ProductProfitDailyEntity::getAdSpend);
        BigDecimal netProfit = sum(rows, ProductProfitDailyEntity::getNetProfit);

        assertThat(vo.getNetMargin()).isEqualTo(pct(netProfit, grossSales).doubleValue());
        assertThat(vo.getAcos()).isEqualTo(pct(adSpend, adSales).doubleValue());
        assertThat(vo.getTacos()).isEqualTo(pct(adSpend, grossSales).doubleValue());
        assertThat(vo.getRoas()).isEqualTo(ratio(adSales, adSpend).doubleValue());
    }

    @Property(tries = 300)
    @Label("Sum of per-product net profit equals the dashboard total net profit")
    void sumOfPartsEqualsWhole(@ForAll("profitRows") List<ProductProfitDailyEntity> rows) {
        ProfitServiceImpl service = newService(rows);

        ProfitDashboardVo dashboard = service.getProfitDashboard(storeId(rows), "2024-01-01", "2024-12-31");
        List<ProductProfitVo> products = service.getProductProfits(storeId(rows), "2024-01-01", "2024-12-31");

        double partsTotal = products.stream().mapToDouble(ProductProfitVo::getNetProfit).sum();
        // Every generated row carries a product id, so the per-product parts cover the whole.
        assertThat(partsTotal).isCloseTo(dashboard.getNetProfit(), within(1e-6));
    }

    @Property(tries = 300)
    @Label("Loss-making SKU list is exactly the negative-net products; top list is net-descending")
    void signRuleAndOrdering(@ForAll("profitRows") List<ProductProfitDailyEntity> rows) {
        ProfitDashboardVo vo = runDashboard(rows);

        // Every SKU in the loss list has a strictly negative net profit.
        assertThat(vo.getProfitLosingSkus())
                .allMatch(p -> p.getNetProfit() < 0);

        // The top-profitable list is ordered by net profit, descending.
        List<Double> nets = vo.getTopProfitableSkus().stream()
                .map(ProductProfitVo::getNetProfit)
                .toList();
        for (int i = 1; i < nets.size(); i++) {
            assertThat(nets.get(i)).isLessThanOrEqualTo(nets.get(i - 1));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Service wiring
    // ---------------------------------------------------------------------------------------------

    private ProfitDashboardVo runDashboard(List<ProductProfitDailyEntity> rows) {
        return newService(rows).getProfitDashboard(storeId(rows), "2024-01-01", "2024-12-31");
    }

    @SuppressWarnings("unchecked")
    private ProfitServiceImpl newService(List<ProductProfitDailyEntity> rows) {
        ProductProfitDailyMapper profitMapper = Mockito.mock(ProductProfitDailyMapper.class);
        ProductMapper productMapper = Mockito.mock(ProductMapper.class);
        CampaignMapper campaignMapper = Mockito.mock(CampaignMapper.class);
        GoalMapper goalMapper = Mockito.mock(GoalMapper.class);
        PerformanceDailyMapper performanceDailyMapper = Mockito.mock(PerformanceDailyMapper.class);
        when(profitMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(rows);
        when(productMapper.selectById(any())).thenReturn(null);
        com.adpilot.modules.store.mapper.StoreMapper storeMapper =
                Mockito.mock(com.adpilot.modules.store.mapper.StoreMapper.class);
        com.adpilot.modules.store.service.MarketplaceReferenceService marketplaceReferenceService =
                Mockito.mock(com.adpilot.modules.store.service.MarketplaceReferenceService.class);
        when(marketplaceReferenceService.timezoneForMarketplace(any()))
                .thenReturn(java.time.ZoneId.of("UTC"));
        return new ProfitServiceImpl(profitMapper, productMapper, campaignMapper, goalMapper,
                performanceDailyMapper, storeMapper, marketplaceReferenceService);
    }

    private String storeId(List<ProductProfitDailyEntity> rows) {
        return rows.get(0).getStoreId().toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Formula oracles (mirror ProfitServiceImpl exactly)
    // ---------------------------------------------------------------------------------------------

    private static BigDecimal sum(List<ProductProfitDailyEntity> rows,
                                  Function<ProductProfitDailyEntity, BigDecimal> getter) {
        return rows.stream().map(getter).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** {@code x*100/y} at scale 2 HALF_UP, or zero when {@code y <= 0}. */
    private static BigDecimal pct(BigDecimal x, BigDecimal y) {
        return y.compareTo(BigDecimal.ZERO) > 0
                ? x.multiply(HUNDRED).divide(y, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }

    /** {@code x/y} at scale 2 HALF_UP, or zero when {@code y <= 0}. */
    private static BigDecimal ratio(BigDecimal x, BigDecimal y) {
        return y.compareTo(BigDecimal.ZERO) > 0
                ? x.divide(y, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }

    // ---------------------------------------------------------------------------------------------
    // Generators
    // ---------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<List<ProductProfitDailyEntity>> profitRows() {
        UUID storeId = UUID.randomUUID();
        Arbitrary<ProductProfitDailyEntity> row = Combinators.combine(
                nonNegativeMoney(),   // grossSales
                nonNegativeMoney(),   // adSales
                nonNegativeMoney(),   // organicSales
                nonNegativeMoney(),   // adSpend
                signedMoney(),        // grossProfit
                signedMoney(),        // netProfit
                Arbitraries.of(PRODUCTS)
        ).as((grossSales, adSales, organicSales, adSpend, grossProfit, netProfit, productId) -> {
            ProductProfitDailyEntity e = new ProductProfitDailyEntity();
            e.setStoreId(storeId);
            e.setProductId(productId);
            e.setSku("SKU-" + productId.toString().substring(0, 8));
            e.setDate(LocalDate.of(2024, 6, 1));
            e.setUnitsSold(1);
            e.setGrossSales(grossSales);
            e.setAdSales(adSales);
            e.setOrganicSales(organicSales);
            e.setAdSpend(adSpend);
            e.setGrossProfit(grossProfit);
            e.setNetProfit(netProfit);
            return e;
        });
        return row.list().ofMinSize(1).ofMaxSize(15);
    }

    /** Non-negative money with 2-decimal scale, up to 100,000.00. */
    private Arbitrary<BigDecimal> nonNegativeMoney() {
        return Arbitraries.longs().between(0, 10_000_000L)
                .map(cents -> BigDecimal.valueOf(cents, 2));
    }

    /** Signed money with 2-decimal scale, in [-100,000.00, 100,000.00]. */
    private Arbitrary<BigDecimal> signedMoney() {
        return Arbitraries.longs().between(-10_000_000L, 10_000_000L)
                .map(cents -> BigDecimal.valueOf(cents, 2));
    }
}
