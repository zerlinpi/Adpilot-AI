package com.adpilot.modules.procurement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class PurchaseOrderDto {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    @NotBlank(message = "Supplier ID is required")
    private String supplierId;

    @NotBlank(message = "PO number is required")
    private String poNumber;

    private String status;
    private BigDecimal totalAmount;
    private String currency;
    private String orderDate;
    private String expectedDeliveryDate;
    private String shippingMethod;
    private String trackingNumber;
    private String notes;
    private List<PurchaseOrderItemDto> items;

    @Data
    public static class PurchaseOrderItemDto {

        private String sku;
        private String asin;
        private String productName;

        @NotNull(message = "Quantity is required")
        private Integer quantityOrdered;

        private BigDecimal unitCost;
        private String notes;
    }
}
