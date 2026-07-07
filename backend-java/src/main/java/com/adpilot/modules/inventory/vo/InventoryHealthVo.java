package com.adpilot.modules.inventory.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class InventoryHealthVo {

    private double totalInventoryValue;
    private int lowStockCount;
    private int overstockCount;
    private int avgDaysOfSupply;
    private List<InventoryItemVo> stockoutRisks;
    private List<InventoryItemVo> overstockRisks;
    private List<InventoryItemVo> notSafeToScale;
    private List<InventoryItemVo> clearanceCandidates;
}
