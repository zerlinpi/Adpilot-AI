package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.RecommendationEntity;
import com.adpilot.modules.advertising.vo.RecommendationVo;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.format.DateTimeFormatter;
import java.util.Map;

public class RecommendationConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Parse the stored current_data JSON string into a map so the UI can iterate it. */
    private static Object parseCurrentData(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    public static RecommendationVo toVo(RecommendationEntity entity) {
        if (entity == null) return null;
        return RecommendationVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .campaignId(entity.getCampaignId() != null ? entity.getCampaignId().toString() : null)
                .keywordId(entity.getKeywordId() != null ? entity.getKeywordId().toString() : null)
                .targetId(entity.getTargetId() != null ? entity.getTargetId().toString() : null)
                .type(entity.getType())
                .priority(entity.getPriority())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .reason(entity.getReason())
                .expectedImpact(entity.getExpectedImpact())
                .riskLevel(entity.getRiskLevel())
                .targetEntityType(entity.getTargetEntityType())
                .targetEntityName(entity.getTargetEntityName())
                .currentData(parseCurrentData(entity.getCurrentData()))
                .currentValue(entity.getCurrentValue())
                .recommendedValue(entity.getRecommendedValue())
                .estimatedImpact(entity.getEstimatedImpact() != null ? entity.getEstimatedImpact().doubleValue() : 0)
                .confidence(entity.getConfidence() != null ? entity.getConfidence().doubleValue() : 0)
                .status(entity.getStatus())
                .appliedAt(entity.getAppliedAt() != null ? entity.getAppliedAt().format(FORMATTER) : null)
                .dismissedAt(entity.getDismissedAt() != null ? entity.getDismissedAt().format(FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }
}
