package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.NegativeKeywordEntity;
import com.adpilot.modules.advertising.vo.NegativeKeywordVo;

import java.time.format.DateTimeFormatter;

public class NegativeKeywordConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Builds the base VO from the entity. The parent-campaign name is enriched
     * by the service layer after this base mapping.
     */
    public static NegativeKeywordVo toVo(NegativeKeywordEntity entity) {
        if (entity == null) return null;
        return NegativeKeywordVo.builder()
                .id(entity.getId().toString())
                .campaignId(entity.getCampaignId() != null ? entity.getCampaignId().toString() : null)
                .adGroupId(entity.getAdGroupId() != null ? entity.getAdGroupId().toString() : null)
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .keywordText(entity.getKeywordText())
                .matchType(entity.getMatchType())
                .level(entity.getLevel())
                .source(entity.getSource())
                .status(AdvertisingEnumEmitter.objectStatus(entity.getStatus()))
                .externalId(entity.getExternalId())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }
}
