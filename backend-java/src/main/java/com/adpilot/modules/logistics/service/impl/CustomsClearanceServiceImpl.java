package com.adpilot.modules.logistics.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.logistics.dto.CustomsClearanceDto;
import com.adpilot.modules.logistics.entity.CustomsClearanceEntity;
import com.adpilot.modules.logistics.mapper.CustomsClearanceMapper;
import com.adpilot.modules.logistics.service.CustomsClearanceService;
import com.adpilot.modules.logistics.vo.CustomsClearanceVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

/**
 * Default implementation of {@link CustomsClearanceService}. Persists at most
 * one Customs_Clearance record per Shipment (keyed by the unique
 * {@code shipment_id}) and returns a not-started state when none exists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomsClearanceServiceImpl implements CustomsClearanceService {

    private final CustomsClearanceMapper customsClearanceMapper;

    private static final DateTimeFormatter DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Permitted clearance status values (Req 7.1). */
    private static final Set<String> PERMITTED_STATUSES =
            Set.of("not-started", "declared", "in-review", "cleared", "held");

    private static final String DEFAULT_STATUS = "not-started";

    /** Inclusive bounds for the duties/taxes amount (Req 7.1). */
    private static final BigDecimal MIN_DUTIES = new BigDecimal("0.00");
    private static final BigDecimal MAX_DUTIES = new BigDecimal("999999999.99");

    @Override
    @Transactional
    public CustomsClearanceVo update(String shipmentId, CustomsClearanceDto dto) {
        UUID shipmentUuid = parseShipmentId(shipmentId);

        // Validate BEFORE any persistence so a rejected request leaves the
        // previously persisted record unchanged (Req 7.3, 7.4).
        validate(dto);

        CustomsClearanceEntity existing = findByShipmentId(shipmentUuid);
        boolean isNew = existing == null;
        CustomsClearanceEntity entity = existing != null
                ? existing : CustomsClearanceEntity.builder().shipmentId(shipmentUuid).build();

        entity.setClearanceStatus(dto.getClearanceStatus());
        entity.setDeclarationRef(dto.getDeclarationRef());
        entity.setDutiesTaxes(dto.getDutiesTaxes() != null ? dto.getDutiesTaxes() : BigDecimal.ZERO);

        if (isNew) {
            customsClearanceMapper.insert(entity);
            log.info("Customs clearance created: shipmentId={}, status={}", shipmentId, entity.getClearanceStatus());
        } else {
            customsClearanceMapper.updateById(entity);
            log.info("Customs clearance updated: shipmentId={}, status={}", shipmentId, entity.getClearanceStatus());
        }

        return toVo(entity);
    }

    @Override
    public CustomsClearanceVo get(String shipmentId) {
        UUID shipmentUuid = parseShipmentId(shipmentId);
        CustomsClearanceEntity entity = findByShipmentId(shipmentUuid);
        if (entity == null) {
            // No record exists: return a not-started default state (Req 7.6).
            return CustomsClearanceVo.builder()
                    .shipmentId(shipmentId)
                    .clearanceStatus(DEFAULT_STATUS)
                    .dutiesTaxes(BigDecimal.ZERO)
                    .build();
        }
        return toVo(entity);
    }

    private void validate(CustomsClearanceDto dto) {
        if (dto == null) {
            throw new BusinessException("CUSTOMS_INVALID", "Customs clearance payload is required");
        }

        // Clearance status: required and exactly one of the permitted values (Req 7.1, 7.3).
        String status = dto.getClearanceStatus();
        if (status == null || status.isBlank()) {
            throw new BusinessException("CUSTOMS_INVALID_STATUS", "Clearance status is required");
        }
        if (!PERMITTED_STATUSES.contains(status)) {
            throw new BusinessException("CUSTOMS_INVALID_STATUS",
                    "Clearance status must be one of not-started, declared, in-review, cleared, held; got: " + status);
        }

        // Declaration reference: optional, but when present must be 1–100 characters (Req 7.1).
        String declarationRef = dto.getDeclarationRef();
        if (declarationRef != null && (declarationRef.length() < 1 || declarationRef.length() > 100)) {
            throw new BusinessException("CUSTOMS_INVALID_DECLARATION_REF",
                    "Declaration reference must be between 1 and 100 characters");
        }

        // Duties/taxes: optional, but when present must be within 0.00–999,999,999.99 (Req 7.1).
        BigDecimal dutiesTaxes = dto.getDutiesTaxes();
        if (dutiesTaxes != null
                && (dutiesTaxes.compareTo(MIN_DUTIES) < 0 || dutiesTaxes.compareTo(MAX_DUTIES) > 0)) {
            throw new BusinessException("CUSTOMS_INVALID_DUTIES",
                    "Duties/taxes must be between 0.00 and 999,999,999.99");
        }
    }

    private CustomsClearanceEntity findByShipmentId(UUID shipmentUuid) {
        LambdaQueryWrapper<CustomsClearanceEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CustomsClearanceEntity::getShipmentId, shipmentUuid);
        return customsClearanceMapper.selectOne(wrapper);
    }

    private UUID parseShipmentId(String shipmentId) {
        if (shipmentId == null || shipmentId.isBlank()) {
            throw new BusinessException("CUSTOMS_INVALID_SHIPMENT", "Shipment id is required");
        }
        try {
            return UUID.fromString(shipmentId);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("CUSTOMS_INVALID_SHIPMENT", "Invalid shipment id: " + shipmentId);
        }
    }

    private CustomsClearanceVo toVo(CustomsClearanceEntity entity) {
        return CustomsClearanceVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .shipmentId(entity.getShipmentId() != null ? entity.getShipmentId().toString() : null)
                .clearanceStatus(entity.getClearanceStatus())
                .declarationRef(entity.getDeclarationRef())
                .dutiesTaxes(entity.getDutiesTaxes())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(DATETIME_FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(DATETIME_FORMATTER) : null)
                .build();
    }
}
