package com.adpilot.modules.logistics.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class ShipmentDto {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    private String shipmentId;
    private String shipmentType;
    private String carrier;
    private String trackingNumber;
    private String shipFromAddress;
    private String shipToAddress;
    private LocalDate shipDate;
    private LocalDate estimatedDeliveryDate;
    private BigDecimal totalWeight;
    private String weightUnit;
    private Integer totalItems;
    private BigDecimal shippingCost;
    private String currency;
    private String notes;
    private List<ShipmentItemDto> items;

    @Data
    public static class ShipmentItemDto {
        private String orderId;
        private String sku;
        private String asin;
        private String productName;
        private Integer quantity;
        private BigDecimal weight;
    }
}
