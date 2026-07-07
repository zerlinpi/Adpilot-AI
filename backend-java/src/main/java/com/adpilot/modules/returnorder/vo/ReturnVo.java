package com.adpilot.modules.returnorder.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReturnVo {

    private String id;
    private String storeId;
    private String orderId;
    private String returnId;
    private String sku;
    private String asin;
    private int quantityReturned;
    private String returnReason;
    private String returnStatus;
    private String returnDate;
    private double refundAmount;
    private String currency;
    private String createdAt;
}
