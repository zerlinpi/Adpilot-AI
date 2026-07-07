package com.adpilot.modules.procurement.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PurchaseOrderVo {

    private String id;
    private String storeId;
    private String supplierId;
    private String poNumber;
    private String status;
    private Double totalAmount;
    private String currency;
    private String orderDate;
    private String expectedDeliveryDate;
    private String actualDeliveryDate;
    private String shippingMethod;
    private String trackingNumber;
    private String notes;
    private String createdBy;
    private String approvedBy;
    private String approvedAt;
    private String createdAt;
    private String updatedAt;
    private List<PurchaseOrderItemVo> items;

    @Data
    @Builder
    public static class PurchaseOrderItemVo {

        private String id;
        private String purchaseOrderId;
        private String sku;
        private String asin;
        private String productName;
        private Integer quantityOrdered;
        private Integer quantityReceived;
        private Double unitCost;
        private Double totalCost;
        private String currency;
        private String notes;
        private String createdAt;
        private String updatedAt;
    }
}
