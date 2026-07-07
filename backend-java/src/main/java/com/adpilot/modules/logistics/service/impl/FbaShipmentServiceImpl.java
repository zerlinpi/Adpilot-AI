package com.adpilot.modules.logistics.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.logistics.dto.FbaFieldsDto;
import com.adpilot.modules.logistics.entity.ShipmentEntity;
import com.adpilot.modules.logistics.entity.ShipmentLineItemEntity;
import com.adpilot.modules.logistics.mapper.ShipmentLineItemMapper;
import com.adpilot.modules.logistics.mapper.ShipmentMapper;
import com.adpilot.modules.logistics.service.FbaShipmentService;
import com.adpilot.modules.logistics.vo.FbaFieldsVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link FbaShipmentService} implementation.
 *
 * <p>Validation is performed in the service layer (mirroring
 * {@code ShipmentLegServiceImpl}/{@code CartonSpecServiceImpl} conventions):
 * the owning Shipment is loaded first (a not-found Shipment is rejected,
 * consistent with the other logistics services and Req 16.6), then every
 * submitted field is validated. On any invalid/over-length field a
 * {@link BusinessException} is thrown identifying each invalid field, and —
 * because {@link #saveFields} is {@link Transactional} and validation runs
 * before any write — nothing is persisted and prior values remain unchanged
 * (Req 16.5).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FbaShipmentServiceImpl implements FbaShipmentService {

    private final ShipmentMapper shipmentMapper;
    private final ShipmentLineItemMapper shipmentLineItemMapper;

    /** FBA shipment identifier length bounds (Req 16.1). */
    private static final int FBA_SHIPMENT_ID_MAX = 100;

    /** Amazon shipment status length bound (matches the shipments column). */
    private static final int AMAZON_STATUS_MAX = 50;

    /** Destination FC code length bounds (Req 16.1). */
    private static final int FC_CODE_MAX = 50;

    /** Line item SKU/MSKU length bounds (Req 16.2). */
    private static final int SKU_MAX = 100;
    private static final int MSKU_MAX = 100;

    /** Line item ASIN length bound (Req 16.2). */
    private static final int ASIN_MAX = 20;

    /** Line item quantity bounds (Req 16.2). */
    private static final int QUANTITY_MIN = 1;
    private static final int QUANTITY_MAX = 1_000_000;

    @Override
    @Transactional
    public FbaFieldsVo saveFields(String shipmentId, FbaFieldsDto dto) {
        UUID shipmentUuid = parseShipmentId(shipmentId);

        // Load the owning shipment first so a not-found shipment is rejected and
        // its store scope can be respected by the controller layer (Req 16.6).
        ShipmentEntity shipment = shipmentMapper.selectById(shipmentUuid);
        if (shipment == null) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + shipmentId);
        }

        // Validate BEFORE any persistence so a rejected request leaves the
        // previously persisted FBA fields and line items unchanged (Req 16.5).
        validate(dto);

        // Persist the FBA fields onto the shipment.
        shipment.setFbaShipmentId(dto.getFbaShipmentId());
        shipment.setAmazonShipmentStatus(dto.getAmazonShipmentStatus());
        shipment.setDestinationFcCode(dto.getDestinationFcCode());
        shipmentMapper.updateById(shipment);

        // Replace the shipment's line items with the submitted set.
        LambdaQueryWrapper<ShipmentLineItemEntity> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(ShipmentLineItemEntity::getShipmentId, shipmentUuid);
        shipmentLineItemMapper.delete(deleteWrapper);

        if (dto.getLineItems() != null) {
            for (FbaFieldsDto.FbaLineItemDto itemDto : dto.getLineItems()) {
                ShipmentLineItemEntity item = ShipmentLineItemEntity.builder()
                        .shipmentId(shipmentUuid)
                        .sku(itemDto.getSku())
                        .msku(itemDto.getMsku())
                        .asin(itemDto.getAsin())
                        .quantity(itemDto.getQuantity())
                        .build();
                shipmentLineItemMapper.insert(item);
            }
        }

        log.info("FBA fields saved: shipmentId={}, lineItems={}",
                shipmentId, dto.getLineItems() != null ? dto.getLineItems().size() : 0);

        return buildVo(shipmentUuid);
    }

    @Override
    public FbaFieldsVo getFields(String shipmentId) {
        UUID shipmentUuid = parseShipmentId(shipmentId);

        // Only return fields for a shipment that exists (and that the controller
        // layer has authorized for the Active_Store) (Req 16.6).
        ShipmentEntity shipment = shipmentMapper.selectById(shipmentUuid);
        if (shipment == null) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + shipmentId);
        }

        return toVo(shipment, loadLineItems(shipmentUuid));
    }

    // --- validation --------------------------------------------------------

    /**
     * Validate every field against the Req 16.1/16.2 bounds, collecting all
     * problems so the error message identifies each invalid field (Req 16.5).
     * Throws before any persistence occurs.
     */
    private void validate(FbaFieldsDto dto) {
        if (dto == null) {
            throw new BusinessException("FBA_FIELDS_INVALID", "FBA fields payload is required");
        }

        List<String> errors = new ArrayList<>();

        // FBA shipment id: optional, but when present must be 1–100 chars (Req 16.1, 16.5).
        validateOptionalString(errors, "fbaShipmentId", "FBA shipment ID",
                dto.getFbaShipmentId(), FBA_SHIPMENT_ID_MAX);

        // Amazon shipment status: optional, but must not exceed its max length (Req 16.5).
        if (dto.getAmazonShipmentStatus() != null && dto.getAmazonShipmentStatus().length() > AMAZON_STATUS_MAX) {
            errors.add("amazonShipmentStatus: Amazon shipment status must not exceed " + AMAZON_STATUS_MAX + " characters");
        }

        // Destination FC code: optional, but when present must be 1–50 chars (Req 16.1, 16.5).
        validateOptionalString(errors, "destinationFcCode", "Destination FC code",
                dto.getDestinationFcCode(), FC_CODE_MAX);

        // Line items: 0 or more (Req 16.1); each item is bounds-checked (Req 16.2, 16.5).
        if (dto.getLineItems() != null) {
            List<FbaFieldsDto.FbaLineItemDto> items = dto.getLineItems();
            for (int i = 0; i < items.size(); i++) {
                validateLineItem(errors, i, items.get(i));
            }
        }

        if (!errors.isEmpty()) {
            throw new BusinessException("FBA_FIELDS_INVALID", String.join("; ", errors));
        }
    }

    private void validateLineItem(List<String> errors, int index, FbaFieldsDto.FbaLineItemDto item) {
        String prefix = "lineItems[" + index + "].";
        if (item == null) {
            errors.add(prefix + "line item is required");
            return;
        }

        // SKU: required, 1–100 chars (Req 16.2, 16.5).
        if (item.getSku() == null || item.getSku().isEmpty()) {
            errors.add(prefix + "sku: SKU is required");
        } else if (item.getSku().length() > SKU_MAX) {
            errors.add(prefix + "sku: SKU must be between 1 and " + SKU_MAX + " characters");
        }

        // MSKU: optional, but when present must be 1–100 chars (Req 16.2, 16.5).
        validateOptionalString(errors, prefix + "msku", "MSKU", item.getMsku(), MSKU_MAX);

        // ASIN: optional, but when present must be 1–20 chars (Req 16.2, 16.5).
        validateOptionalString(errors, prefix + "asin", "ASIN", item.getAsin(), ASIN_MAX);

        // Quantity: required, 1–1,000,000 (Req 16.2, 16.5).
        if (item.getQuantity() == null) {
            errors.add(prefix + "quantity: Quantity is required");
        } else if (item.getQuantity() < QUANTITY_MIN || item.getQuantity() > QUANTITY_MAX) {
            errors.add(prefix + "quantity: Quantity must be between " + QUANTITY_MIN + " and " + QUANTITY_MAX);
        }
    }

    /**
     * Validate an optional string field: {@code null} is permitted (field not
     * provided), but a present value must be between 1 and {@code maxLength}
     * characters — an empty string or an over-length value is rejected (Req 16.5).
     */
    private void validateOptionalString(List<String> errors, String field, String label,
                                        String value, int maxLength) {
        if (value == null) {
            return;
        }
        if (value.isEmpty()) {
            errors.add(field + ": " + label + " must not be empty");
        } else if (value.length() > maxLength) {
            errors.add(field + ": " + label + " must be between 1 and " + maxLength + " characters");
        }
    }

    // --- helpers -----------------------------------------------------------

    private List<ShipmentLineItemEntity> loadLineItems(UUID shipmentUuid) {
        LambdaQueryWrapper<ShipmentLineItemEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShipmentLineItemEntity::getShipmentId, shipmentUuid);
        wrapper.orderByAsc(ShipmentLineItemEntity::getCreatedAt);
        return shipmentLineItemMapper.selectList(wrapper);
    }

    private FbaFieldsVo buildVo(UUID shipmentUuid) {
        ShipmentEntity shipment = shipmentMapper.selectById(shipmentUuid);
        return toVo(shipment, loadLineItems(shipmentUuid));
    }

    private FbaFieldsVo toVo(ShipmentEntity shipment, List<ShipmentLineItemEntity> lineItems) {
        List<FbaFieldsVo.FbaLineItemVo> itemVos = lineItems.stream()
                .map(this::toLineItemVo)
                .collect(Collectors.toList());

        return FbaFieldsVo.builder()
                .shipmentId(shipment.getId() != null ? shipment.getId().toString() : null)
                .fbaShipmentId(shipment.getFbaShipmentId())
                .amazonShipmentStatus(shipment.getAmazonShipmentStatus())
                .destinationFcCode(shipment.getDestinationFcCode())
                .lineItems(itemVos)
                .build();
    }

    private FbaFieldsVo.FbaLineItemVo toLineItemVo(ShipmentLineItemEntity entity) {
        return FbaFieldsVo.FbaLineItemVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .shipmentId(entity.getShipmentId() != null ? entity.getShipmentId().toString() : null)
                .sku(entity.getSku())
                .msku(entity.getMsku())
                .asin(entity.getAsin())
                .quantity(entity.getQuantity())
                .build();
    }

    private UUID parseShipmentId(String shipmentId) {
        if (shipmentId == null || shipmentId.isBlank()) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + shipmentId);
        }
        try {
            return UUID.fromString(shipmentId);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + shipmentId);
        }
    }
}
