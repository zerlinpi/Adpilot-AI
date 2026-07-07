package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.CreativeAssetEntity;
import com.adpilot.modules.advertising.support.CreativeAssetView;
import com.adpilot.modules.advertising.vo.CreativeAssetVo;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps a {@link CreativeAssetEntity} (plus its resolved creator display name) to
 * a {@link CreativeAssetVo} for rendering on the Creative Asset Library page
 * (Req 29.1), and to a database-free {@link CreativeAssetView} for the pure
 * {@link com.adpilot.modules.advertising.support.CreativeAssetFilter} search.
 */
public final class CreativeAssetConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private CreativeAssetConverter() {
        // Utility class.
    }

    public static CreativeAssetVo toVo(CreativeAssetEntity entity, String creator) {
        if (entity == null) {
            return null;
        }
        return CreativeAssetVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .name(entity.getName())
                .assetType(entity.getAssetType())
                .mediaKind(entity.getMediaKind())
                .storageUrl(entity.getStorageUrl())
                .asin(entity.getAsin())
                .tags(safeTags(entity.getTags()))
                .creator(creator)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }

    public static CreativeAssetView toView(CreativeAssetEntity entity, String creator) {
        if (entity == null) {
            return null;
        }
        return new CreativeAssetView(
                entity.getStoreId() != null ? entity.getStoreId().toString() : null,
                entity.getName(),
                entity.getAssetType(),
                entity.getAsin(),
                safeTags(entity.getTags()),
                creator);
    }

    private static List<String> safeTags(List<String> tags) {
        return tags != null ? tags : new ArrayList<>();
    }
}
