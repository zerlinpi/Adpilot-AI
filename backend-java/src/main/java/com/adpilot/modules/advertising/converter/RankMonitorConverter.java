package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.RankMonitorSnapshotEntity;
import com.adpilot.modules.advertising.entity.RankMonitorTaskEntity;
import com.adpilot.modules.advertising.vo.RankMonitorTaskVo;

import java.time.format.DateTimeFormatter;

/**
 * Maps a {@link RankMonitorTaskEntity} (plus its most recent
 * {@link RankMonitorSnapshotEntity}, when present) to a
 * {@link RankMonitorTaskVo} for rendering on the Rank Monitoring page
 * (Req 28.2).
 */
public final class RankMonitorConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private RankMonitorConverter() {
        // Utility class.
    }

    public static RankMonitorTaskVo toVo(RankMonitorTaskEntity entity, RankMonitorSnapshotEntity latest) {
        return toVo(entity, latest, null, null);
    }

    public static RankMonitorTaskVo toVo(RankMonitorTaskEntity entity, RankMonitorSnapshotEntity latest,
                                         String storeName, String productName) {
        if (entity == null) {
            return null;
        }
        return RankMonitorTaskVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .storeName(storeName)
                .productId(entity.getProductId() != null ? entity.getProductId().toString() : null)
                .productName(productName)
                .keywordText(entity.getKeywordText())
                .status(entity.getStatus())
                .organicRank(latest != null ? latest.getOrganicRank() : null)
                .adRank(latest != null ? latest.getAdRank() : null)
                .lastCapturedAt(latest != null && latest.getCapturedAt() != null
                        ? latest.getCapturedAt().format(FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }
}
