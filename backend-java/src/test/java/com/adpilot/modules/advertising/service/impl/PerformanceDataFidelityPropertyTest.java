package com.adpilot.modules.advertising.service.impl;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.CampaignProductLinkEntity;
import com.adpilot.modules.advertising.mapper.AdvertisedProductReportMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.CampaignProductLinkMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.support.CampaignPerfAggRow;
import com.adpilot.modules.advertising.vo.ProductCampaignVo;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for performance-metric and data-status fidelity in
 * {@link ProductAdServiceImpl#listProductCampaigns}.
 *
 * <p>Feature: multistore-ai-ads-operations, Property 12: 表现数据与数据状态保真映射
 * (performance data and data-status faithful mapping).
 *
 * <p>Validates: Requirements 2.2, 2.3.
 *
 * <p>Property 12 (transcribed from the design's Correctness Properties section):
 * <em>For any {@code performance_daily} / {@code advertised_product_report} row,
 * mapping to the outward VO must include spend, clicks, orders, sales and ACoS
 * with values consistent with the source row; if the source row carries a
 * {@code data_status}, the VO must faithfully reflect preliminary or finalized —
 * never rewritten or lost.</em>
 *
 * <p>The service is driven against mocked mappers so the test fully controls the
 * single {@link CampaignPerfAggRow} that {@code aggregateByCampaign} returns for
 * the one campaign associated with the queried product. We then assert that the
 * resulting {@link ProductCampaignVo}:
 * <ul>
 *   <li>passes spend / clicks / orders / sales through unchanged from the source
 *       aggregate row (Req 2.2);</li>
 *   <li>computes ACoS as {@code spend / sales * 100} (0 when there are no sales)
 *       consistently with those same source values (Req 2.2); and</li>
 *   <li>faithfully derives the data status — {@code finalized} only when the
 *       source aggregate reports every contributing day finalized
 *       ({@code allFinalized == 1}), otherwise {@code preliminary}, and
 *       {@code null} only when no performance row exists (Req 2.3) — so the
 *       source status is never rewritten or lost.</li>
 * </ul>
 */
@Tag("pbt")
@Label("Feature: multistore-ai-ads-operations, Property 12: 表现数据与数据状态保真映射")
class PerformanceDataFidelityPropertyTest {

    private static final int MIN_ITERATIONS = 100;

    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID CAMPAIGN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final String QUERIED_ASIN = "B0FIDELITY1";

    /**
     * Feature: multistore-ai-ads-operations, Property 12: 表现数据与数据状态保真映射.
     *
     * <p>Validates: Requirements 2.2, 2.3.
     *
     * <p>For any aggregated performance row (or the absence of one), the mapped VO
     * carries spend/clicks/orders/sales consistent with the source, an ACoS equal
     * to {@code spend / sales * 100} (0 without sales), and a data status that
     * faithfully reflects the source — finalized only when fully finalized,
     * preliminary otherwise, and null only when no row exists.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 12: listProductCampaigns maps performance metrics and data status faithfully")
    void mapsPerformanceMetricsAndDataStatusFaithfully(@ForAll("perfScenarios") PerfScenario scenario) {
        CampaignProductLinkMapper linkMapper = mock(CampaignProductLinkMapper.class);
        CampaignMapper campaignMapper = mock(CampaignMapper.class);
        PerformanceDailyMapper performanceDailyMapper = mock(PerformanceDailyMapper.class);
        AdvertisedProductReportMapper advertisedProductReportMapper = mock(AdvertisedProductReportMapper.class);
        DataScopeService dataScopeService = mock(DataScopeService.class);

        // A single campaign linked to the queried product by parent ASIN.
        CampaignProductLinkEntity link = CampaignProductLinkEntity.builder()
                .id(UUID.randomUUID())
                .storeId(STORE_ID)
                .campaignId(CAMPAIGN_ID)
                .parentAsin(QUERIED_ASIN)
                .productId(UUID.randomUUID())
                .build();
        when(linkMapper.selectList(any())).thenReturn(List.of(link));

        CampaignEntity campaign = CampaignEntity.builder()
                .id(CAMPAIGN_ID)
                .storeId(STORE_ID)
                .name("fidelity-campaign")
                .status("enabled")
                .build();
        when(campaignMapper.selectBatchIds(anyList())).thenReturn(List.of(campaign));

        // The aggregate row under test (or no row at all) flows in here.
        when(performanceDailyMapper.aggregateByCampaign(anyString(), anyList()))
                .thenReturn(scenario.aggregateRows());

        ProductAdServiceImpl service = new ProductAdServiceImpl(
                advertisedProductReportMapper, campaignMapper, linkMapper,
                performanceDailyMapper, dataScopeService);

        CurrentUser user = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .email("scope@test.local")
                .roles(Set.of("USER"))
                .permissions(List.of("advertising:view"))
                .build();

        List<ProductCampaignVo> result;
        try (MockedStatic<SecurityUtils> security = org.mockito.Mockito.mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAuthenticated).thenReturn(true);
            security.when(SecurityUtils::getCurrentUser).thenReturn(user);
            result = service.listProductCampaigns(STORE_ID.toString(), QUERIED_ASIN, null);
        }

        assertThat(result).hasSize(1);
        ProductCampaignVo vo = result.get(0);

        // Spend / clicks / orders / sales pass through consistently (Req 2.2).
        assertThat(vo.getSpend()).isEqualTo(scenario.expectedSpend);
        assertThat(vo.getClicks()).isEqualTo(scenario.expectedClicks);
        assertThat(vo.getOrders()).isEqualTo(scenario.expectedOrders);
        assertThat(vo.getSales()).isEqualTo(scenario.expectedSales);

        // ACoS is computed as spend / sales * 100 from those same values, 0 without sales (Req 2.2).
        assertThat(vo.getAcos()).isCloseTo(scenario.expectedAcos, within(1e-9));

        // Data status is faithfully reflected — never rewritten or lost (Req 2.3).
        assertThat(vo.getDataStatus()).isEqualTo(scenario.expectedDataStatus);
    }

    // -----------------------------------------------------------------------------------------
    // Scenario + generator
    // -----------------------------------------------------------------------------------------

    /** A performance-fidelity scenario: the source aggregate row and its faithful expectations. */
    static final class PerfScenario {
        /** Whether a performance aggregate row exists at all (false models "no perf → null status"). */
        final boolean hasPerfRow;
        final BigDecimal spend;
        final Long clicks;
        final Long orders;
        final BigDecimal sales;
        /** 0 = at least one preliminary day, 1 = all finalized, null = unknown (treated as preliminary). */
        final Integer allFinalized;
        final int rowCount;

        final double expectedSpend;
        final int expectedClicks;
        final int expectedOrders;
        final double expectedSales;
        final double expectedAcos;
        final String expectedDataStatus;

        PerfScenario(boolean hasPerfRow, BigDecimal spend, Long clicks, Long orders,
                     BigDecimal sales, Integer allFinalized, int rowCount) {
            this.hasPerfRow = hasPerfRow;
            this.spend = spend;
            this.clicks = clicks;
            this.orders = orders;
            this.sales = sales;
            this.allFinalized = allFinalized;
            this.rowCount = rowCount;

            if (hasPerfRow) {
                this.expectedSpend = spend.doubleValue();
                this.expectedClicks = (int) (long) clicks;
                this.expectedOrders = (int) (long) orders;
                this.expectedSales = sales.doubleValue();
                this.expectedDataStatus =
                        (allFinalized != null && allFinalized == 1) ? "finalized" : "preliminary";
            } else {
                // No contributing performance row: metrics default to zero, status is null.
                this.expectedSpend = 0;
                this.expectedClicks = 0;
                this.expectedOrders = 0;
                this.expectedSales = 0;
                this.expectedDataStatus = null;
            }
            this.expectedAcos = this.expectedSales > 0
                    ? (this.expectedSpend / this.expectedSales) * 100 : 0;
        }

        /** What {@code aggregateByCampaign} returns: a single row, or nothing at all. */
        List<CampaignPerfAggRow> aggregateRows() {
            if (!hasPerfRow) {
                return Collections.emptyList();
            }
            CampaignPerfAggRow row = new CampaignPerfAggRow();
            row.setCampaignId(CAMPAIGN_ID.toString());
            row.setRowCount(rowCount);
            row.setSpend(spend);
            row.setClicks(clicks);
            row.setOrders(orders);
            row.setSales(sales);
            row.setAllFinalized(allFinalized);
            return List.of(row);
        }
    }

    @Provide
    Arbitrary<PerfScenario> perfScenarios() {
        Arbitrary<Boolean> hasPerfRow = Arbitraries.of(true, true, true, false); // bias toward present
        Arbitrary<BigDecimal> spend = money();
        Arbitrary<Long> clicks = Arbitraries.longs().between(0L, 1_000_000L);
        Arbitrary<Long> orders = Arbitraries.longs().between(0L, 1_000_000L);
        Arbitrary<BigDecimal> sales = money();
        Arbitrary<Integer> allFinalized = Arbitraries.of(0, 1, null);
        Arbitrary<Integer> rowCount = Arbitraries.integers().between(1, 60);

        return Combinators.combine(hasPerfRow, spend, clicks, orders, sales, allFinalized, rowCount)
                .as(PerfScenario::new);
    }

    /** Non-negative monetary amounts at cent scale, spanning zero (to exercise the no-sales ACoS path). */
    private Arbitrary<BigDecimal> money() {
        return Arbitraries.doubles().between(0.0, 100_000.0).ofScale(2)
                .map(BigDecimal::valueOf);
    }
}
