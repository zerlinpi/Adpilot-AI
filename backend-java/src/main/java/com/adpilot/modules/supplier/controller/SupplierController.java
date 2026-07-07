package com.adpilot.modules.supplier.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.supplier.dto.SupplierDto;
import com.adpilot.modules.supplier.service.SupplierService;
import com.adpilot.modules.supplier.vo.SupplierVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    /**
     * GET /api/suppliers - List suppliers with pagination.
     */
    @GetMapping
    public ApiResponse<PageResponse<SupplierVo>> listSuppliers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<SupplierVo> result = supplierService.listSuppliers(page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/suppliers/{id} - Get supplier by ID.
     */
    @GetMapping("/{id}")
    public ApiResponse<SupplierVo> getSupplier(@PathVariable String id) {
        SupplierVo supplier = supplierService.getSupplierById(id);
        return ApiResponse.ok(supplier);
    }

    /**
     * POST /api/suppliers - Create a new supplier.
     */
    @PostMapping
    public ApiResponse<SupplierVo> createSupplier(@Valid @RequestBody SupplierDto dto) {
        SupplierVo supplier = supplierService.createSupplier(dto);
        return ApiResponse.ok(supplier);
    }

    /**
     * PUT /api/suppliers/{id} - Update an existing supplier.
     */
    @PutMapping("/{id}")
    public ApiResponse<SupplierVo> updateSupplier(@PathVariable String id, @Valid @RequestBody SupplierDto dto) {
        SupplierVo supplier = supplierService.updateSupplier(id, dto);
        return ApiResponse.ok(supplier);
    }

    /**
     * DELETE /api/suppliers/{id} - Delete a supplier.
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteSupplier(@PathVariable String id) {
        supplierService.deleteSupplier(id);
        return ApiResponse.ok(null);
    }
}
