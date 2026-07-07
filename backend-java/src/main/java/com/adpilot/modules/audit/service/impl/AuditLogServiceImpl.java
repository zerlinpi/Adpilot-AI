package com.adpilot.modules.audit.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.audit.entity.AuditLogEntity;
import com.adpilot.modules.audit.mapper.AuditLogMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.audit.support.AuditLabelTranslator;
import com.adpilot.modules.audit.vo.AuditLogVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    @Transactional
    public void createLog(UUID userId, UUID orgId, String action, String entityType, UUID entityId, Object details) {
        String detailsJson = null;
        if (details != null) {
            try {
                detailsJson = objectMapper.writeValueAsString(details);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize audit details: {}", e.getMessage());
                detailsJson = details.toString();
            }
        }

        AuditLogEntity entity = AuditLogEntity.builder()
                .userId(userId)
                .orgId(orgId)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .newData(detailsJson)
                .source("app")
                .build();

        auditLogMapper.insert(entity);
        log.debug("Audit log created: action={}, entityType={}, entityId={}", action, entityType, entityId);
    }

    @Override
    public PageResponse<AuditLogVo> listAuditLogs(String action, String entityType, int page, int pageSize) {
        Page<AuditLogEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<AuditLogEntity> wrapper = new LambdaQueryWrapper<>();
        // Case-insensitive filter matching (Requirement 11.6). The UI sends
        // canonical lower-case codes while stored codes may be upper-case.
        if (action != null && !action.isBlank()) {
            wrapper.apply("LOWER(action) = LOWER({0})", action.trim());
        }
        if (entityType != null && !entityType.isBlank()) {
            wrapper.apply("LOWER(entity_type) = LOWER({0})", entityType.trim());
        }
        wrapper.orderByDesc(AuditLogEntity::getCreatedAt);

        Page<AuditLogEntity> result = auditLogMapper.selectPage(pageParam, wrapper);
        List<AuditLogVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    private AuditLogVo toVo(AuditLogEntity entity) {
        return AuditLogVo.builder()
                .id(entity.getId().toString())
                .userId(entity.getUserId() != null ? entity.getUserId().toString() : null)
                .orgId(entity.getOrgId() != null ? entity.getOrgId().toString() : null)
                .action(entity.getAction())
                .actionLabel(AuditLabelTranslator.actionLabel(entity.getAction()))
                .entityType(entity.getEntityType())
                .entityTypeLabel(AuditLabelTranslator.entityTypeLabel(entity.getEntityType()))
                .entityId(entity.getEntityId() != null ? entity.getEntityId().toString() : null)
                .oldData(entity.getOldData())
                .newData(entity.getNewData())
                .ipAddress(entity.getIpAddress())
                .source(entity.getSource())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }
}
