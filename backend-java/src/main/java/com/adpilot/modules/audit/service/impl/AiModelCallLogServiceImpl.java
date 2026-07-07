package com.adpilot.modules.audit.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.audit.entity.AiModelCallLogEntity;
import com.adpilot.modules.audit.mapper.AiModelCallLogMapper;
import com.adpilot.modules.audit.service.AiModelCallLogService;
import com.adpilot.modules.audit.vo.AiModelCallLogVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelCallLogServiceImpl implements AiModelCallLogService {

    private final AiModelCallLogMapper aiModelCallLogMapper;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    @Transactional
    public void createLog(String userId, String storeId, String feature, String model,
                          String promptSnapshot, String inputSnapshot, String outputSnapshot,
                          String status, String errorMessage) {
        AiModelCallLogEntity entity = AiModelCallLogEntity.builder()
                .userId(userId != null ? UUID.fromString(userId) : null)
                .storeId(storeId != null ? UUID.fromString(storeId) : null)
                .feature(feature)
                .model(model)
                .promptSnapshot(promptSnapshot)
                .inputSnapshot(inputSnapshot)
                .outputSnapshot(outputSnapshot)
                .status(status != null ? status : "success")
                .errorMessage(errorMessage)
                .build();

        aiModelCallLogMapper.insert(entity);
        log.debug("AI模型调用日志已记录: feature={}, model={}, status={}", feature, model, status);
    }

    @Override
    public PageResponse<AiModelCallLogVo> listAiModelCallLogs(String storeId, String feature, String model, int page, int pageSize) {
        Page<AiModelCallLogEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<AiModelCallLogEntity> wrapper = new LambdaQueryWrapper<>();

        if (storeId != null && !storeId.isEmpty()) {
            wrapper.eq(AiModelCallLogEntity::getStoreId, UUID.fromString(storeId));
        }
        if (feature != null && !feature.isEmpty()) {
            wrapper.eq(AiModelCallLogEntity::getFeature, feature);
        }
        if (model != null && !model.isEmpty()) {
            wrapper.eq(AiModelCallLogEntity::getModel, model);
        }
        wrapper.orderByDesc(AiModelCallLogEntity::getCreatedAt);

        Page<AiModelCallLogEntity> result = aiModelCallLogMapper.selectPage(pageParam, wrapper);
        List<AiModelCallLogVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    private AiModelCallLogVo toVo(AiModelCallLogEntity entity) {
        return AiModelCallLogVo.builder()
                .id(entity.getId().toString())
                .userId(entity.getUserId() != null ? entity.getUserId().toString() : null)
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .feature(entity.getFeature())
                .model(entity.getModel())
                .promptSnapshot(entity.getPromptSnapshot())
                .inputSnapshot(entity.getInputSnapshot())
                .outputSnapshot(entity.getOutputSnapshot())
                .status(entity.getStatus())
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }
}
