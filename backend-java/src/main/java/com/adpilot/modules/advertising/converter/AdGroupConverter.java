package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.AdGroupEntity;
import com.adpilot.modules.advertising.vo.AdGroupVo;

import java.time.format.DateTimeFormatter;

public class AdGroupConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Builds the base VO from the entity. Relational/aggregated fields
     * ({@code campaignName}, {@code keywordCount}, and the performance metrics)
     * are enriched by the service layer after this base mapping.
     */
    public static AdGroupVo toVo(AdGroupEntity entity) {
        if (entity == null) return null;
        return AdGroupVo.builder()
                .id(entity.getId().toString())
                .campaignId(entity.getCampaignId() != null ? entity.getCampaignId().toString() : null)
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .name(entity.getName())
                .status(AdvertisingEnumEmitter.objectStatus(entity.getStatus()))
                .defaultBid(entity.getDefaultBid() != null ? entity.getDefaultBid().doubleValue() : 0)
                .externalId(entity.getExternalId())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }
}
