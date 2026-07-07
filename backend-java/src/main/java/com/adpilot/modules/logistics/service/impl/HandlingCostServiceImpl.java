package com.adpilot.modules.logistics.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.logistics.dto.HandlingCostDto;
import com.adpilot.modules.logistics.entity.HandlingCostEntity;
import com.adpilot.modules.logistics.entity.ShipmentEntity;
import com.adpilot.modules.logistics.mapper.HandlingCostMapper;
import com.adpilot.modules.logistics.mapper.ShipmentMapper;
import com.adpilot.modules.logistics.service.HandlingCostService;
import com.adpilot.modules.logistics.vo.HandlingCostVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link HandlingCostService} implementation.
 *
 * <p>Validation is performed in the service layer (mirroring
 * {@code ShipmentLegServiceImpl}/{@code CartonSpecServiceImpl} conventions): on
 * any invalid field a {@link BusinessException} is thrown with a message
 * identifying every invalid field. Because the persisting methods are
 * {@link Transactional} and validation runs before any write, nothing is
 * persisted on failure, leaving previously persisted handling-cost lines
 * unchanged (Req 18.4).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HandlingCostServiceImpl implements HandlingCostService {

    /** Amount lower bound (Req 18.1). */
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.00");

    /** Amount upper bound (Req 18.1). */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");

    /** Currency code is an ISO-4217 alpha code of exactly 3 characters (Req 18.1). */
    private static final int CURRENCY_CODE_LENGTH = 3;

    /** Description/category length bounds (Req 18.1). */
    private static final int MIN_DESCRIPTION_LENGTH = 1;
    private static final int MAX_DESCRIPTION_LENGTH = 200;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HandlingCostMapper handlingCostMapper;
    private final ShipmentMapper shipmentMapper;

    @Override
    @Transactional
    public HandlingCostVo upsert(String shipmentId, String id, HandlingCostDto dto) {
        UUID shipmentUuid = parseShipmentId(shipmentId);
        ShipmentEntity shipment = shipmentMapper.selectById(shipmentUuid);
        if (shipment == null) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + shipmentId);
        }

        validate(dto);

        // Resolve any existing line for update; absence of id => insert.
        HandlingCostEntity existing = null;
        if (!isBlank(id)) {
            UUID handlingCostUuid = parseHandlingCostId(id);
            existing = handlingCostMapper.selectById(handlingCostUuid);
            if (existing == null || !shipmentUuid.equals(existing.getShipmentId())) {
                throw new BusinessException("HANDLING_COST_NOT_FOUND",
                        "Handling cost not found for shipment: " + id);
            }
        }

        HandlingCostEntity entity = existing != null ? existing : new HandlingCostEntity();
        entity.setShipmentId(shipmentUuid);
        entity.setAmount(dto.getAmount());
        entity.setCurrencyCode(dto.getCurrencyCode());
        entity.setDescription(dto.getDescription());
        entity.setExchangeRate(dto.getExchangeRate());
        entity.setCostDate(dto.getCostDate());

        if (existing != null) {
            handlingCostMapper.updateById(entity);
            log.info("Handling cost updated: id={}, shipmentId={}", entity.getId(), shipmentUuid);
        } else {
            handlingCostMapper.insert(entity);
            log.info("Handling cost created: id={}, shipmentId={}", entity.getId(), shipmentUuid);
        }

        // Re-read so generated id and timestamps are populated from persistence.
        HandlingCostEntity saved = entity.getId() != null
                ? handlingCostMapper.selectById(entity.getId())
                : null;
        return toVo(saved != null ? saved : entity);
    }

    @Override
    public void delete(String id) {
        if (isBlank(id)) {
            return;
        }
        UUID handlingCostUuid = parseHandlingCostId(id);
        handlingCostMapper.deleteById(handlingCostUuid);
        log.info("Handling cost deleted: id={}", handlingCostUuid);
    }

    @Override
    public List<HandlingCostVo> list(String shipmentId) {
        UUID shipmentUuid = parseShipmentId(shipmentId);
        LambdaQueryWrapper<HandlingCostEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(HandlingCostEntity::getShipmentId, shipmentUuid);
        wrapper.orderByAsc(HandlingCostEntity::getCreatedAt);
        List<HandlingCostEntity> lines = handlingCostMapper.selectList(wrapper);
        return lines.stream().map(this::toVo).collect(Collectors.toList());
    }

    // --- validation --------------------------------------------------------

    /**
     * Validate every field against the Req 18.1 bounds, collecting all problems
     * so the error message identifies each invalid field (Req 18.4). Throws
     * before any persistence occurs.
     */
    private void validate(HandlingCostDto dto) {
        if (dto == null) {
            throw new BusinessException("HANDLING_COST_INVALID", "Handling cost payload is required");
        }

        List<String> errors = new ArrayList<>();

        if (dto.getAmount() == null) {
            errors.add("amount: Amount is required");
        } else if (dto.getAmount().compareTo(MIN_AMOUNT) < 0 || dto.getAmount().compareTo(MAX_AMOUNT) > 0) {
            errors.add("amount: Amount must be between 0.00 and 999,999,999.99");
        }

        if (isBlank(dto.getCurrencyCode())) {
            errors.add("currencyCode: Currency code is required");
        } else if (dto.getCurrencyCode().length() != CURRENCY_CODE_LENGTH) {
            errors.add("currencyCode: Currency code must be exactly " + CURRENCY_CODE_LENGTH + " characters");
        }

        if (isBlank(dto.getDescription())) {
            errors.add("description: Description is required");
        } else if (dto.getDescription().length() < MIN_DESCRIPTION_LENGTH
                || dto.getDescription().length() > MAX_DESCRIPTION_LENGTH) {
            errors.add("description: Description must be between " + MIN_DESCRIPTION_LENGTH
                    + " and " + MAX_DESCRIPTION_LENGTH + " characters");
        }

        if (!errors.isEmpty()) {
            throw new BusinessException("HANDLING_COST_INVALID", String.join("; ", errors));
        }
    }

    // --- helpers -----------------------------------------------------------

    private UUID parseShipmentId(String shipmentId) {
        if (isBlank(shipmentId)) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + shipmentId);
        }
        try {
            return UUID.fromString(shipmentId);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + shipmentId);
        }
    }

    private UUID parseHandlingCostId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("HANDLING_COST_NOT_FOUND", "Handling cost not found: " + id);
        }
    }

    private HandlingCostVo toVo(HandlingCostEntity entity) {
        return HandlingCostVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .shipmentId(entity.getShipmentId() != null ? entity.getShipmentId().toString() : null)
                .amount(entity.getAmount())
                .currencyCode(entity.getCurrencyCode())
                .description(entity.getDescription())
                .exchangeRate(entity.getExchangeRate())
                .costDate(entity.getCostDate() != null ? entity.getCostDate().format(DATE_FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(DATETIME_FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(DATETIME_FORMATTER) : null)
                .build();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
