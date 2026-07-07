package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.SearchTermEntity;
import com.adpilot.modules.advertising.vo.SearchTermVo;

import java.time.format.DateTimeFormatter;

public class SearchTermConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static SearchTermVo toVo(SearchTermEntity entity) {
        return toVo(entity, null, null);
    }

    /**
     * Convert a Search_Term to its canonical VO, stamping the reporting period the metrics cover
     * (Req 14.3). {@code periodStart}/{@code periodEnd} are supplied by the caller (the query's
     * date-range filter) because they describe the request window, not a stored entity column; pass
     * {@code null} when no period is known.
     */
    public static SearchTermVo toVo(SearchTermEntity entity, String periodStart, String periodEnd) {
        if (entity == null) return null;
        return SearchTermVo.builder()
                .id(entity.getId().toString())
                .campaignId(entity.getCampaignId().toString())
                .adGroupId(entity.getAdGroupId() != null ? entity.getAdGroupId().toString() : null)
                .keywordId(entity.getKeywordId() != null ? entity.getKeywordId().toString() : null)
                .storeId(entity.getStoreId().toString())
                .searchTerm(entity.getSearchTerm())
                .impressions(entity.getImpressions() != null ? entity.getImpressions() : 0)
                .clicks(entity.getClicks() != null ? entity.getClicks() : 0)
                .spend(entity.getSpend() != null ? entity.getSpend().doubleValue() : 0)
                .sales(entity.getSales() != null ? entity.getSales().doubleValue() : 0)
                .orders(entity.getOrders() != null ? entity.getOrders() : 0)
                .acos(entity.getAcos() != null ? entity.getAcos().doubleValue() : 0)
                .ctr(entity.getCtr() != null ? entity.getCtr().doubleValue() : 0)
                .cvr(entity.getCvr() != null ? entity.getCvr().doubleValue() : 0)
                .cpc(entity.getAvgCpc() != null ? entity.getAvgCpc().doubleValue() : 0)
                .roas(entity.getRoas() != null ? entity.getRoas().doubleValue() : 0)
                .harvested(Boolean.TRUE.equals(entity.getHarvested()))
                .harvestingStatus(entity.getHarvestingStatus())
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }
}
