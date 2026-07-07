package com.adpilot.modules.order.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderVo {

    private String id;
    private String storeId;
    private String orderId;
    private String orderItemId;
    private String purchaseDate;
    private String lastUpdateDate;
    private String orderStatus;
    private String fulfillmentChannel;
    private String salesChannel;
    private String marketplaceId;
    private String sku;
    private String asin;
    private String productName;
    private int quantityOrdered;
    private double itemPrice;
    private double itemTax;
    private double shippingPrice;
    private double shippingTax;
    private double itemPromotionDiscount;
    private double shipPromotionDiscount;
    private String currency;
    private String buyerEmail;
    private String recipientName;
    private String shipAddressLine1;
    private String shipCity;
    private String shipState;
    private String shipPostalCode;
    private String shipCountry;
    private String rawData;
    private String createdAt;
}
