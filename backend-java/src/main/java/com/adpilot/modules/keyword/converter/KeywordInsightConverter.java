package com.adpilot.modules.keyword.converter;

import com.adpilot.modules.keyword.entity.KeywordInsightEntity;
import com.adpilot.modules.keyword.vo.KeywordInsightVo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class KeywordInsightConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private KeywordInsightConverter() {
        // Utility class
    }

    public static KeywordInsightVo toVo(KeywordInsightEntity entity) {
        if (entity == null) {
            return null;
        }

        Object currentData = null;
        if (entity.getCurrentData() != null && !entity.getCurrentData().isEmpty()) {
            try {
                currentData = objectMapper.readValue(entity.getCurrentData(), Object.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse currentData JSON for insight {}: {}", entity.getId(), e.getMessage());
                currentData = entity.getCurrentData();
            }
        }

        return KeywordInsightVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .text(entity.getText())
                .source(entity.getSource())
                .segment(entity.getSegment())
                .healthScore(entity.getHealthScore() != null ? entity.getHealthScore() : 0)
                .opportunityScore(entity.getOpportunityScore() != null ? entity.getOpportunityScore() : 0)
                .wasteScore(entity.getWasteScore() != null ? entity.getWasteScore() : 0)
                .confidenceScore(entity.getConfidenceScore() != null ? entity.getConfidenceScore() : 0)
                .recommendedAction(entity.getRecommendedAction())
                .reason(entity.getReason())
                .currentData(currentData)
                .expectedImpact(entity.getExpectedImpact())
                .riskLevel(entity.getRiskLevel())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }

    public static List<KeywordInsightVo> toVoList(List<KeywordInsightEntity> entities) {
        if (entities == null) {
            return List.of();
        }
        return entities.stream()
                .map(KeywordInsightConverter::toVo)
                .collect(Collectors.toList());
    }
}
