package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.AdPortfolioEntity;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.vo.AdPortfolioVo;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Maps an {@link AdPortfolioEntity} (plus its member campaigns) to an
 * {@link AdPortfolioVo}, computing the portfolio rollup metrics (Req 20.1).
 * The rollup aggregates spend / sales / orders / clicks / impressions across
 * member campaigns; CTR and CPC are derived from those sums.
 */
public class AdPortfolioConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AdPortfolioConverter() {
        // Utility class.
    }

    public static AdPortfolioVo toVo(AdPortfolioEntity entity, List<CampaignEntity> members) {
        if (entity == null) {
            return null;
        }

        double spend = 0;
        double sales = 0;
        int orders = 0;
        int clicks = 0;
        long impressions = 0;
        int campaignCount = 0;

        if (members != null) {
            campaignCount = members.size();
            for (CampaignEntity c : members) {
                if (c == null) {
                    continue;
                }
                spend += c.getSpend() != null ? c.getSpend().doubleValue() : 0;
                sales += c.getSales() != null ? c.getSales().doubleValue() : 0;
                orders += c.getOrders() != null ? c.getOrders() : 0;
                clicks += c.getClicks() != null ? c.getClicks() : 0;
                impressions += c.getImpressions() != null ? c.getImpressions() : 0;
            }
        }

        double ctr = impressions > 0 ? (clicks * 100.0) / impressions : 0;
        double cpc = clicks > 0 ? spend / clicks : 0;

        return AdPortfolioVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .name(entity.getName())
                .state(entity.getState())
                .budgetType(entity.getBudgetType())
                .budget(entity.getBudget() != null ? entity.getBudget().doubleValue() : null)
                .startDate(entity.getStartDate())
                .endDate(entity.getEndDate())
                .externalId(entity.getExternalId())
                .campaignCount(campaignCount)
                .impressions(impressions)
                .clicks(clicks)
                .ctr(ctr)
                .spend(spend)
                .cpc(cpc)
                .orders(orders)
                .sales(sales)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }
}
