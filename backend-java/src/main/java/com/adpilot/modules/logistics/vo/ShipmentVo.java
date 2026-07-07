package com.adpilot.modules.logistics.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class ShipmentVo {

    private String id;
    private String storeId;
    private String shipmentId;
    private String shipmentType;
    private String status;
    private String carrier;
    private String trackingNumber;
    private String shipFromAddress;
    private String shipToAddress;
    private String shipDate;
    private String estimatedDeliveryDate;
    private String actualDeliveryDate;
    private BigDecimal totalWeight;
    private String weightUnit;
    private Integer totalItems;
    private BigDecimal shippingCost;
    private String currency;
    private String notes;
    private String createdAt;
    private String updatedAt;
    private List<ShipmentItemVo> items;

    @Data
    @Builder
    public static class ShipmentItemVo {
        private String id;
        private String shipmentId;
        private String orderId;
        private String sku;
        private String asin;
        private String productName;
        private Integer quantity;
        private BigDecimal weight;
    }
}
