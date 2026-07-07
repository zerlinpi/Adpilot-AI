package com.adpilot.modules.warehouse.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.warehouse.dto.InventoryMovementDto;
import com.adpilot.modules.warehouse.dto.WarehouseInventoryDto;
import com.adpilot.modules.warehouse.dto.WarehouseLocationDto;
import com.adpilot.modules.warehouse.entity.InventoryMovementEntity;
import com.adpilot.modules.warehouse.entity.WarehouseInventoryEntity;
import com.adpilot.modules.warehouse.entity.WarehouseLocationEntity;
import com.adpilot.modules.warehouse.mapper.InventoryMovementMapper;
import com.adpilot.modules.warehouse.mapper.WarehouseInventoryMapper;
import com.adpilot.modules.warehouse.mapper.WarehouseLocationMapper;
import com.adpilot.modules.warehouse.service.WarehouseService;
import com.adpilot.modules.warehouse.vo.InventoryMovementVo;
import com.adpilot.modules.warehouse.vo.WarehouseInventoryVo;
import com.adpilot.modules.warehouse.vo.WarehouseLocationVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WarehouseServiceImpl implements WarehouseService {

    private final WarehouseLocationMapper locationMapper;
    private final WarehouseInventoryMapper inventoryMapper;
    private final InventoryMovementMapper movementMapper;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ==================== Location Methods ====================

    @Override
    public PageResponse<WarehouseLocationVo> listLocations(String orgId, int page, int pageSize) {
        Page<WarehouseLocationEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<WarehouseLocationEntity> wrapper = new LambdaQueryWrapper<>();
        UUID effectiveOrgId = resolveEffectiveOrgId(orgId, false);
        if (effectiveOrgId != null) {
            wrapper.eq(WarehouseLocationEntity::getOrgId, effectiveOrgId);
        }
        wrapper.orderByDesc(WarehouseLocationEntity::getCreatedAt);

        Page<WarehouseLocationEntity> result = locationMapper.selectPage(pageParam, wrapper);
        List<WarehouseLocationVo> voList = result.getRecords().stream()
                .map(this::toLocationVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public WarehouseLocationVo getLocationById(String id) {
        WarehouseLocationEntity entity = locationMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("WAREHOUSE_LOCATION_NOT_FOUND", "Warehouse location not found: " + id);
        }
        assertCurrentOrgAccess(entity);
        return toLocationVo(entity);
    }

    @Override
    @Transactional
    public WarehouseLocationVo createLocation(WarehouseLocationDto dto, String userId) {
        WarehouseLocationEntity entity = WarehouseLocationEntity.builder()
                .orgId(resolveEffectiveOrgId(dto.getOrgId(), true))
                .locationName(dto.getLocationName())
                .locationCode(dto.getLocationCode())
                .locationType(dto.getLocationType())
                .address(dto.getAddress())
                .country(dto.getCountry())
                .capacity(dto.getCapacity() != null ? dto.getCapacity() : 0)
                .status("active")
                .build();

        locationMapper.insert(entity);
        log.info("Warehouse location created: id={}, name={}", entity.getId(), entity.getLocationName());
        return toLocationVo(entity);
    }

    @Override
    @Transactional
    public WarehouseLocationVo updateLocation(String id, WarehouseLocationDto dto, String userId) {
        WarehouseLocationEntity entity = locationMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("WAREHOUSE_LOCATION_NOT_FOUND", "Warehouse location not found: " + id);
        }
        assertCurrentOrgAccess(entity);

        if (dto.getLocationName() != null) {
            entity.setLocationName(dto.getLocationName());
        }
        if (dto.getLocationCode() != null) {
            entity.setLocationCode(dto.getLocationCode());
        }
        if (dto.getLocationType() != null) {
            entity.setLocationType(dto.getLocationType());
        }
        if (dto.getAddress() != null) {
            entity.setAddress(dto.getAddress());
        }
        if (dto.getCountry() != null) {
            entity.setCountry(dto.getCountry());
        }
        if (dto.getCapacity() != null) {
            entity.setCapacity(dto.getCapacity());
        }

        entity.setUpdatedAt(LocalDateTime.now());

        locationMapper.updateById(entity);
        log.info("Warehouse location updated: id={}", id);
        return toLocationVo(entity);
    }

    @Override
    @Transactional
    public void deleteLocation(String id) {
        WarehouseLocationEntity entity = locationMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("WAREHOUSE_LOCATION_NOT_FOUND", "Warehouse location not found: " + id);
        }
        assertCurrentOrgAccess(entity);
        locationMapper.deleteById(UUID.fromString(id));
        log.info("Warehouse location deleted: id={}", id);
    }

    // ==================== Inventory Methods ====================

    @Override
    public PageResponse<WarehouseInventoryVo> listInventory(String locationId, int page, int pageSize) {
        Page<WarehouseInventoryEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<WarehouseInventoryEntity> wrapper = new LambdaQueryWrapper<>();
        if (locationId != null && !locationId.isEmpty()) {
            UUID locationUuid = UUID.fromString(locationId);
            assertLocationAccessible(locationUuid);
            wrapper.eq(WarehouseInventoryEntity::getWarehouseLocationId, locationUuid);
        } else {
            applyCurrentOrgLocationScope(wrapper);
        }
        wrapper.orderByDesc(WarehouseInventoryEntity::getCreatedAt);

        Page<WarehouseInventoryEntity> result = inventoryMapper.selectPage(pageParam, wrapper);
        List<WarehouseInventoryVo> voList = result.getRecords().stream()
                .map(this::toInventoryVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public WarehouseInventoryVo getInventoryBySku(String locationId, String sku) {
        assertLocationAccessible(UUID.fromString(locationId));
        LambdaQueryWrapper<WarehouseInventoryEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WarehouseInventoryEntity::getWarehouseLocationId, UUID.fromString(locationId))
                .eq(WarehouseInventoryEntity::getSku, sku);

        WarehouseInventoryEntity entity = inventoryMapper.selectOne(wrapper);
        if (entity == null) {
            throw new BusinessException("INVENTORY_NOT_FOUND",
                    "Inventory not found for location=" + locationId + ", sku=" + sku);
        }
        return toInventoryVo(entity);
    }

    @Override
    @Transactional
    public WarehouseInventoryVo updateInventory(String locationId, String sku, WarehouseInventoryDto dto, String userId) {
        assertLocationAccessible(UUID.fromString(locationId));
        LambdaQueryWrapper<WarehouseInventoryEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WarehouseInventoryEntity::getWarehouseLocationId, UUID.fromString(locationId))
                .eq(WarehouseInventoryEntity::getSku, sku);

        WarehouseInventoryEntity entity = inventoryMapper.selectOne(wrapper);
        if (entity == null) {
            throw new BusinessException("INVENTORY_NOT_FOUND",
                    "Inventory not found for location=" + locationId + ", sku=" + sku);
        }

        if (dto.getAsin() != null) {
            entity.setAsin(dto.getAsin());
        }
        if (dto.getProductName() != null) {
            entity.setProductName(dto.getProductName());
        }
        if (dto.getQuantityOnHand() != null) {
            entity.setQuantityOnHand(dto.getQuantityOnHand());
        }
        if (dto.getQuantityReserved() != null) {
            entity.setQuantityReserved(dto.getQuantityReserved());
        }
        if (dto.getQuantityAvailable() != null) {
            entity.setQuantityAvailable(dto.getQuantityAvailable());
        }
        if (dto.getReorderPoint() != null) {
            entity.setReorderPoint(dto.getReorderPoint());
        }
        if (dto.getReorderQuantity() != null) {
            entity.setReorderQuantity(dto.getReorderQuantity());
        }
        if (dto.getUnitCost() != null) {
            entity.setUnitCost(dto.getUnitCost());
        }

        entity.setUpdatedAt(LocalDateTime.now());

        inventoryMapper.updateById(entity);
        log.info("Inventory updated: locationId={}, sku={}", locationId, sku);
        return toInventoryVo(entity);
    }

    // ==================== Movement Methods ====================

    @Override
    public PageResponse<InventoryMovementVo> listMovements(String locationId, String sku, int page, int pageSize) {
        Page<InventoryMovementEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<InventoryMovementEntity> wrapper = new LambdaQueryWrapper<>();
        if (locationId != null && !locationId.isEmpty()) {
            UUID locationUuid = UUID.fromString(locationId);
            assertLocationAccessible(locationUuid);
            wrapper.eq(InventoryMovementEntity::getWarehouseLocationId, locationUuid);
        } else {
            applyCurrentOrgMovementScope(wrapper);
        }
        if (sku != null && !sku.isEmpty()) {
            wrapper.eq(InventoryMovementEntity::getSku, sku);
        }
        wrapper.orderByDesc(InventoryMovementEntity::getCreatedAt);

        Page<InventoryMovementEntity> result = movementMapper.selectPage(pageParam, wrapper);
        List<InventoryMovementVo> voList = result.getRecords().stream()
                .map(this::toMovementVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    @Transactional
    public InventoryMovementVo createMovement(InventoryMovementDto dto, String userId) {
        assertLocationAccessible(UUID.fromString(dto.getWarehouseLocationId()));
        InventoryMovementEntity entity = InventoryMovementEntity.builder()
                .warehouseLocationId(UUID.fromString(dto.getWarehouseLocationId()))
                .sku(dto.getSku())
                .movementType(dto.getMovementType())
                .quantity(dto.getQuantity())
                .referenceType(dto.getReferenceType())
                .referenceId(dto.getReferenceId() != null ? UUID.fromString(dto.getReferenceId()) : null)
                .notes(dto.getNotes())
                .createdBy(userId != null ? UUID.fromString(userId) : null)
                .build();

        movementMapper.insert(entity);
        log.info("Inventory movement created: id={}, sku={}, type={}, qty={}",
                entity.getId(), entity.getSku(), entity.getMovementType(), entity.getQuantity());
        return toMovementVo(entity);
    }

    private UUID resolveEffectiveOrgId(String requestedOrgId, boolean required) {
        String candidate = SecurityUtils.isAuthenticated()
                ? SecurityUtils.getCurrentOrgId()
                : requestedOrgId;
        if (candidate == null || candidate.isBlank()) {
            if (required) {
                throw new BusinessException("ORG_REQUIRED", "Organization ID is required");
            }
            return null;
        }
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("ORG_INVALID", "Invalid organization ID");
        }
    }

    private void assertCurrentOrgAccess(WarehouseLocationEntity location) {
        if (!SecurityUtils.isAuthenticated()) {
            return;
        }
        UUID currentOrgId = resolveEffectiveOrgId(null, true);
        if (!currentOrgId.equals(location.getOrgId())) {
            throw new BusinessException("WAREHOUSE_LOCATION_NOT_FOUND",
                    "Warehouse location not found: " + location.getId());
        }
    }

    private void assertLocationAccessible(UUID locationId) {
        WarehouseLocationEntity location = locationMapper.selectById(locationId);
        if (location == null) {
            throw new BusinessException("WAREHOUSE_LOCATION_NOT_FOUND",
                    "Warehouse location not found: " + locationId);
        }
        assertCurrentOrgAccess(location);
    }

    private List<UUID> currentOrgLocationIds() {
        if (!SecurityUtils.isAuthenticated()) {
            return null;
        }
        UUID currentOrgId = resolveEffectiveOrgId(null, true);
        LambdaQueryWrapper<WarehouseLocationEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WarehouseLocationEntity::getOrgId, currentOrgId)
                .select(WarehouseLocationEntity::getId);
        return locationMapper.selectList(wrapper).stream()
                .map(WarehouseLocationEntity::getId)
                .collect(Collectors.toList());
    }

    private void applyCurrentOrgLocationScope(LambdaQueryWrapper<WarehouseInventoryEntity> wrapper) {
        List<UUID> locationIds = currentOrgLocationIds();
        if (locationIds != null) {
            if (locationIds.isEmpty()) {
                wrapper.apply("1 = 0");
            } else {
                wrapper.in(WarehouseInventoryEntity::getWarehouseLocationId, locationIds);
            }
        }
    }

    private void applyCurrentOrgMovementScope(LambdaQueryWrapper<InventoryMovementEntity> wrapper) {
        List<UUID> locationIds = currentOrgLocationIds();
        if (locationIds != null) {
            if (locationIds.isEmpty()) {
                wrapper.apply("1 = 0");
            } else {
                wrapper.in(InventoryMovementEntity::getWarehouseLocationId, locationIds);
            }
        }
    }
    // ==================== VO Conversion Helpers ====================

    private WarehouseLocationVo toLocationVo(WarehouseLocationEntity entity) {
        return WarehouseLocationVo.builder()
                .id(entity.getId().toString())
                .orgId(entity.getOrgId().toString())
                .locationName(entity.getLocationName())
                .locationCode(entity.getLocationCode())
                .locationType(entity.getLocationType())
                .address(entity.getAddress())
                .country(entity.getCountry())
                .capacity(entity.getCapacity() != null ? entity.getCapacity() : 0)
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private WarehouseInventoryVo toInventoryVo(WarehouseInventoryEntity entity) {
        return WarehouseInventoryVo.builder()
                .id(entity.getId().toString())
                .warehouseLocationId(entity.getWarehouseLocationId().toString())
                .sku(entity.getSku())
                .asin(entity.getAsin())
                .productName(entity.getProductName())
                .quantityOnHand(entity.getQuantityOnHand() != null ? entity.getQuantityOnHand() : 0)
                .quantityReserved(entity.getQuantityReserved() != null ? entity.getQuantityReserved() : 0)
                .quantityAvailable(entity.getQuantityAvailable() != null ? entity.getQuantityAvailable() : 0)
                .reorderPoint(entity.getReorderPoint() != null ? entity.getReorderPoint() : 0)
                .reorderQuantity(entity.getReorderQuantity() != null ? entity.getReorderQuantity() : 0)
                .unitCost(entity.getUnitCost() != null ? entity.getUnitCost().doubleValue() : 0.0)
                .lastCountedAt(entity.getLastCountedAt() != null ? entity.getLastCountedAt().format(FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private InventoryMovementVo toMovementVo(InventoryMovementEntity entity) {
        return InventoryMovementVo.builder()
                .id(entity.getId().toString())
                .warehouseLocationId(entity.getWarehouseLocationId().toString())
                .sku(entity.getSku())
                .movementType(entity.getMovementType())
                .quantity(entity.getQuantity() != null ? entity.getQuantity() : 0)
                .referenceType(entity.getReferenceType())
                .referenceId(entity.getReferenceId() != null ? entity.getReferenceId().toString() : null)
                .notes(entity.getNotes())
                .createdBy(entity.getCreatedBy() != null ? entity.getCreatedBy().toString() : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }
}
