package com.adpilot.modules.logistics.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.logistics.dto.CarrierDto;
import com.adpilot.modules.logistics.entity.CarrierEntity;
import com.adpilot.modules.logistics.mapper.CarrierMapper;
import com.adpilot.modules.logistics.service.CarrierService;
import com.adpilot.modules.logistics.vo.CarrierVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link CarrierService} implementation.
 *
 * <p>Validation is performed in the service layer (mirroring
 * {@code CartonSpecServiceImpl}/{@code LogisticsServiceImpl} conventions): on
 * any invalid field a {@link BusinessException} is thrown with a message
 * identifying the invalid field, and — because the persisting methods are
 * {@link Transactional} and validation runs before any write — nothing is
 * persisted (Req 6.2).</p>
 *
 * <p>Each carrier is scoped to the requester's Organization via {@code org_id}
 * (resolved from {@link SecurityUtils#getCurrentOrgId()}): create stamps the
 * org id, and update/list are filtered by it, so carriers are shared across
 * the owning Organization's stores but invisible to other Organizations
 * (Req 17.1).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CarrierServiceImpl implements CarrierService {

    private final CarrierMapper carrierMapper;

    private static final DateTimeFormatter DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Carrier name length bounds (Req 6.1). */
    private static final int NAME_MIN = 1;
    private static final int NAME_MAX = 200;

    /** Service type length bounds (Req 6.1). */
    private static final int SERVICE_TYPE_MIN = 1;
    private static final int SERVICE_TYPE_MAX = 100;

    @Override
    @Transactional
    public CarrierVo create(CarrierDto dto) {
        validate(dto);
        UUID orgUuid = currentOrgId();

        CarrierEntity entity = CarrierEntity.builder()
                .orgId(orgUuid)
                .name(dto.getName().trim())
                .serviceType(dto.getServiceType().trim())
                .build();

        carrierMapper.insert(entity);
        log.info("Carrier created: id={}, orgId={}", entity.getId(), orgUuid);

        // Re-read so generated id and timestamps are populated from persistence.
        CarrierEntity saved = carrierMapper.selectById(entity.getId());
        return toVo(saved != null ? saved : entity);
    }

    @Override
    @Transactional
    public CarrierVo update(String id, CarrierDto dto) {
        validate(dto);
        UUID orgUuid = currentOrgId();
        UUID carrierUuid = parseId(id);

        CarrierEntity existing = carrierMapper.selectById(carrierUuid);
        if (existing == null || !orgUuid.equals(existing.getOrgId())) {
            // Outside the requester's org scope is indistinguishable from absent (Req 17.1, 17.4).
            throw new BusinessException("CARRIER_NOT_FOUND", "Carrier not found: " + id);
        }

        existing.setName(dto.getName().trim());
        existing.setServiceType(dto.getServiceType().trim());

        carrierMapper.updateById(existing);
        log.info("Carrier updated: id={}, orgId={}", carrierUuid, orgUuid);

        CarrierEntity saved = carrierMapper.selectById(carrierUuid);
        return toVo(saved != null ? saved : existing);
    }

    @Override
    public List<CarrierVo> list() {
        UUID orgUuid = currentOrgId();

        LambdaQueryWrapper<CarrierEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CarrierEntity::getOrgId, orgUuid);
        wrapper.orderByAsc(CarrierEntity::getName);

        List<CarrierEntity> carriers = carrierMapper.selectList(wrapper);
        if (carriers == null || carriers.isEmpty()) {
            return List.of();
        }
        return carriers.stream().map(this::toVo).collect(Collectors.toList());
    }

    // --- helpers -----------------------------------------------------------

    private UUID currentOrgId() {
        String orgId = SecurityUtils.getCurrentOrgId();
        if (orgId == null || orgId.isBlank()) {
            throw new BusinessException("AUTH_001", "No organization context for the current user");
        }
        try {
            return UUID.fromString(orgId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("AUTH_001", "Organization id is not a valid identifier: " + orgId);
        }
    }

    private UUID parseId(String id) {
        if (id == null || id.isBlank()) {
            throw new BusinessException("CARRIER_NOT_FOUND", "Carrier id is required");
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("CARRIER_NOT_FOUND", "Carrier not found: " + id);
        }
    }

    /**
     * Validate name (1–200) and service type (1–100), collecting every problem
     * so the error message identifies each invalid field (Req 6.1, 6.2). Throws
     * before any persistence occurs.
     */
    private void validate(CarrierDto dto) {
        if (dto == null) {
            throw new BusinessException("CARRIER_INVALID", "Carrier payload is required");
        }

        List<String> errors = new ArrayList<>();
        validateText(errors, "name", "Carrier name", dto.getName(), NAME_MIN, NAME_MAX);
        validateText(errors, "serviceType", "Service type", dto.getServiceType(), SERVICE_TYPE_MIN, SERVICE_TYPE_MAX);

        if (!errors.isEmpty()) {
            throw new BusinessException("CARRIER_INVALID", String.join("; ", errors));
        }
    }

    private void validateText(List<String> errors, String field, String label, String value, int min, int max) {
        if (value == null || value.trim().isEmpty()) {
            errors.add(field + ": " + label + " is required");
            return;
        }
        int length = value.trim().length();
        if (length < min || length > max) {
            errors.add(field + ": " + label + " must be between " + min + " and " + max + " characters");
        }
    }

    private CarrierVo toVo(CarrierEntity entity) {
        return CarrierVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .orgId(entity.getOrgId() != null ? entity.getOrgId().toString() : null)
                .name(entity.getName())
                .serviceType(entity.getServiceType())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(DATETIME_FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(DATETIME_FORMATTER) : null)
                .build();
    }
}
