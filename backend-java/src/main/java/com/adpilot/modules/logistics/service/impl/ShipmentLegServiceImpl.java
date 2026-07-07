package com.adpilot.modules.logistics.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.logistics.dto.ShipmentLegDto;
import com.adpilot.modules.logistics.entity.CarrierEntity;
import com.adpilot.modules.logistics.entity.ShipmentEntity;
import com.adpilot.modules.logistics.entity.ShipmentLegEntity;
import com.adpilot.modules.logistics.mapper.CarrierMapper;
import com.adpilot.modules.logistics.mapper.ShipmentLegMapper;
import com.adpilot.modules.logistics.mapper.ShipmentMapper;
import com.adpilot.modules.logistics.service.ShipmentLegService;
import com.adpilot.modules.logistics.vo.ShipmentLegVo;
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
 * Default {@link ShipmentLegService} implementation.
 *
 * <p>Validation and persistence rules implement Requirement 4 (multi-leg
 * transport path) and Requirement 6.6 (carrier-not-found). All writes are
 * transactional so any validation failure rolls back and leaves previously
 * persisted leg data unchanged.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentLegServiceImpl implements ShipmentLegService {

    /** Maximum number of legs a single shipment may record (Req 4.1, 4.7). */
    static final int MAX_LEGS_PER_SHIPMENT = 20;

    /** Lower bound for a leg cost (Req 4.1). */
    private static final BigDecimal MIN_LEG_COST = new BigDecimal("0.00");

    /** Upper bound for a leg cost (Req 4.1). */
    private static final BigDecimal MAX_LEG_COST = new BigDecimal("999999999.99");

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ShipmentLegMapper shipmentLegMapper;
    private final ShipmentMapper shipmentMapper;
    private final CarrierMapper carrierMapper;

    @Override
    @Transactional
    public ShipmentLegVo upsertLeg(String shipmentId, ShipmentLegDto dto) {
        UUID shipmentUuid = parseShipmentId(shipmentId);
        ShipmentEntity shipment = shipmentMapper.selectById(shipmentUuid);
        if (shipment == null) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + shipmentId);
        }

        // Field-level validation (Req 4.6): accumulate every invalid/missing field.
        List<String> errors = new ArrayList<>();
        if (dto == null) {
            throw new BusinessException("VALIDATION_ERROR", "Shipment leg payload is required");
        }
        if (isBlank(dto.getLegType())) {
            errors.add("legType is required");
        }
        if (dto.getSequenceNo() == null) {
            errors.add("sequenceNo is required");
        }
        if (isBlank(dto.getCarrierId())) {
            errors.add("carrierId is required");
        }
        if (dto.getDepartureDate() == null) {
            errors.add("departureDate is required");
        }
        if (dto.getArrivalDate() == null) {
            errors.add("arrivalDate is required");
        }
        if (dto.getLegCost() == null) {
            errors.add("legCost is required");
        } else if (dto.getLegCost().compareTo(MIN_LEG_COST) < 0 || dto.getLegCost().compareTo(MAX_LEG_COST) > 0) {
            errors.add("legCost must be between 0.00 and 999,999,999.99");
        }
        // Arrival date must be on or after departure date (Req 4.5).
        if (dto.getDepartureDate() != null && dto.getArrivalDate() != null
                && dto.getArrivalDate().isBefore(dto.getDepartureDate())) {
            errors.add("arrivalDate must be on or after departureDate");
        }
        if (!errors.isEmpty()) {
            throw new BusinessException("VALIDATION_ERROR",
                    "Invalid shipment leg: " + String.join("; ", errors));
        }

        // Carrier must reference an existing carrier (Req 6.6).
        UUID carrierUuid = parseCarrierId(dto.getCarrierId());
        CarrierEntity carrier = carrierUuid != null ? carrierMapper.selectById(carrierUuid) : null;
        if (carrier == null) {
            throw new BusinessException("CARRIER_NOT_FOUND", "Carrier not found: " + dto.getCarrierId());
        }

        // Locate any existing leg with the same sequence number for upsert.
        ShipmentLegEntity existing = findBySequence(shipmentUuid, dto.getSequenceNo());

        // Enforce the per-shipment leg-count limit only when adding a new leg (Req 4.7).
        if (existing == null) {
            long legCount = countLegs(shipmentUuid);
            if (legCount >= MAX_LEGS_PER_SHIPMENT) {
                throw new BusinessException("LEG_LIMIT_EXCEEDED",
                        "A shipment may record at most " + MAX_LEGS_PER_SHIPMENT + " legs");
            }
        }

        ShipmentLegEntity entity = existing != null ? existing : new ShipmentLegEntity();
        entity.setShipmentId(shipmentUuid);
        entity.setLegType(dto.getLegType());
        entity.setSequenceNo(dto.getSequenceNo());
        entity.setCarrierId(carrierUuid);
        entity.setDepartureDate(dto.getDepartureDate());
        entity.setArrivalDate(dto.getArrivalDate());
        entity.setLegCost(dto.getLegCost());

        if (existing != null) {
            shipmentLegMapper.updateById(entity);
            log.info("Shipment leg updated: shipmentId={}, sequenceNo={}", shipmentId, dto.getSequenceNo());
        } else {
            shipmentLegMapper.insert(entity);
            log.info("Shipment leg created: shipmentId={}, sequenceNo={}", shipmentId, dto.getSequenceNo());
        }

        return toVo(entity, carrier);
    }

    @Override
    public List<ShipmentLegVo> listLegs(String shipmentId) {
        UUID shipmentUuid = parseShipmentId(shipmentId);
        LambdaQueryWrapper<ShipmentLegEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShipmentLegEntity::getShipmentId, shipmentUuid);
        wrapper.orderByAsc(ShipmentLegEntity::getSequenceNo);
        List<ShipmentLegEntity> legs = shipmentLegMapper.selectList(wrapper);
        return legs.stream()
                .map(leg -> toVo(leg, resolveCarrier(leg.getCarrierId())))
                .collect(Collectors.toList());
    }

    private ShipmentLegEntity findBySequence(UUID shipmentUuid, Integer sequenceNo) {
        LambdaQueryWrapper<ShipmentLegEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShipmentLegEntity::getShipmentId, shipmentUuid);
        wrapper.eq(ShipmentLegEntity::getSequenceNo, sequenceNo);
        wrapper.last("LIMIT 1");
        return shipmentLegMapper.selectOne(wrapper);
    }

    private long countLegs(UUID shipmentUuid) {
        LambdaQueryWrapper<ShipmentLegEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShipmentLegEntity::getShipmentId, shipmentUuid);
        Long count = shipmentLegMapper.selectCount(wrapper);
        return count != null ? count : 0L;
    }

    private CarrierEntity resolveCarrier(UUID carrierId) {
        return carrierId != null ? carrierMapper.selectById(carrierId) : null;
    }

    private ShipmentLegVo toVo(ShipmentLegEntity entity, CarrierEntity carrier) {
        return ShipmentLegVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .shipmentId(entity.getShipmentId() != null ? entity.getShipmentId().toString() : null)
                .legType(entity.getLegType())
                .sequenceNo(entity.getSequenceNo())
                .carrierId(entity.getCarrierId() != null ? entity.getCarrierId().toString() : null)
                .carrierName(carrier != null ? carrier.getName() : null)
                .departureDate(entity.getDepartureDate() != null ? entity.getDepartureDate().format(DATE_FORMATTER) : null)
                .arrivalDate(entity.getArrivalDate() != null ? entity.getArrivalDate().format(DATE_FORMATTER) : null)
                .legCost(entity.getLegCost())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(DATETIME_FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(DATETIME_FORMATTER) : null)
                .build();
    }

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

    private UUID parseCarrierId(String carrierId) {
        try {
            return UUID.fromString(carrierId);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
