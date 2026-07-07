package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.AiNotificationConfigEntity;
import com.adpilot.modules.advertising.entity.AiNotificationEntity;
import com.adpilot.modules.advertising.vo.AiNotificationConfigVo;
import com.adpilot.modules.advertising.vo.AiNotificationVo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.format.DateTimeFormatter;

/**
 * Maps AI Notification entities to their VOs, parsing the stored JSON blobs
 * ({@code detail_json}, {@code config_json}) into structured nodes for
 * rendering (Req 23). A malformed blob never breaks the response — the field is
 * rendered as {@code null} instead.
 */
@Slf4j
public final class AiNotificationConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AiNotificationConverter() {
        // Utility class.
    }

    public static AiNotificationVo toVo(AiNotificationEntity entity, ObjectMapper objectMapper) {
        if (entity == null) {
            return null;
        }
        return AiNotificationVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .category(entity.getCategory())
                .title(entity.getTitle())
                .detail(parseJson(entity.getDetailJson(), objectMapper, entity.getId()))
                .subjectId(entity.getSubjectId())
                .state(entity.getState())
                .resolution(entity.getResolution())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .closedAt(entity.getClosedAt() != null ? entity.getClosedAt().format(FORMATTER) : null)
                .build();
    }

    public static AiNotificationConfigVo toConfigVo(AiNotificationConfigEntity entity, ObjectMapper objectMapper) {
        if (entity == null) {
            return null;
        }
        return AiNotificationConfigVo.builder()
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .config(parseJson(entity.getConfigJson(), objectMapper, entity.getId()))
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private static JsonNode parseJson(String json, ObjectMapper objectMapper, Object id) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            log.warn("Failed to parse AI notification JSON for {}: {}", id, ex.getMessage());
            return null;
        }
    }
}
