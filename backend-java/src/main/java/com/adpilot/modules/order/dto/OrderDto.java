package com.adpilot.modules.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderDto {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    @NotBlank(message = "Order ID is required")
    private String orderId;

    private String orderItemId;
    private LocalDateTime purchaseDate;
    private LocalDateTime lastUpdateDate;
    private String orderStatus;
    private String fulfillmentChannel;
    private String salesChannel;
    private String marketplaceId;
    private String sku;
    private String asin;
    private String productName;
    private Integer quantityOrdered;
    private BigDecimal itemPrice;
    private BigDecimal itemTax;
    private BigDecimal shippingPrice;
    private BigDecimal shippingTax;
    private BigDecimal itemPromotionDiscount;
    private BigDecimal shipPromotionDiscount;
    private String currency;
    private String buyerEmail;
    private String recipientName;
    private String shipAddressLine1;
    private String shipCity;
    private String shipState;
    private String shipPostalCode;
    private String shipCountry;
    private String rawData;
}
