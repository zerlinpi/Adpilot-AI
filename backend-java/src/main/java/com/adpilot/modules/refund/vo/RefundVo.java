package com.adpilot.modules.refund.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RefundVo {

    private String id;
    private String storeId;
    private String orderId;
    private String refundId;
    private String sku;
    private String asin;
    private double refundAmount;
    private String refundReason;
    private String refundStatus;
    private String refundDate;
    private String currency;
    private String createdAt;
}
