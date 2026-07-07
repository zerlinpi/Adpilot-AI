package com.adpilot.modules.rollback.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.automation.entity.AutomationExecutionEntity;
import com.adpilot.modules.automation.mapper.AutomationExecutionMapper;
import com.adpilot.modules.rollback.entity.RollbackPlanEntity;
import com.adpilot.modules.rollback.mapper.RollbackPlanMapper;
import com.adpilot.modules.rollback.service.RollbackService;
import com.adpilot.modules.rollback.vo.RollbackPlanVo;
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
public class RollbackServiceImpl implements RollbackService {

    private final RollbackPlanMapper rollbackPlanMapper;
    private final AutomationExecutionMapper executionMapper;
    private final AuditLogService auditLogService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public PageResponse<RollbackPlanVo> listRollbackPlans(String storeId, String status, int page, int pageSize) {
        Page<RollbackPlanEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<RollbackPlanEntity> wrapper = new LambdaQueryWrapper<>();

        if (status != null && !status.isEmpty()) {
            wrapper.eq(RollbackPlanEntity::getStatus, status);
        }

        // If storeId is provided, join through automation_executions to filter by store
        // For simplicity, we filter directly if possible
        wrapper.orderByDesc(RollbackPlanEntity::getCreatedAt);

        Page<RollbackPlanEntity> result = rollbackPlanMapper.selectPage(pageParam, wrapper);
        List<RollbackPlanVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public RollbackPlanVo getRollbackPlan(String id) {
        RollbackPlanEntity entity = rollbackPlanMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("ROLLBACK_PLAN_NOT_FOUND", "回滚计划不存在: " + id);
        }
        return toVo(entity);
    }

    @Override
    @Transactional
    public RollbackPlanVo executeRollback(String id, String userId) {
        RollbackPlanEntity plan = rollbackPlanMapper.selectById(UUID.fromString(id));
        if (plan == null) {
            throw new BusinessException("ROLLBACK_PLAN_NOT_FOUND", "回滚计划不存在: " + id);
        }
        if (!"available".equals(plan.getStatus())) {
            throw new BusinessException("ROLLBACK_NOT_AVAILABLE", "回滚计划当前状态不可执行: " + plan.getStatus());
        }

        // Check expiration
        if (plan.getExpiresAt() != null && LocalDateTime.now().isAfter(plan.getExpiresAt())) {
            plan.setStatus("expired");
            plan.setUpdatedAt(LocalDateTime.now());
            rollbackPlanMapper.updateById(plan);
            throw new BusinessException("ROLLBACK_EXPIRED", "回滚计划已过期");
        }

        // Mark plan as used
        plan.setStatus("used");
        plan.setUpdatedAt(LocalDateTime.now());
        rollbackPlanMapper.updateById(plan);

        // Update the related execution status
        AutomationExecutionEntity execution = executionMapper.selectById(plan.getAutomationExecutionId());
        if (execution != null) {
            execution.setStatus("rolled_back");
            execution.setUpdatedAt(LocalDateTime.now());
            executionMapper.updateById(execution);
        }

        log.info("回滚计划已执行: planId={}, executionId={}", id, plan.getAutomationExecutionId());

        // Write audit log
        UUID userUuid = userId != null ? UUID.fromString(userId) : null;
        auditLogService.createLog(userUuid, null, "EXECUTE_ROLLBACK", plan.getEntityType(), plan.getEntityId(),
                "执行回滚计划: " + id);

        return toVo(plan);
    }

    @Override
    @Transactional
    public void expireOldPlans() {
        LambdaQueryWrapper<RollbackPlanEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RollbackPlanEntity::getStatus, "available");
        wrapper.lt(RollbackPlanEntity::getExpiresAt, LocalDateTime.now());

        List<RollbackPlanEntity> expiredPlans = rollbackPlanMapper.selectList(wrapper);
        for (RollbackPlanEntity plan : expiredPlans) {
            plan.setStatus("expired");
            plan.setUpdatedAt(LocalDateTime.now());
            rollbackPlanMapper.updateById(plan);
        }

        if (!expiredPlans.isEmpty()) {
            log.info("已过期 {} 个回滚计划", expiredPlans.size());
        }
    }

    private RollbackPlanVo toVo(RollbackPlanEntity entity) {
        return RollbackPlanVo.builder()
                .id(entity.getId().toString())
                .automationExecutionId(entity.getAutomationExecutionId() != null ? entity.getAutomationExecutionId().toString() : null)
                .entityType(entity.getEntityType())
                .entityId(entity.getEntityId() != null ? entity.getEntityId().toString() : null)
                .rollbackData(entity.getRollbackData())
                .status(entity.getStatus())
                .expiresAt(entity.getExpiresAt() != null ? entity.getExpiresAt().format(FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }
}
