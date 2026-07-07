package com.adpilot.modules.procurement.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.procurement.dto.PurchaseOrderDto;
import com.adpilot.modules.procurement.dto.SupplierDto;
import com.adpilot.modules.procurement.vo.PurchaseOrderVo;
import com.adpilot.modules.procurement.vo.SupplierVo;

public interface ProcurementService {

    /**
     * List suppliers with pagination, filtered by orgId.
     */
    PageResponse<SupplierVo> listSuppliers(String orgId, int page, int pageSize);

    /**
     * Get supplier by ID.
     */
    SupplierVo getSupplierById(String id);

    /**
     * Create a new supplier.
     */
    SupplierVo createSupplier(SupplierDto dto, String userId);

    /**
     * Update an existing supplier.
     */
    SupplierVo updateSupplier(String id, SupplierDto dto, String userId);

    /**
     * Delete a supplier by ID.
     */
    void deleteSupplier(String id);

    /**
     * List purchase orders with pagination, filtered by storeId.
     */
    PageResponse<PurchaseOrderVo> listPurchaseOrders(String storeId, int page, int pageSize);

    /**
     * Get purchase order by ID, including its items.
     */
    PurchaseOrderVo getPurchaseOrderById(String id);

    /**
     * Create a new purchase order with items.
     */
    PurchaseOrderVo createPurchaseOrder(PurchaseOrderDto dto, String userId);

    /**
     * Update purchase order status.
     */
    PurchaseOrderVo updatePurchaseOrderStatus(String id, String status, String userId);
}
