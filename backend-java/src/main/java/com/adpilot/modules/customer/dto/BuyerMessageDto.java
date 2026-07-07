package com.adpilot.modules.customer.dto;

import lombok.Data;

@Data
public class BuyerMessageDto {

    private String storeId;
    private String orderId;
    private String buyerEmail;
    private String subject;
    private String message;
    private String direction;
    private String status;
    private String reply;
    private String rawData;
}
