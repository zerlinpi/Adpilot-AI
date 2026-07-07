package com.adpilot.modules.inventory.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ReplenishmentPlanUpdateRequest {

    private Integer recommendedQty;
    private Integer approvedQty;
    private String reason;
    private LocalDate expectedStockoutDate;
    private LocalDate expectedArrivalDate;
    private BigDecimal purchaseCost;
    private BigDecimal shippingCost;
}
