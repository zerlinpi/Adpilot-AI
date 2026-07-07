package com.adpilot.modules.warehouse.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WarehouseInventoryVo {

    private String id;
    private String warehouseLocationId;
    private String sku;
    private String asin;
    private String productName;
    private int quantityOnHand;
    private int quantityReserved;
    private int quantityAvailable;
    private int reorderPoint;
    private int reorderQuantity;
    private double unitCost;
    private String lastCountedAt;
    private String createdAt;
    private String updatedAt;
}
