package com.adpilot.modules.logistics.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.logistics.dto.CartonSpecDto;
import com.adpilot.modules.logistics.entity.CartonSpecEntity;
import com.adpilot.modules.logistics.mapper.CartonSpecMapper;
import com.adpilot.modules.logistics.service.CartonSpecService;
import com.adpilot.modules.logistics.vo.CartonSpecVo;
import com.adpilot.modules.logistics.vo.CartonTotalsVo;
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

/**
 * Default {@link CartonSpecService} implementation.
 *
 * <p>Validation is performed in the service layer (mirroring
 * {@code LogisticsServiceImpl} conventions): on any invalid field a
 * {@link BusinessException} is thrown with a message identifying every invalid
 * field, and — because the persisting method is {@link Transactional} and the
 * validation runs before any write — nothing is persisted (Req 5.5).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartonSpecServiceImpl implements CartonSpecService {

    private final CartonSpecMapper cartonSpecMapper;

    private static final DateTimeFormatter DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Per-shipment carton-spec count bound (Req 5.1). */
    private static final int MAX_SPECS_PER_SHIPMENT = 100;

    /** Box dimension bounds in centimetres (Req 5.1). */
    private static final BigDecimal MIN_DIMENSION_CM = new BigDecimal("0.1");
    private static final BigDecimal MAX_DIMENSION_CM = new BigDecimal("1000.0");

    /** Box weight bounds in kilograms (Req 5.1). */
    private static final BigDecimal MIN_WEIGHT_KG = new BigDecimal("0.01");
    private static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("10000.00");

    /** Integer count bounds for units-per-box and box-count (Req 5.1). */
    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 1_000_000;

    @Override
    @Transactional
    public CartonSpecVo addSpec(String shipmentId, CartonSpecDto dto) {
        UUID shipmentUuid = parseShipmentId(shipmentId);
        validate(dto);

        long existing = countSpecs(shipmentUuid);
        if (existing >= MAX_SPECS_PER_SHIPMENT) {
            throw new BusinessException("CARTON_SPEC_LIMIT_EXCEEDED",
                    "A shipment may record at most " + MAX_SPECS_PER_SHIPMENT
                            + " carton specs; this shipment already has " + existing);
        }

        CartonSpecEntity entity = CartonSpecEntity.builder()
                .shipmentId(shipmentUuid)
                .boxLengthCm(dto.getBoxLengthCm())
                .boxWidthCm(dto.getBoxWidthCm())
                .boxHeightCm(dto.getBoxHeightCm())
                .boxWeightKg(dto.getBoxWeightKg())
                .unitsPerBox(dto.getUnitsPerBox())
                .boxCount(dto.getBoxCount())
                .build();

        cartonSpecMapper.insert(entity);
        log.info("Carton spec created: id={}, shipmentId={}", entity.getId(), shipmentUuid);

        // Re-read so generated id and timestamps are populated from persistence.
        CartonSpecEntity saved = cartonSpecMapper.selectById(entity.getId());
        return toVo(saved != null ? saved : entity);
    }

    @Override
    public CartonTotalsVo totals(String shipmentId) {
        UUID shipmentUuid = parseShipmentId(shipmentId);

        LambdaQueryWrapper<CartonSpecEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CartonSpecEntity::getShipmentId, shipmentUuid);
        List<CartonSpecEntity> specs = cartonSpecMapper.selectList(wrapper);

        long totalBoxCount = 0L;
        long totalUnitQuantity = 0L;
        for (CartonSpecEntity spec : specs) {
            long boxCount = spec.getBoxCount() != null ? spec.getBoxCount() : 0L;
            long unitsPerBox = spec.getUnitsPerBox() != null ? spec.getUnitsPerBox() : 0L;
            totalBoxCount += boxCount;
            totalUnitQuantity += unitsPerBox * boxCount;
        }

        return CartonTotalsVo.builder()
                .shipmentId(shipmentUuid.toString())
                .totalBoxCount(totalBoxCount)
                .totalUnitQuantity(totalUnitQuantity)
                .build();
    }

    // --- helpers -----------------------------------------------------------

    private long countSpecs(UUID shipmentUuid) {
        LambdaQueryWrapper<CartonSpecEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CartonSpecEntity::getShipmentId, shipmentUuid);
        Long count = cartonSpecMapper.selectCount(wrapper);
        return count != null ? count : 0L;
    }

    private UUID parseShipmentId(String shipmentId) {
        if (shipmentId == null || shipmentId.isBlank()) {
            throw new BusinessException("CARTON_SPEC_INVALID", "shipmentId is required");
        }
        try {
            return UUID.fromString(shipmentId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("CARTON_SPEC_INVALID", "shipmentId is not a valid identifier: " + shipmentId);
        }
    }

    /**
     * Validate every field against the Req 5.1 bounds, collecting all problems
     * so the error message identifies each invalid field (Req 5.5). Throws
     * before any persistence occurs.
     */
    private void validate(CartonSpecDto dto) {
        if (dto == null) {
            throw new BusinessException("CARTON_SPEC_INVALID", "Carton spec payload is required");
        }

        List<String> errors = new ArrayList<>();
        validateDimension(errors, "boxLengthCm", "Box length (cm)", dto.getBoxLengthCm());
        validateDimension(errors, "boxWidthCm", "Box width (cm)", dto.getBoxWidthCm());
        validateDimension(errors, "boxHeightCm", "Box height (cm)", dto.getBoxHeightCm());
        validateWeight(errors, dto.getBoxWeightKg());
        validateCount(errors, "unitsPerBox", "Units per box", dto.getUnitsPerBox());
        validateCount(errors, "boxCount", "Box count", dto.getBoxCount());

        if (!errors.isEmpty()) {
            throw new BusinessException("CARTON_SPEC_INVALID", String.join("; ", errors));
        }
    }

    private void validateDimension(List<String> errors, String field, String label, BigDecimal value) {
        if (value == null) {
            errors.add(field + ": " + label + " is required");
            return;
        }
        if (value.compareTo(MIN_DIMENSION_CM) < 0 || value.compareTo(MAX_DIMENSION_CM) > 0) {
            errors.add(field + ": " + label + " must be between " + MIN_DIMENSION_CM
                    + " and " + MAX_DIMENSION_CM);
        }
    }

    private void validateWeight(List<String> errors, BigDecimal value) {
        if (value == null) {
            errors.add("boxWeightKg: Box weight (kg) is required");
            return;
        }
        if (value.compareTo(MIN_WEIGHT_KG) < 0 || value.compareTo(MAX_WEIGHT_KG) > 0) {
            errors.add("boxWeightKg: Box weight (kg) must be between " + MIN_WEIGHT_KG
                    + " and " + MAX_WEIGHT_KG);
        }
    }

    private void validateCount(List<String> errors, String field, String label, Integer value) {
        if (value == null) {
            errors.add(field + ": " + label + " is required");
            return;
        }
        if (value < MIN_COUNT || value > MAX_COUNT) {
            errors.add(field + ": " + label + " must be between " + MIN_COUNT + " and " + MAX_COUNT);
        }
    }

    private CartonSpecVo toVo(CartonSpecEntity entity) {
        return CartonSpecVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .shipmentId(entity.getShipmentId() != null ? entity.getShipmentId().toString() : null)
                .boxLengthCm(entity.getBoxLengthCm())
                .boxWidthCm(entity.getBoxWidthCm())
                .boxHeightCm(entity.getBoxHeightCm())
                .boxWeightKg(entity.getBoxWeightKg())
                .unitsPerBox(entity.getUnitsPerBox())
                .boxCount(entity.getBoxCount())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(DATETIME_FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(DATETIME_FORMATTER) : null)
                .build();
    }
}
