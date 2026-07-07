package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.BidChangeEntity;
import com.adpilot.modules.advertising.vo.BidChangeVo;

import java.time.format.DateTimeFormatter;

public class BidChangeConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Builds the base VO from the entity. The parent-campaign name is enriched
     * by the service layer after this base mapping.
     */
    public static BidChangeVo toVo(BidChangeEntity entity) {
        if (entity == null) return null;
        return BidChangeVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .campaignId(entity.getCampaignId() != null ? entity.getCampaignId().toString() : null)
                .keywordId(entity.getKeywordId() != null ? entity.getKeywordId().toString() : null)
                .targetId(entity.getTargetId() != null ? entity.getTargetId().toString() : null)
                .entityType(entity.getEntityType())
                .oldBid(entity.getOldBid() != null ? entity.getOldBid().doubleValue() : null)
                .newBid(entity.getNewBid() != null ? entity.getNewBid().doubleValue() : null)
                .changeReason(entity.getChangeReason())
                .automated(Boolean.TRUE.equals(entity.getIsAutomated()))
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }
}
