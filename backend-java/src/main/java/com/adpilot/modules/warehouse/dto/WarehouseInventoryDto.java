package com.adpilot.modules.warehouse.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class WarehouseInventoryDto {

    private String asin;
    private String productName;
    private Integer quantityOnHand;
    private Integer quantityReserved;
    private Integer quantityAvailable;
    private Integer reorderPoint;
    private Integer reorderQuantity;
    private BigDecimal unitCost;
}
