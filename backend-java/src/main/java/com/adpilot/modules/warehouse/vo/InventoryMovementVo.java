package com.adpilot.modules.warehouse.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InventoryMovementVo {

    private String id;
    private String warehouseLocationId;
    private String sku;
    private String movementType;
    private int quantity;
    private String referenceType;
    private String referenceId;
    private String notes;
    private String createdBy;
    private String createdAt;
}
