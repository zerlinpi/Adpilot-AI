package com.adpilot.modules.task.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.task.dto.OperationTaskDto;
import com.adpilot.modules.task.entity.OperationTaskEntity;
import com.adpilot.modules.task.mapper.OperationTaskMapper;
import com.adpilot.modules.task.service.OperationTaskService;
import com.adpilot.modules.task.vo.OperationTaskVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OperationTaskServiceImpl implements OperationTaskService {

    private final OperationTaskMapper taskMapper;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public PageResponse<OperationTaskVo> listTasks(int page, int pageSize,
                                                   String storeId, String status,
                                                   String priority, String taskType) {
        Page<OperationTaskEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<OperationTaskEntity> wrapper = new LambdaQueryWrapper<>();
        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq(OperationTaskEntity::getStoreId, UUID.fromString(storeId.trim()));
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(OperationTaskEntity::getStatus, status.trim());
        }
        if (priority != null && !priority.isBlank()) {
            wrapper.eq(OperationTaskEntity::getPriority, priority.trim());
        }
        if (taskType != null && !taskType.isBlank()) {
            wrapper.eq(OperationTaskEntity::getTaskType, taskType.trim());
        }
        wrapper.orderByDesc(OperationTaskEntity::getCreatedAt);

        Page<OperationTaskEntity> result = taskMapper.selectPage(pageParam, wrapper);
        List<OperationTaskVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public OperationTaskVo getTaskById(String id) {
        OperationTaskEntity entity = taskMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("TASK_NOT_FOUND", "Task not found: " + id);
        }
        return toVo(entity);
    }

    @Override
    @Transactional
    public OperationTaskVo createTask(OperationTaskDto dto, String userId) {
        OperationTaskEntity entity = OperationTaskEntity.builder()
                .storeId(dto.getStoreId() != null ? UUID.fromString(dto.getStoreId()) : null)
                .title(dto.getTitle())
                .description(dto.getDescription())
                .taskType(dto.getTaskType())
                .sourceType(dto.getSourceType() != null ? dto.getSourceType() : "manual")
                .relatedEntityType(dto.getRelatedEntityType())
                .relatedEntityId(dto.getRelatedEntityId() != null ? UUID.fromString(dto.getRelatedEntityId()) : null)
                .priority(dto.getPriority() != null ? dto.getPriority() : "medium")
                .riskLevel(dto.getRiskLevel() != null ? dto.getRiskLevel() : "low")
                .status(dto.getStatus() != null ? dto.getStatus() : "open")
                .assignedToUserId(dto.getAssignedToUserId() != null ? UUID.fromString(dto.getAssignedToUserId()) : null)
                .dueDate(dto.getDueDate())
                .expectedImpact(dto.getExpectedImpact())
                .suggestedAction(dto.getSuggestedAction())
                .approvalRequired(dto.getApprovalRequired() != null ? dto.getApprovalRequired() : false)
                .createdBy(userId != null ? UUID.fromString(userId) : null)
                .updatedBy(userId != null ? UUID.fromString(userId) : null)
                .build();

        taskMapper.insert(entity);
        log.info("Task created: id={}, title={}", entity.getId(), entity.getTitle());
        return toVo(entity);
    }

    @Override
    @Transactional
    public OperationTaskVo updateTask(String id, OperationTaskDto dto, String userId) {
        OperationTaskEntity entity = taskMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("TASK_NOT_FOUND", "Task not found: " + id);
        }

        if (dto.getTitle() != null) {
            entity.setTitle(dto.getTitle());
        }
        if (dto.getDescription() != null) {
            entity.setDescription(dto.getDescription());
        }
        if (dto.getTaskType() != null) {
            entity.setTaskType(dto.getTaskType());
        }
        if (dto.getSourceType() != null) {
            entity.setSourceType(dto.getSourceType());
        }
        if (dto.getRelatedEntityType() != null) {
            entity.setRelatedEntityType(dto.getRelatedEntityType());
        }
        if (dto.getRelatedEntityId() != null) {
            entity.setRelatedEntityId(UUID.fromString(dto.getRelatedEntityId()));
        }
        if (dto.getPriority() != null) {
            entity.setPriority(dto.getPriority());
        }
        if (dto.getRiskLevel() != null) {
            entity.setRiskLevel(dto.getRiskLevel());
        }
        if (dto.getStatus() != null) {
            entity.setStatus(dto.getStatus());
        }
        if (dto.getAssignedToUserId() != null) {
            entity.setAssignedToUserId(UUID.fromString(dto.getAssignedToUserId()));
        }
        if (dto.getDueDate() != null) {
            entity.setDueDate(dto.getDueDate());
        }
        if (dto.getExpectedImpact() != null) {
            entity.setExpectedImpact(dto.getExpectedImpact());
        }
        if (dto.getSuggestedAction() != null) {
            entity.setSuggestedAction(dto.getSuggestedAction());
        }
        if (dto.getApprovalRequired() != null) {
            entity.setApprovalRequired(dto.getApprovalRequired());
        }
        if (dto.getStoreId() != null) {
            entity.setStoreId(UUID.fromString(dto.getStoreId()));
        }

        entity.setUpdatedBy(userId != null ? UUID.fromString(userId) : null);
        entity.setUpdatedAt(LocalDateTime.now());

        taskMapper.updateById(entity);
        log.info("Task updated: id={}", id);
        return toVo(entity);
    }

    @Override
    @Transactional
    public OperationTaskVo completeTask(String id, String userId) {
        OperationTaskEntity entity = taskMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("TASK_NOT_FOUND", "Task not found: " + id);
        }

        entity.setStatus("completed");
        entity.setCompletedAt(LocalDateTime.now());
        entity.setUpdatedBy(userId != null ? UUID.fromString(userId) : null);
        entity.setUpdatedAt(LocalDateTime.now());

        taskMapper.updateById(entity);
        log.info("Task completed: id={}", id);
        return toVo(entity);
    }

    @Override
    @Transactional
    public OperationTaskVo dismissTask(String id, String userId) {
        OperationTaskEntity entity = taskMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("TASK_NOT_FOUND", "Task not found: " + id);
        }

        entity.setStatus("dismissed");
        entity.setUpdatedBy(userId != null ? UUID.fromString(userId) : null);
        entity.setUpdatedAt(LocalDateTime.now());

        taskMapper.updateById(entity);
        log.info("Task dismissed: id={}", id);
        return toVo(entity);
    }

    @Override
    @Transactional
    public OperationTaskVo assignTask(String id, String assignedToUserId, String userId) {
        if (assignedToUserId == null || assignedToUserId.isBlank()) {
            throw new BusinessException("TASK_ASSIGNEE_REQUIRED", "Assignee userId is required");
        }

        OperationTaskEntity entity = taskMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("TASK_NOT_FOUND", "Task not found: " + id);
        }

        entity.setAssignedToUserId(UUID.fromString(assignedToUserId.trim()));
        entity.setUpdatedBy(userId != null ? UUID.fromString(userId) : null);
        entity.setUpdatedAt(LocalDateTime.now());

        taskMapper.updateById(entity);
        log.info("Task assigned: id={}, assignedToUserId={}", id, assignedToUserId);
        return toVo(entity);
    }

    private OperationTaskVo toVo(OperationTaskEntity entity) {
        return OperationTaskVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .title(entity.getTitle())
                .description(entity.getDescription())
                .taskType(entity.getTaskType())
                .sourceType(entity.getSourceType())
                .relatedEntityType(entity.getRelatedEntityType())
                .relatedEntityId(entity.getRelatedEntityId() != null ? entity.getRelatedEntityId().toString() : null)
                .priority(entity.getPriority())
                .riskLevel(entity.getRiskLevel())
                .status(entity.getStatus())
                .assignedToUserId(entity.getAssignedToUserId() != null ? entity.getAssignedToUserId().toString() : null)
                .dueDate(entity.getDueDate() != null ? entity.getDueDate().format(FORMATTER) : null)
                .expectedImpact(entity.getExpectedImpact())
                .suggestedAction(entity.getSuggestedAction())
                .approvalRequired(entity.getApprovalRequired())
                .completedAt(entity.getCompletedAt() != null ? entity.getCompletedAt().format(FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }
}
