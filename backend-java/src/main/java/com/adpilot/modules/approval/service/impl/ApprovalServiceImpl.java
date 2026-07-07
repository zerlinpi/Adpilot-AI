package com.adpilot.modules.approval.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.approval.dto.ApprovalRequestDto;
import com.adpilot.modules.approval.entity.ApprovalRequestEntity;
import com.adpilot.modules.approval.entity.ApprovalWatcherEntity;
import com.adpilot.modules.approval.mapper.ApprovalRequestMapper;
import com.adpilot.modules.approval.mapper.ApprovalWatcherMapper;
import com.adpilot.modules.approval.service.ApprovalService;
import com.adpilot.modules.approval.vo.ApprovalRequestVo;
import com.adpilot.modules.audit.service.AuditLogService;
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
public class ApprovalServiceImpl implements ApprovalService {

    private final ApprovalRequestMapper approvalRequestMapper;
    private final ApprovalWatcherMapper approvalWatcherMapper;
    private final AuditLogService auditLogService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public PageResponse<ApprovalRequestVo> listApprovalRequests(String storeId, String status, String riskLevel, int page, int pageSize) {
        Page<ApprovalRequestEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<ApprovalRequestEntity> wrapper = new LambdaQueryWrapper<>();

        if (storeId != null && !storeId.isEmpty()) {
            wrapper.eq(ApprovalRequestEntity::getStoreId, UUID.fromString(storeId));
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(ApprovalRequestEntity::getStatus, status);
        }
        if (riskLevel != null && !riskLevel.isEmpty()) {
            wrapper.eq(ApprovalRequestEntity::getRiskLevel, riskLevel);
        }
        wrapper.orderByDesc(ApprovalRequestEntity::getCreatedAt);

        Page<ApprovalRequestEntity> result = approvalRequestMapper.selectPage(pageParam, wrapper);
        List<ApprovalRequestVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public ApprovalRequestVo getApprovalRequest(String id) {
        ApprovalRequestEntity entity = approvalRequestMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("APPROVAL_NOT_FOUND", "审批请求不存在: " + id);
        }
        return toVo(entity);
    }

    @Override
    @Transactional
    public ApprovalRequestVo createApprovalRequest(ApprovalRequestDto dto, String userId) {
        UUID storeId = UUID.fromString(dto.getStoreId());
        UUID requesterId = userId != null ? UUID.fromString(userId) : null;

        ApprovalRequestEntity entity = ApprovalRequestEntity.builder()
                .storeId(storeId)
                .requesterId(requesterId)
                .requestType(dto.getRequestType())
                .relatedEntityType(dto.getRelatedEntityType())
                .relatedEntityId(dto.getRelatedEntityId() != null ? UUID.fromString(dto.getRelatedEntityId()) : null)
                .title(dto.getTitle())
                .description(dto.getDescription())
                .payload(dto.getPayload())
                .riskLevel(dto.getRiskLevel() != null ? dto.getRiskLevel() : "low")
                .status("pending")
                .build();

        approvalRequestMapper.insert(entity);
        log.info("审批请求已创建: id={}, type={}, title={}", entity.getId(), entity.getRequestType(), entity.getTitle());

        // Write audit log
        auditLogService.createLog(requesterId, null, "CREATE", "approval_request", entity.getId(), dto);

        return toVo(entity);
    }

    @Override
    @Transactional
    public ApprovalRequestVo approveRequest(String id, String userId) {
        ApprovalRequestEntity entity = approvalRequestMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("APPROVAL_NOT_FOUND", "审批请求不存在: " + id);
        }
        if (!"pending".equals(entity.getStatus())) {
            throw new BusinessException("APPROVAL_INVALID_STATUS", "审批请求当前状态不允许审批: " + entity.getStatus());
        }

        entity.setApproverId(userId != null ? UUID.fromString(userId) : null);
        entity.setStatus("approved");
        entity.setResolvedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        approvalRequestMapper.updateById(entity);

        log.info("审批请求已通过: id={}, approver={}", id, userId);

        // Write audit log
        auditLogService.createLog(
                userId != null ? UUID.fromString(userId) : null,
                null, "APPROVE", "approval_request", entity.getId(),
                "审批通过"
        );

        return toVo(entity);
    }

    @Override
    @Transactional
    public ApprovalRequestVo rejectRequest(String id, String userId, String reason) {
        ApprovalRequestEntity entity = approvalRequestMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("APPROVAL_NOT_FOUND", "审批请求不存在: " + id);
        }
        if (!"pending".equals(entity.getStatus())) {
            throw new BusinessException("APPROVAL_INVALID_STATUS", "审批请求当前状态不允许拒绝: " + entity.getStatus());
        }

        entity.setApproverId(userId != null ? UUID.fromString(userId) : null);
        entity.setStatus("rejected");
        entity.setRejectionReason(reason);
        entity.setResolvedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        approvalRequestMapper.updateById(entity);

        log.info("审批请求已拒绝: id={}, reason={}", id, reason);

        // Write audit log
        auditLogService.createLog(
                userId != null ? UUID.fromString(userId) : null,
                null, "REJECT", "approval_request", entity.getId(),
                reason
        );

        return toVo(entity);
    }

    @Override
    @Transactional
    public void watchRequest(String id, String userId) {
        ApprovalRequestEntity request = approvalRequestMapper.selectById(UUID.fromString(id));
        if (request == null) {
            throw new BusinessException("APPROVAL_NOT_FOUND", "审批请求不存在: " + id);
        }

        UUID userUuid = UUID.fromString(userId);

        // Check if already watching
        LambdaQueryWrapper<ApprovalWatcherEntity> checkWrapper = new LambdaQueryWrapper<>();
        checkWrapper.eq(ApprovalWatcherEntity::getApprovalRequestId, UUID.fromString(id))
                .eq(ApprovalWatcherEntity::getUserId, userUuid);
        Long existing = approvalWatcherMapper.selectCount(checkWrapper);
        if (existing != null && existing > 0) {
            throw new BusinessException("ALREADY_WATCHING", "您已在关注此审批请求");
        }

        ApprovalWatcherEntity watcher = ApprovalWatcherEntity.builder()
                .approvalRequestId(UUID.fromString(id))
                .userId(userUuid)
                .build();
        approvalWatcherMapper.insert(watcher);

        log.info("用户已关注审批请求: approvalId={}, userId={}", id, userId);

        // Write audit log
        auditLogService.createLog(userUuid, null, "WATCH", "approval_request", UUID.fromString(id), "关注审批请求");
    }

    private ApprovalRequestVo toVo(ApprovalRequestEntity entity) {
        return ApprovalRequestVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .requesterId(entity.getRequesterId() != null ? entity.getRequesterId().toString() : null)
                .approverId(entity.getApproverId() != null ? entity.getApproverId().toString() : null)
                .requestType(entity.getRequestType())
                .relatedEntityType(entity.getRelatedEntityType())
                .relatedEntityId(entity.getRelatedEntityId() != null ? entity.getRelatedEntityId().toString() : null)
                .title(entity.getTitle())
                .description(entity.getDescription())
                .payload(entity.getPayload())
                .riskLevel(entity.getRiskLevel())
                .status(entity.getStatus())
                .rejectionReason(entity.getRejectionReason())
                .resolvedAt(entity.getResolvedAt() != null ? entity.getResolvedAt().format(FORMATTER) : null)
                .expiresAt(entity.getExpiresAt() != null ? entity.getExpiresAt().format(FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }
}
