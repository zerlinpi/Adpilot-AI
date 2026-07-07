package com.adpilot.modules.keyword.converter;

import com.adpilot.modules.keyword.entity.KeywordLibraryEntity;
import com.adpilot.modules.keyword.entity.KeywordLibraryItemEntity;
import com.adpilot.modules.keyword.vo.KeywordLibraryVo;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Maps a {@link KeywordLibraryEntity} (plus its items) to a
 * {@link KeywordLibraryVo}, deriving the keyword count (关键词数量) and the
 * distinct associated-product count (关联商品) from the library items (Req 27.2).
 */
public final class KeywordLibraryConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private KeywordLibraryConverter() {
        // Utility class.
    }

    public static KeywordLibraryVo toVo(KeywordLibraryEntity entity, List<KeywordLibraryItemEntity> items) {
        if (entity == null) {
            return null;
        }

        int keywordCount = 0;
        int associatedProductCount = 0;
        if (items != null) {
            keywordCount = items.size();
            associatedProductCount = (int) items.stream()
                    .map(KeywordLibraryItemEntity::getProductId)
                    .filter(Objects::nonNull)
                    .map(UUID::toString)
                    .distinct()
                    .count();
        }

        return KeywordLibraryVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .name(entity.getName())
                .libraryType(entity.getLibraryType())
                .scheduleCron(entity.getScheduleCron())
                .keywordCount(keywordCount)
                .associatedProductCount(associatedProductCount)
                .lastRunAt(entity.getLastRunAt() != null ? entity.getLastRunAt().format(FORMATTER) : null)
                .nextRunAt(entity.getNextRunAt() != null ? entity.getNextRunAt().format(FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }

    public static List<KeywordLibraryVo> toVoList(List<KeywordLibraryEntity> entities,
                                                  java.util.Map<UUID, List<KeywordLibraryItemEntity>> itemsByLibrary) {
        if (entities == null) {
            return List.of();
        }
        return entities.stream()
                .map(e -> toVo(e, itemsByLibrary.getOrDefault(e.getId(), List.of())))
                .collect(Collectors.toList());
    }
}
