package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.SmartDiagnosisTaskEntity;
import com.adpilot.modules.advertising.vo.SmartDiagnosisTaskVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.format.DateTimeFormatter;

/**
 * Maps a {@link SmartDiagnosisTaskEntity} to a {@link SmartDiagnosisTaskVo},
 * deserializing the stored {@code result_json} into the structured
 * {@link SmartDiagnosisTaskVo.DiagnosisResult} for rendering (Req 22.2, 22.3).
 */
@Slf4j
public class SmartDiagnosisConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SmartDiagnosisConverter() {
        // Utility class.
    }

    public static SmartDiagnosisTaskVo toVo(SmartDiagnosisTaskEntity entity, ObjectMapper objectMapper) {
        if (entity == null) {
            return null;
        }

        SmartDiagnosisTaskVo.DiagnosisResult result = null;
        if (entity.getResultJson() != null && !entity.getResultJson().isBlank()) {
            try {
                result = objectMapper.readValue(entity.getResultJson(),
                        SmartDiagnosisTaskVo.DiagnosisResult.class);
            } catch (Exception ex) {
                // A malformed result must not break the listing; render the row
                // without a result rather than failing the whole request.
                log.warn("Failed to parse diagnosis result for task {}: {}",
                        entity.getId(), ex.getMessage());
            }
        }

        return SmartDiagnosisTaskVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .parentAsin(entity.getParentAsin())
                .updateFrequency(entity.getUpdateFrequency())
                .status(entity.getStatus())
                .createdBy(entity.getCreatedBy() != null ? entity.getCreatedBy().toString() : null)
                .lastDiagnosedAt(entity.getLastDiagnosedAt() != null
                        ? entity.getLastDiagnosedAt().format(FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .result(result)
                .build();
    }
}
