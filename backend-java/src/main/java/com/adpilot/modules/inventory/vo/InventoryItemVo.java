package com.adpilot.modules.inventory.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InventoryItemVo {

    private String id;
    private String sku;
    private String productName;
    private int inventory;
    private int dailyVelocity;
    private int daysOfSupply;
    private String stockoutDate;
    private String risk;
    private double inventoryValue;
    private String recommendation;
}
