package com.adpilot.modules.customer.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BuyerMessageVo {

    private String id;
    private String storeId;
    private String orderId;
    private String buyerEmail;
    private String subject;
    private String message;
    private String direction;
    private String status;
    private String reply;
    private String repliedAt;
    private String rawData;
    private String createdAt;
    private String updatedAt;
}
