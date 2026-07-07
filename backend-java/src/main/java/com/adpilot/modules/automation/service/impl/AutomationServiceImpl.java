package com.adpilot.modules.automation.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.automation.dto.AutomationPolicyDto;
import com.adpilot.modules.automation.dto.RiskEvaluateDto;
import com.adpilot.modules.automation.entity.AutomationExecutionEntity;
import com.adpilot.modules.automation.entity.AutomationPolicyEntity;
import com.adpilot.modules.automation.entity.RiskEvaluationEntity;
import com.adpilot.modules.automation.mapper.AutomationExecutionMapper;
import com.adpilot.modules.automation.mapper.AutomationPolicyMapper;
import com.adpilot.modules.automation.mapper.RiskEvaluationMapper;
import com.adpilot.modules.automation.service.AutomationService;
import com.adpilot.modules.automation.vo.AutomationExecutionVo;
import com.adpilot.modules.automation.vo.AutomationPolicyVo;
import com.adpilot.modules.automation.vo.RiskEvaluationVo;
import com.adpilot.modules.rollback.entity.RollbackPlanEntity;
import com.adpilot.modules.rollback.mapper.RollbackPlanMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationServiceImpl implements AutomationService {

    private final AutomationPolicyMapper policyMapper;
    private final AutomationExecutionMapper executionMapper;
    private final RiskEvaluationMapper riskEvaluationMapper;
    private final RollbackPlanMapper rollbackPlanMapper;
    private final AuditLogService auditLogService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public AutomationPolicyVo getAutomationPolicy(String storeId) {
        LambdaQueryWrapper<AutomationPolicyEntity> wrapper = new LambdaQueryWrapper<>();
        if (storeId != null && !storeId.isEmpty()) {
            wrapper.eq(AutomationPolicyEntity::getStoreId, UUID.fromString(storeId));
        } else {
            wrapper.isNull(AutomationPolicyEntity::getStoreId);
        }
        wrapper.last("LIMIT 1");

        AutomationPolicyEntity entity = policyMapper.selectOne(wrapper);
        if (entity == null) {
            // No policy configured yet — return null so the UI can show an empty
            // state rather than surfacing an error.
            return null;
        }
        return toPolicyVo(entity);
    }

    @Override
    @Transactional
    public AutomationPolicyVo createOrUpdatePolicy(AutomationPolicyDto dto) {
        UUID storeId = dto.getStoreId() != null ? UUID.fromString(dto.getStoreId()) : null;
        UUID orgId = UUID.fromString(dto.getOrgId());

        // Try to find existing policy
        LambdaQueryWrapper<AutomationPolicyEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AutomationPolicyEntity::getOrgId, orgId);
        if (storeId != null) {
            wrapper.eq(AutomationPolicyEntity::getStoreId, storeId);
        } else {
            wrapper.isNull(AutomationPolicyEntity::getStoreId);
        }
        wrapper.last("LIMIT 1");

        AutomationPolicyEntity entity = policyMapper.selectOne(wrapper);

        if (entity == null) {
            // Create new policy
            entity = AutomationPolicyEntity.builder()
                    .orgId(orgId)
                    .storeId(storeId)
                    .mode(dto.getMode() != null ? dto.getMode() : "manual")
                    .maxBidChangePct(dto.getMaxBidChangePct())
                    .maxBudgetChangePct(dto.getMaxBudgetChangePct())
                    .minClicksBeforeNegative(dto.getMinClicksBeforeNegative())
                    .blockBrandNegative(dto.getBlockBrandNegative())
                    .blockCompetitorInListing(dto.getBlockCompetitorInListing())
                    .inventoryThreshold(dto.getInventoryThreshold())
                    .minDaysOfSupplyToScale(dto.getMinDaysOfSupplyToScale())
                    .requireApprovalForHighRisk(dto.getRequireApprovalForHighRisk())
                    .requireApprovalForProductUpload(dto.getRequireApprovalForProductUpload())
                    .requireApprovalForReplenishment(dto.getRequireApprovalForReplenishment())
                    .dailyBudgetLimit(dto.getDailyBudgetLimit())
                    .build();
            policyMapper.insert(entity);
            log.info("自动化策略已创建: id={}, storeId={}", entity.getId(), storeId);

            auditLogService.createLog(null, orgId, "CREATE", "automation_policy", entity.getId(), dto);
        } else {
            // Update existing policy
            if (dto.getMode() != null) entity.setMode(dto.getMode());
            if (dto.getMaxBidChangePct() != null) entity.setMaxBidChangePct(dto.getMaxBidChangePct());
            if (dto.getMaxBudgetChangePct() != null) entity.setMaxBudgetChangePct(dto.getMaxBudgetChangePct());
            if (dto.getMinClicksBeforeNegative() != null) entity.setMinClicksBeforeNegative(dto.getMinClicksBeforeNegative());
            if (dto.getBlockBrandNegative() != null) entity.setBlockBrandNegative(dto.getBlockBrandNegative());
            if (dto.getBlockCompetitorInListing() != null) entity.setBlockCompetitorInListing(dto.getBlockCompetitorInListing());
            if (dto.getInventoryThreshold() != null) entity.setInventoryThreshold(dto.getInventoryThreshold());
            if (dto.getMinDaysOfSupplyToScale() != null) entity.setMinDaysOfSupplyToScale(dto.getMinDaysOfSupplyToScale());
            if (dto.getRequireApprovalForHighRisk() != null) entity.setRequireApprovalForHighRisk(dto.getRequireApprovalForHighRisk());
            if (dto.getRequireApprovalForProductUpload() != null) entity.setRequireApprovalForProductUpload(dto.getRequireApprovalForProductUpload());
            if (dto.getRequireApprovalForReplenishment() != null) entity.setRequireApprovalForReplenishment(dto.getRequireApprovalForReplenishment());
            if (dto.getDailyBudgetLimit() != null) entity.setDailyBudgetLimit(dto.getDailyBudgetLimit());
            entity.setUpdatedAt(LocalDateTime.now());

            policyMapper.updateById(entity);
            log.info("自动化策略已更新: id={}", entity.getId());

            auditLogService.createLog(null, orgId, "UPDATE", "automation_policy", entity.getId(), dto);
        }

        return toPolicyVo(entity);
    }

    @Override
    @Transactional
    public RiskEvaluationVo evaluateAction(RiskEvaluateDto dto) {
        UUID storeId = UUID.fromString(dto.getStoreId());
        String actionType = dto.getActionType();
        String entityType = dto.getEntityType();
        UUID entityId = dto.getEntityId() != null ? UUID.fromString(dto.getEntityId()) : null;

        // Get the policy for this store
        LambdaQueryWrapper<AutomationPolicyEntity> policyWrapper = new LambdaQueryWrapper<>();
        policyWrapper.eq(AutomationPolicyEntity::getStoreId, storeId);
        policyWrapper.last("LIMIT 1");
        AutomationPolicyEntity policy = policyWrapper != null ? policyMapper.selectOne(policyWrapper) : null;

        // Evaluate risk based on policy rules
        List<String> riskReasons = new ArrayList<>();
        String riskLevel = "low";
        boolean blocked = false;
        boolean requiresApproval = false;

        if (policy != null) {
            // Check mode
            if ("manual".equals(policy.getMode())) {
                riskLevel = "high";
                requiresApproval = true;
                riskReasons.add("当前策略为手动模式，所有操作需要审批");
            }

            // Check high-risk approval requirement
            if (Boolean.TRUE.equals(policy.getRequireApprovalForHighRisk())) {
                if ("bid_change".equals(actionType) || "budget_change".equals(actionType)) {
                    riskLevel = "medium";
                    requiresApproval = true;
                    riskReasons.add("出价/预算变更需要审批");
                }
            }

            // Check product upload approval
            if (Boolean.TRUE.equals(policy.getRequireApprovalForProductUpload()) && "product_upload".equals(actionType)) {
                riskLevel = "medium";
                requiresApproval = true;
                riskReasons.add("商品上传需要审批");
            }

            // Check replenishment approval
            if (Boolean.TRUE.equals(policy.getRequireApprovalForReplenishment()) && "replenishment".equals(actionType)) {
                riskLevel = "medium";
                requiresApproval = true;
                riskReasons.add("补货操作需要审批");
            }

            // Check brand negative keyword blocking
            if (Boolean.TRUE.equals(policy.getBlockBrandNegative()) && "add_negative_keyword".equals(actionType)) {
                riskReasons.add("品牌否定词被策略阻止");
                blocked = true;
                riskLevel = "high";
            }

            // Check competitor in listing blocking
            if (Boolean.TRUE.equals(policy.getBlockCompetitorInListing()) && "competitor_listing".equals(actionType)) {
                riskReasons.add("竞品Listing操作被策略阻止");
                blocked = true;
                riskLevel = "high";
            }

            // Autopilot mode - lower risk
            if ("autopilot".equals(policy.getMode())) {
                if (!blocked) {
                    riskLevel = "low";
                    requiresApproval = false;
                    riskReasons.clear();
                    riskReasons.add("自动巡航模式，自动执行");
                }
            }
        }

        // Build reasons JSON
        String reasonsJson;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            reasonsJson = mapper.writeValueAsString(riskReasons);
        } catch (Exception e) {
            reasonsJson = "[]";
        }

        // Save risk evaluation
        RiskEvaluationEntity evaluation = RiskEvaluationEntity.builder()
                .storeId(storeId)
                .actionType(actionType)
                .entityType(entityType)
                .entityId(entityId)
                .riskLevel(riskLevel)
                .reasons(reasonsJson)
                .blocked(blocked)
                .requiresApproval(requiresApproval)
                .build();
        riskEvaluationMapper.insert(evaluation);

        log.info("风险评估完成: storeId={}, action={}, riskLevel={}, blocked={}, requiresApproval={}",
                storeId, actionType, riskLevel, blocked, requiresApproval);

        auditLogService.createLog(null, null, "RISK_EVALUATE", entityType, entityId, evaluation);

        return toRiskEvaluationVo(evaluation);
    }

    @Override
    public PageResponse<AutomationExecutionVo> listExecutions(String storeId, String status, int page, int pageSize) {
        Page<AutomationExecutionEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<AutomationExecutionEntity> wrapper = new LambdaQueryWrapper<>();

        if (storeId != null && !storeId.isEmpty()) {
            wrapper.eq(AutomationExecutionEntity::getStoreId, UUID.fromString(storeId));
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(AutomationExecutionEntity::getStatus, status);
        }
        wrapper.orderByDesc(AutomationExecutionEntity::getCreatedAt);

        Page<AutomationExecutionEntity> result = executionMapper.selectPage(pageParam, wrapper);
        List<AutomationExecutionVo> voList = result.getRecords().stream()
                .map(this::toExecutionVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public AutomationExecutionVo getExecution(String id) {
        AutomationExecutionEntity entity = executionMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("EXECUTION_NOT_FOUND", "执行记录不存在: " + id);
        }
        return toExecutionVo(entity);
    }

    @Override
    @Transactional
    public AutomationExecutionVo rollbackExecution(String id, String userId) {
        AutomationExecutionEntity execution = executionMapper.selectById(UUID.fromString(id));
        if (execution == null) {
            throw new BusinessException("EXECUTION_NOT_FOUND", "执行记录不存在: " + id);
        }
        if ("rolled_back".equals(execution.getStatus())) {
            throw new BusinessException("ALREADY_ROLLED_BACK", "该执行记录已回滚");
        }
        if (!"completed".equals(execution.getStatus()) && !"failed".equals(execution.getStatus())) {
            throw new BusinessException("EXECUTION_NOT_ROLLBACKABLE", "当前状态不允许回滚: " + execution.getStatus());
        }

        // Find the rollback plan
        LambdaQueryWrapper<RollbackPlanEntity> planWrapper = new LambdaQueryWrapper<>();
        planWrapper.eq(RollbackPlanEntity::getAutomationExecutionId, UUID.fromString(id));
        planWrapper.eq(RollbackPlanEntity::getStatus, "available");
        planWrapper.last("LIMIT 1");
        RollbackPlanEntity rollbackPlan = rollbackPlanMapper.selectOne(planWrapper);

        if (rollbackPlan == null) {
            throw new BusinessException("ROLLBACK_PLAN_NOT_FOUND", "未找到可用的回滚计划");
        }

        // Mark execution as rolled back
        execution.setStatus("rolled_back");
        execution.setUpdatedAt(LocalDateTime.now());
        executionMapper.updateById(execution);

        // Mark rollback plan as used
        rollbackPlan.setStatus("used");
        rollbackPlan.setUpdatedAt(LocalDateTime.now());
        rollbackPlanMapper.updateById(rollbackPlan);

        log.info("执行记录已回滚: executionId={}, userId={}", id, userId);

        // Write audit log
        UUID userUuid = userId != null ? UUID.fromString(userId) : null;
        auditLogService.createLog(userUuid, null, "ROLLBACK", execution.getEntityType(), execution.getEntityId(),
                "回滚执行记录: " + id);

        return toExecutionVo(execution);
    }

    private AutomationPolicyVo toPolicyVo(AutomationPolicyEntity entity) {
        return AutomationPolicyVo.builder()
                .id(entity.getId().toString())
                .orgId(entity.getOrgId() != null ? entity.getOrgId().toString() : null)
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .mode(entity.getMode())
                .maxBidChangePct(entity.getMaxBidChangePct())
                .maxBudgetChangePct(entity.getMaxBudgetChangePct())
                .minClicksBeforeNegative(entity.getMinClicksBeforeNegative())
                .blockBrandNegative(entity.getBlockBrandNegative())
                .blockCompetitorInListing(entity.getBlockCompetitorInListing())
                .inventoryThreshold(entity.getInventoryThreshold())
                .minDaysOfSupplyToScale(entity.getMinDaysOfSupplyToScale())
                .requireApprovalForHighRisk(entity.getRequireApprovalForHighRisk())
                .requireApprovalForProductUpload(entity.getRequireApprovalForProductUpload())
                .requireApprovalForReplenishment(entity.getRequireApprovalForReplenishment())
                .dailyBudgetLimit(entity.getDailyBudgetLimit())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private AutomationExecutionVo toExecutionVo(AutomationExecutionEntity entity) {
        return AutomationExecutionVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .source(entity.getSource())
                .entityType(entity.getEntityType())
                .entityId(entity.getEntityId() != null ? entity.getEntityId().toString() : null)
                .actionType(entity.getActionType())
                .beforeSnapshot(entity.getBeforeSnapshot())
                .afterSnapshot(entity.getAfterSnapshot())
                .riskLevel(entity.getRiskLevel())
                .approvalRequestId(entity.getApprovalRequestId() != null ? entity.getApprovalRequestId().toString() : null)
                .taskId(entity.getTaskId() != null ? entity.getTaskId().toString() : null)
                .status(entity.getStatus())
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private RiskEvaluationVo toRiskEvaluationVo(RiskEvaluationEntity entity) {
        return RiskEvaluationVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .actionType(entity.getActionType())
                .entityType(entity.getEntityType())
                .entityId(entity.getEntityId() != null ? entity.getEntityId().toString() : null)
                .riskLevel(entity.getRiskLevel())
                .reasons(entity.getReasons())
                .blocked(entity.getBlocked())
                .requiresApproval(entity.getRequiresApproval())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }
}
