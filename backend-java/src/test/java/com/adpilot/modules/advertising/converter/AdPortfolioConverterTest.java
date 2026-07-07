package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.AdPortfolioEntity;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.vo.AdPortfolioVo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example-based unit tests for {@link AdPortfolioConverter} — the portfolio
 * rollup metric aggregation across member campaigns (Req 20.1) and the
 * no-budget-cap representation (Req 20.3).
 */
class AdPortfolioConverterTest {

    private AdPortfolioEntity portfolio(String budgetType, BigDecimal budget) {
        return AdPortfolioEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .name("Core Portfolio")
                .state("enabled")
                .budgetType(budgetType)
                .budget(budget)
                .build();
    }

    private CampaignEntity campaign(double spend, double sales, int orders, int clicks, long impressions) {
        return CampaignEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .name("c")
                .spend(BigDecimal.valueOf(spend))
                .sales(BigDecimal.valueOf(sales))
                .orders(orders)
                .clicks(clicks)
                .impressions(impressions)
                .build();
    }

    @Test
    void rollupSumsMemberCampaignMetrics() {
        AdPortfolioVo vo = AdPortfolioConverter.toVo(
                portfolio("recurring", new BigDecimal("100.00")),
                List.of(
                        campaign(10, 50, 2, 20, 1000),
                        campaign(15, 25, 1, 30, 3000)));

        assertThat(vo.getCampaignCount()).isEqualTo(2);
        assertThat(vo.getSpend()).isEqualTo(25.0);
        assertThat(vo.getSales()).isEqualTo(75.0);
        assertThat(vo.getOrders()).isEqualTo(3);
        assertThat(vo.getClicks()).isEqualTo(50);
        assertThat(vo.getImpressions()).isEqualTo(4000L);
        // ctr = clicks / impressions * 100 = 50/4000*100 = 1.25%
        assertThat(vo.getCtr()).isEqualTo(1.25);
        // cpc = spend / clicks = 25/50 = 0.5
        assertThat(vo.getCpc()).isEqualTo(0.5);
        assertThat(vo.getBudget()).isEqualTo(100.0);
    }

    @Test
    void emptyPortfolioHasZeroRollupAndSafeDivision() {
        AdPortfolioVo vo = AdPortfolioConverter.toVo(portfolio("none", null), List.of());

        assertThat(vo.getCampaignCount()).isZero();
        assertThat(vo.getSpend()).isZero();
        assertThat(vo.getSales()).isZero();
        assertThat(vo.getOrders()).isZero();
        assertThat(vo.getClicks()).isZero();
        assertThat(vo.getImpressions()).isZero();
        // No clicks / impressions => no division by zero, ctr and cpc are 0.
        assertThat(vo.getCtr()).isZero();
        assertThat(vo.getCpc()).isZero();
    }

    @Test
    void noBudgetCapPortfolioReportsNullBudgetWithNoneType() {
        AdPortfolioVo vo = AdPortfolioConverter.toVo(portfolio("none", null), null);

        assertThat(vo.getBudgetType()).isEqualTo("none");
        assertThat(vo.getBudget()).isNull();
    }
}
