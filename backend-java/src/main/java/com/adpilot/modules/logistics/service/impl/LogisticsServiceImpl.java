package com.adpilot.modules.logistics.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.logistics.dto.ShipmentDto;
import com.adpilot.modules.logistics.entity.ShipmentEntity;
import com.adpilot.modules.logistics.entity.ShipmentItemEntity;
import com.adpilot.modules.logistics.filter.ShipmentFilterFields;
import com.adpilot.modules.logistics.mapper.ShipmentItemMapper;
import com.adpilot.modules.logistics.mapper.ShipmentMapper;
import com.adpilot.modules.logistics.service.LogisticsService;
import com.adpilot.modules.logistics.vo.ShipmentVo;
import com.adpilot.modules.tableview.filter.FilterCondition;
import com.adpilot.modules.tableview.filter.FilterFieldSpec;
import com.adpilot.modules.tableview.filter.FilterQueryRequest;
import com.adpilot.modules.tableview.filter.FilterTranslator;
import com.adpilot.modules.tableview.filter.FilterValidator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogisticsServiceImpl implements LogisticsService {

    private final ShipmentMapper shipmentMapper;
    private final ShipmentItemMapper shipmentItemMapper;
    private final FilterTranslator filterTranslator;
    private final DataScopeService dataScopeService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public PageResponse<ShipmentVo> listShipments(String storeId, int page, int pageSize) {
        Page<ShipmentEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<ShipmentEntity> wrapper = new QueryWrapper<>();

        CurrentUser user = SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
        dataScopeService.applyScope(wrapper, ScopeTarget.store("store_id"), user);
        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq("store_id", UUID.fromString(storeId));
        }
        wrapper.orderByDesc("created_at");

        Page<ShipmentEntity> result = shipmentMapper.selectPage(pageParam, wrapper);
        List<ShipmentVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public PageResponse<ShipmentVo> queryShipments(FilterQueryRequest request) {
        FilterQueryRequest req = request != null ? request : new FilterQueryRequest();

        QueryWrapper<ShipmentEntity> wrapper = new QueryWrapper<>();

        // 1) Restrict to the requester's data scope so filtering can never widen
        //    visibility beyond what the user may read.
        CurrentUser user = SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
        dataScopeService.applyScope(wrapper, ScopeTarget.store("store_id"), user);

        // 2) Validate + translate the advanced-filter conditions, combined with AND
        //    on top of the scope predicate (Req 2.9, 2.10). Invalid conditions are
        //    rejected here, leaving the caller's displayed rows unchanged.
        filterTranslator.apply(wrapper, req.getFilters(), ShipmentFilterFields.REGISTRY);

        // 3) Apply sort over an allow-listed field/column, defaulting to newest first.
        applySort(wrapper, req);

        // 4) Page the scoped, filtered, sorted result.
        int page = req.resolvedPage();
        int pageSize = req.resolvedPageSize();
        Page<ShipmentEntity> pageParam = new Page<>(page, pageSize);
        Page<ShipmentEntity> result = shipmentMapper.selectPage(pageParam, wrapper);

        List<ShipmentVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    /**
     * Apply the request's sort to {@code wrapper}. The sort field is resolved to a
     * physical column via the shipment filter registry (an allow-list), so an
     * unknown sort field is rejected rather than interpolated into SQL. When no
     * sort field is supplied, results default to newest-created first.
     */
    private void applySort(QueryWrapper<ShipmentEntity> wrapper, FilterQueryRequest req) {
        String sortField = req.getSortField();
        if (sortField == null || sortField.isBlank()) {
            wrapper.orderByDesc("created_at");
            return;
        }
        FilterFieldSpec spec = ShipmentFilterFields.REGISTRY.get(sortField);
        if (spec == null) {
            throw new BusinessException(FilterValidator.INVALID_CODE,
                    "unknown sort field '" + sortField + "'");
        }
        wrapper.orderBy(true, !req.descending(), spec.column());
    }

    @Override
    public List<String> selectAllMatching(List<FilterCondition> filters) {
        QueryWrapper<ShipmentEntity> wrapper = new QueryWrapper<>();

        // Translate the active advanced-filter descriptor into scoped predicates.
        // Invalid conditions raise a BusinessException before any id is resolved
        // (Req 2.9, 2.10), so a bad filter never returns a partial selection.
        filterTranslator.apply(wrapper, filters, ShipmentFilterFields.REGISTRY);

        // Restrict to the requester's data scope, exactly like the list/query and
        // export endpoints, so "select all" can never reach out-of-scope records.
        CurrentUser user = SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
        dataScopeService.applyScope(wrapper, ScopeTarget.store("store_id"), user);

        // Resolve every matching id across all pages (not just the rendered page).
        // Select only the id column to keep the resolution lightweight.
        wrapper.select("id");
        List<Object> ids = shipmentMapper.selectObjs(wrapper);

        return ids.stream()
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.toList());
    }

    @Override
    public ShipmentVo getShipmentById(String id) {
        ShipmentEntity entity = shipmentMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + id);
        }

        ShipmentVo vo = toVo(entity);

        // Load shipment items
        LambdaQueryWrapper<ShipmentItemEntity> itemWrapper = new LambdaQueryWrapper<>();
        itemWrapper.eq(ShipmentItemEntity::getShipmentId, entity.getId());
        List<ShipmentItemEntity> items = shipmentItemMapper.selectList(itemWrapper);
        vo.setItems(items.stream().map(this::toItemVo).collect(Collectors.toList()));

        return vo;
    }

    @Override
    @Transactional
    public ShipmentVo createShipment(ShipmentDto dto, String userId) {
        ShipmentEntity entity = ShipmentEntity.builder()
                .storeId(UUID.fromString(dto.getStoreId()))
                .shipmentId(dto.getShipmentId())
                .shipmentType(dto.getShipmentType())
                .status("pending")
                .carrier(dto.getCarrier())
                .trackingNumber(dto.getTrackingNumber())
                .shipFromAddress(dto.getShipFromAddress())
                .shipToAddress(dto.getShipToAddress())
                .shipDate(dto.getShipDate())
                .estimatedDeliveryDate(dto.getEstimatedDeliveryDate())
                .totalWeight(dto.getTotalWeight())
                .weightUnit(dto.getWeightUnit())
                .totalItems(dto.getTotalItems() != null ? dto.getTotalItems() : 0)
                .shippingCost(dto.getShippingCost() != null ? dto.getShippingCost() : BigDecimal.ZERO)
                .currency(dto.getCurrency())
                .notes(dto.getNotes())
                .build();

        shipmentMapper.insert(entity);
        log.info("Shipment created: id={}, shipmentId={}", entity.getId(), entity.getShipmentId());

        // Insert shipment items if provided
        if (dto.getItems() != null && !dto.getItems().isEmpty()) {
            for (ShipmentDto.ShipmentItemDto itemDto : dto.getItems()) {
                ShipmentItemEntity item = ShipmentItemEntity.builder()
                        .shipmentId(entity.getId())
                        .orderId(itemDto.getOrderId())
                        .sku(itemDto.getSku())
                        .asin(itemDto.getAsin())
                        .productName(itemDto.getProductName())
                        .quantity(itemDto.getQuantity() != null ? itemDto.getQuantity() : 0)
                        .weight(itemDto.getWeight())
                        .build();
                shipmentItemMapper.insert(item);
            }
            log.info("Inserted {} shipment items for shipment {}", dto.getItems().size(), entity.getId());
        }

        return getShipmentById(entity.getId().toString());
    }

    @Override
    @Transactional
    public ShipmentVo updateShipmentStatus(String id, String status, String userId) {
        ShipmentEntity entity = shipmentMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + id);
        }

        entity.setStatus(status);
        entity.setUpdatedAt(LocalDateTime.now());

        // If status is delivered, set actual delivery date
        if ("delivered".equalsIgnoreCase(status)) {
            entity.setActualDeliveryDate(LocalDate.now());
        }

        shipmentMapper.updateById(entity);
        log.info("Shipment status updated: id={}, status={}", id, status);
        return getShipmentById(id);
    }

    private ShipmentVo toVo(ShipmentEntity entity) {
        return ShipmentVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .shipmentId(entity.getShipmentId())
                .shipmentType(entity.getShipmentType())
                .status(entity.getStatus())
                .carrier(entity.getCarrier())
                .trackingNumber(entity.getTrackingNumber())
                .shipFromAddress(entity.getShipFromAddress())
                .shipToAddress(entity.getShipToAddress())
                .shipDate(entity.getShipDate() != null ? entity.getShipDate().format(DATE_FORMATTER) : null)
                .estimatedDeliveryDate(entity.getEstimatedDeliveryDate() != null ? entity.getEstimatedDeliveryDate().format(DATE_FORMATTER) : null)
                .actualDeliveryDate(entity.getActualDeliveryDate() != null ? entity.getActualDeliveryDate().format(DATE_FORMATTER) : null)
                .totalWeight(entity.getTotalWeight())
                .weightUnit(entity.getWeightUnit())
                .totalItems(entity.getTotalItems() != null ? entity.getTotalItems() : 0)
                .shippingCost(entity.getShippingCost() != null ? entity.getShippingCost() : BigDecimal.ZERO)
                .currency(entity.getCurrency())
                .notes(entity.getNotes())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(DATETIME_FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(DATETIME_FORMATTER) : null)
                .build();
    }

    private ShipmentVo.ShipmentItemVo toItemVo(ShipmentItemEntity entity) {
        return ShipmentVo.ShipmentItemVo.builder()
                .id(entity.getId().toString())
                .shipmentId(entity.getShipmentId().toString())
                .orderId(entity.getOrderId())
                .sku(entity.getSku())
                .asin(entity.getAsin())
                .productName(entity.getProductName())
                .quantity(entity.getQuantity() != null ? entity.getQuantity() : 0)
                .weight(entity.getWeight())
                .build();
    }
}
