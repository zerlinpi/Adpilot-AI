package com.adpilot.modules.procurement.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.procurement.dto.PurchaseOrderDto;
import com.adpilot.modules.procurement.dto.SupplierDto;
import com.adpilot.modules.procurement.service.ProcurementService;
import com.adpilot.modules.procurement.vo.PurchaseOrderVo;
import com.adpilot.modules.procurement.vo.SupplierVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/procurement")
@RequiredArgsConstructor
public class ProcurementController {

    private final ProcurementService procurementService;

    // ==================== Supplier Endpoints ====================

    /**
     * GET /api/procurement/suppliers - List suppliers with pagination.
     */
    @GetMapping("/suppliers")
    @RequirePermission("procurement:view")
    public ApiResponse<PageResponse<SupplierVo>> listSuppliers(
            @RequestParam(required = false) String orgId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<SupplierVo> result = procurementService.listSuppliers(orgId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/procurement/suppliers/{id} - Get supplier by ID.
     */
    @GetMapping("/suppliers/{id}")
    @RequirePermission("procurement:view")
    public ApiResponse<SupplierVo> getSupplier(@PathVariable String id) {
        SupplierVo supplier = procurementService.getSupplierById(id);
        return ApiResponse.ok(supplier);
    }

    /**
     * POST /api/procurement/suppliers - Create a new supplier.
     */
    @PostMapping("/suppliers")
    @RequirePermission("procurement:manage")
    public ApiResponse<SupplierVo> createSupplier(@Valid @RequestBody SupplierDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        SupplierVo supplier = procurementService.createSupplier(dto, userId);
        return ApiResponse.ok(supplier);
    }

    /**
     * PUT /api/procurement/suppliers/{id} - Update an existing supplier.
     */
    @PutMapping("/suppliers/{id}")
    @RequirePermission("procurement:manage")
    public ApiResponse<SupplierVo> updateSupplier(@PathVariable String id, @Valid @RequestBody SupplierDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        SupplierVo supplier = procurementService.updateSupplier(id, dto, userId);
        return ApiResponse.ok(supplier);
    }

    /**
     * DELETE /api/procurement/suppliers/{id} - Delete a supplier.
     */
    @DeleteMapping("/suppliers/{id}")
    @RequirePermission("procurement:manage")
    public ApiResponse<Void> deleteSupplier(@PathVariable String id) {
        procurementService.deleteSupplier(id);
        return ApiResponse.ok(null);
    }

    // ==================== Purchase Order Endpoints ====================

    /**
     * GET /api/procurement/purchase-orders - List purchase orders with pagination.
     */
    @GetMapping("/purchase-orders")
    @RequirePermission("procurement:view")
    public ApiResponse<PageResponse<PurchaseOrderVo>> listPurchaseOrders(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<PurchaseOrderVo> result = procurementService.listPurchaseOrders(storeId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/procurement/purchase-orders/{id} - Get purchase order by ID with items.
     */
    @GetMapping("/purchase-orders/{id}")
    @RequirePermission("procurement:view")
    public ApiResponse<PurchaseOrderVo> getPurchaseOrder(@PathVariable String id) {
        PurchaseOrderVo purchaseOrder = procurementService.getPurchaseOrderById(id);
        return ApiResponse.ok(purchaseOrder);
    }

    /**
     * POST /api/procurement/purchase-orders - Create a new purchase order.
     */
    @PostMapping("/purchase-orders")
    @RequirePermission("procurement:manage")
    public ApiResponse<PurchaseOrderVo> createPurchaseOrder(@Valid @RequestBody PurchaseOrderDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        PurchaseOrderVo purchaseOrder = procurementService.createPurchaseOrder(dto, userId);
        return ApiResponse.ok(purchaseOrder);
    }

    /**
     * PUT /api/procurement/purchase-orders/{id}/status - Update purchase order status.
     */
    @PutMapping("/purchase-orders/{id}/status")
    @RequirePermission("procurement:approve")
    public ApiResponse<PurchaseOrderVo> updatePurchaseOrderStatus(
            @PathVariable String id,
            @RequestParam String status) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        PurchaseOrderVo purchaseOrder = procurementService.updatePurchaseOrderStatus(id, status, userId);
        return ApiResponse.ok(purchaseOrder);
    }
}
