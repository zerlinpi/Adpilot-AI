package com.adpilot.modules.returnorder.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ReturnDto {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    private String orderId;

    private String returnId;

    private String sku;

    private String asin;

    private Integer quantityReturned;

    private String returnReason;

    private String returnStatus;

    private LocalDateTime returnDate;

    private BigDecimal refundAmount;

    private String currency;

    private String rawData;
}
