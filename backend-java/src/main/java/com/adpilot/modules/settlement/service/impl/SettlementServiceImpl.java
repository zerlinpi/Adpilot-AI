package com.adpilot.modules.settlement.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.settlement.dto.SettlementDto;
import com.adpilot.modules.settlement.entity.SettlementEntity;
import com.adpilot.modules.settlement.mapper.SettlementMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.settlement.service.SettlementService;
import com.adpilot.modules.settlement.vo.SettlementVo;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementServiceImpl implements SettlementService {

    private final SettlementMapper settlementMapper;
    private final DataScopeService dataScopeService;
    private final AuditLogService auditLogService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Store-only scope target shared by settlement entities (Req 7.1.5). */
    private static final ScopeTarget STORE_SCOPE = ScopeTarget.store("store_id");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public PageResponse<SettlementVo> listSettlements(String storeId, int page, int pageSize) {
        Page<SettlementEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<SettlementEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isBlank()) {
            try { wrapper.eq("store_id", UUID.fromString(storeId).toString()); }
            catch (IllegalArgumentException ignored) { }
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<SettlementEntity> result = settlementMapper.selectPage(pageParam, wrapper);
        List<SettlementVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public SettlementVo getSettlementById(String id) {
        SettlementEntity entity = settlementMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("SETTLEMENT_NOT_FOUND", "Settlement not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(entity, user);
        }
        return toVo(entity);
    }

    @Override
    @Transactional
    public SettlementVo importSettlement(SettlementDto dto) {
        SettlementEntity entity = SettlementEntity.builder()
                .storeId(UUID.fromString(dto.getStoreId()))
                .settlementId(dto.getSettlementId())
                .settlementStartDate(dto.getSettlementStartDate())
                .settlementEndDate(dto.getSettlementEndDate())
                .depositDate(dto.getDepositDate())
                .totalAmount(dto.getTotalAmount() != null ? dto.getTotalAmount() : BigDecimal.ZERO)
                .currency(dto.getCurrency())
                .status(dto.getStatus() != null ? dto.getStatus() : "pending")
                .rawData(dto.getRawData())
                .build();

        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }

        settlementMapper.insert(entity);
        log.info("Settlement imported: id={}, settlementId={}", entity.getId(), entity.getSettlementId());

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("storeId", entity.getStoreId() != null ? entity.getStoreId().toString() : null);
        auditDetails.put("totalAmount", entity.getTotalAmount());
        auditDetails.put("currency", entity.getCurrency());
        auditDetails.put("periodStart", entity.getSettlementStartDate() != null
                ? entity.getSettlementStartDate().format(DATE_FORMATTER) : null);
        auditDetails.put("periodEnd", entity.getSettlementEndDate() != null
                ? entity.getSettlementEndDate().format(DATE_FORMATTER) : null);
        writeAudit("IMPORT_SETTLEMENT", "settlement", entity.getId(), auditDetails);

        return toVo(entity);
    }

    /**
     * Write a forensic AUDIT-TRAIL entry for a state-changing settlement action. Auditing is
     * additive and best-effort: a failure here is logged and swallowed so it can never break
     * the business operation. The details map must never carry secret values.
     */
    private void writeAudit(String action, String entityType, UUID entityId, Map<String, Object> details) {
        try {
            auditLogService.createLog(resolveActorId(), resolveOrgId(), action, entityType, entityId, details);
        } catch (Exception ex) {
            log.warn("Failed to write audit log action={} entityId={}: {}", action, entityId, ex.getMessage());
        }
    }

    /** Acting user id from the security context, or {@code null} for a system/background actor. */
    private static UUID resolveActorId() {
        return parseUuidOrNull(SecurityUtils.getCurrentUserIdOrNull());
    }

    /** Caller org id from the security context, or {@code null} when unauthenticated. */
    private static UUID resolveOrgId() {
        if (!SecurityUtils.isAuthenticated()) {
            return null;
        }
        return parseUuidOrNull(SecurityUtils.getCurrentOrgId());
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private SettlementVo toVo(SettlementEntity entity) {
        return SettlementVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .settlementId(entity.getSettlementId())
                .settlementStartDate(entity.getSettlementStartDate() != null ? entity.getSettlementStartDate().format(DATE_FORMATTER) : null)
                .settlementEndDate(entity.getSettlementEndDate() != null ? entity.getSettlementEndDate().format(DATE_FORMATTER) : null)
                .depositDate(entity.getDepositDate() != null ? entity.getDepositDate().format(DATE_FORMATTER) : null)
                .totalAmount(entity.getTotalAmount() != null ? entity.getTotalAmount().doubleValue() : 0.0)
                .currency(entity.getCurrency())
                .status(entity.getStatus())
                .rawData(entity.getRawData())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }
}
