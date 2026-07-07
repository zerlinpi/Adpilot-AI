package com.adpilot.modules.warehouse.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.warehouse.dto.InventoryMovementDto;
import com.adpilot.modules.warehouse.dto.WarehouseInventoryDto;
import com.adpilot.modules.warehouse.dto.WarehouseLocationDto;
import com.adpilot.modules.warehouse.service.WarehouseService;
import com.adpilot.modules.warehouse.vo.InventoryMovementVo;
import com.adpilot.modules.warehouse.vo.WarehouseInventoryVo;
import com.adpilot.modules.warehouse.vo.WarehouseLocationVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/warehouse")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;

    // ==================== Location Endpoints ====================

    /**
     * GET /api/warehouse/locations - List warehouse locations with pagination.
     */
    @GetMapping("/locations")
    @RequirePermission("warehouse:view")
    public ApiResponse<PageResponse<WarehouseLocationVo>> listLocations(
            @RequestParam(required = false) String orgId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<WarehouseLocationVo> result = warehouseService.listLocations(orgId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/warehouse/locations/{id} - Get warehouse location by ID.
     */
    @GetMapping("/locations/{id}")
    @RequirePermission("warehouse:view")
    public ApiResponse<WarehouseLocationVo> getLocation(@PathVariable String id) {
        WarehouseLocationVo location = warehouseService.getLocationById(id);
        return ApiResponse.ok(location);
    }

    /**
     * POST /api/warehouse/locations - Create a new warehouse location.
     */
    @PostMapping("/locations")
    @RequirePermission("warehouse:manage")
    public ApiResponse<WarehouseLocationVo> createLocation(@Valid @RequestBody WarehouseLocationDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        WarehouseLocationVo location = warehouseService.createLocation(dto, userId);
        return ApiResponse.ok(location);
    }

    /**
     * PUT /api/warehouse/locations/{id} - Update an existing warehouse location.
     */
    @PutMapping("/locations/{id}")
    @RequirePermission("warehouse:manage")
    public ApiResponse<WarehouseLocationVo> updateLocation(
            @PathVariable String id,
            @Valid @RequestBody WarehouseLocationDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        WarehouseLocationVo location = warehouseService.updateLocation(id, dto, userId);
        return ApiResponse.ok(location);
    }

    /**
     * DELETE /api/warehouse/locations/{id} - Delete a warehouse location.
     */
    @DeleteMapping("/locations/{id}")
    @RequirePermission("warehouse:manage")
    public ApiResponse<Void> deleteLocation(@PathVariable String id) {
        warehouseService.deleteLocation(id);
        return ApiResponse.ok(null);
    }

    // ==================== Inventory Endpoints ====================

    /**
     * GET /api/warehouse/inventory - List inventory with pagination.
     */
    @GetMapping("/inventory")
    @RequirePermission("warehouse:view")
    public ApiResponse<PageResponse<WarehouseInventoryVo>> listInventory(
            @RequestParam(required = false) String locationId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<WarehouseInventoryVo> result = warehouseService.listInventory(locationId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/warehouse/inventory/{sku} - Get inventory by SKU.
     */
    @GetMapping("/inventory/{sku}")
    @RequirePermission("warehouse:view")
    public ApiResponse<WarehouseInventoryVo> getInventoryBySku(
            @RequestParam String locationId,
            @PathVariable String sku) {
        WarehouseInventoryVo inventory = warehouseService.getInventoryBySku(locationId, sku);
        return ApiResponse.ok(inventory);
    }

    /**
     * PUT /api/warehouse/inventory/{sku} - Update inventory for a specific SKU.
     */
    @PutMapping("/inventory/{sku}")
    @RequirePermission("warehouse:manage")
    public ApiResponse<WarehouseInventoryVo> updateInventory(
            @RequestParam String locationId,
            @PathVariable String sku,
            @Valid @RequestBody WarehouseInventoryDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        WarehouseInventoryVo inventory = warehouseService.updateInventory(locationId, sku, dto, userId);
        return ApiResponse.ok(inventory);
    }

    // ==================== Movement Endpoints ====================

    /**
     * GET /api/warehouse/movements - List inventory movements with pagination.
     */
    @GetMapping("/movements")
    @RequirePermission("warehouse:view")
    public ApiResponse<PageResponse<InventoryMovementVo>> listMovements(
            @RequestParam(required = false) String locationId,
            @RequestParam(required = false) String sku,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<InventoryMovementVo> result = warehouseService.listMovements(locationId, sku, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/warehouse/movements - Create a new inventory movement.
     */
    @PostMapping("/movements")
    @RequirePermission("warehouse:manage")
    public ApiResponse<InventoryMovementVo> createMovement(@Valid @RequestBody InventoryMovementDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        InventoryMovementVo movement = warehouseService.createMovement(dto, userId);
        return ApiResponse.ok(movement);
    }
}
