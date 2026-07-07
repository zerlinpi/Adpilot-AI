package com.adpilot.modules.supplier.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.supplier.dto.SupplierDto;
import com.adpilot.modules.supplier.vo.SupplierVo;

public interface SupplierService {

    /**
     * List suppliers with pagination.
     */
    PageResponse<SupplierVo> listSuppliers(int page, int pageSize);

    /**
     * Get supplier by ID.
     */
    SupplierVo getSupplierById(String id);

    /**
     * Create a new supplier.
     */
    SupplierVo createSupplier(SupplierDto dto);

    /**
     * Update an existing supplier.
     */
    SupplierVo updateSupplier(String id, SupplierDto dto);

    /**
     * Delete a supplier by ID.
     */
    void deleteSupplier(String id);
}
