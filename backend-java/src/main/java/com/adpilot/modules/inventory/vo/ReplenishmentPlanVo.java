package com.adpilot.modules.inventory.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReplenishmentPlanVo {

    private String id;
    private String sku;
    private String productName;
    private String status;
    private int recommendedQty;
    private Integer approvedQty;
    private String reason;
    private String expectedStockoutDate;
    private String expectedArrivalDate;
    private Double purchaseCost;
    private Double shippingCost;
    private String createdAt;
}
