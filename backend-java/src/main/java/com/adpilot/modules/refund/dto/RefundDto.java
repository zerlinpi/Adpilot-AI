package com.adpilot.modules.refund.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RefundDto {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    private String orderId;

    private String refundId;

    private String sku;

    private String asin;

    private BigDecimal refundAmount;

    private String refundReason;

    private String refundStatus;

    private LocalDateTime refundDate;

    private String currency;

    private String rawData;
}
