package com.adpilot.modules.warehouse.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.warehouse.dto.InventoryMovementDto;
import com.adpilot.modules.warehouse.dto.WarehouseInventoryDto;
import com.adpilot.modules.warehouse.dto.WarehouseLocationDto;
import com.adpilot.modules.warehouse.vo.InventoryMovementVo;
import com.adpilot.modules.warehouse.vo.WarehouseInventoryVo;
import com.adpilot.modules.warehouse.vo.WarehouseLocationVo;

public interface WarehouseService {

    /**
     * List warehouse locations with pagination.
     */
    PageResponse<WarehouseLocationVo> listLocations(String orgId, int page, int pageSize);

    /**
     * Get warehouse location by ID.
     */
    WarehouseLocationVo getLocationById(String id);

    /**
     * Create a new warehouse location.
     */
    WarehouseLocationVo createLocation(WarehouseLocationDto dto, String userId);

    /**
     * Update an existing warehouse location.
     */
    WarehouseLocationVo updateLocation(String id, WarehouseLocationDto dto, String userId);

    /**
     * Delete a warehouse location by ID.
     */
    void deleteLocation(String id);

    /**
     * List inventory for a warehouse location with pagination.
     */
    PageResponse<WarehouseInventoryVo> listInventory(String locationId, int page, int pageSize);

    /**
     * Get inventory by SKU at a specific location.
     */
    WarehouseInventoryVo getInventoryBySku(String locationId, String sku);

    /**
     * Update inventory for a specific SKU at a location.
     */
    WarehouseInventoryVo updateInventory(String locationId, String sku, WarehouseInventoryDto dto, String userId);

    /**
     * List inventory movements with pagination.
     */
    PageResponse<InventoryMovementVo> listMovements(String locationId, String sku, int page, int pageSize);

    /**
     * Create a new inventory movement.
     */
    InventoryMovementVo createMovement(InventoryMovementDto dto, String userId);
}
