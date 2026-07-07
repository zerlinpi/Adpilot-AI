package com.adpilot.modules.logistics.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Response VO for a {@code Handling_Cost} (费用) line. Returns amount,
 * currency, and description (Req 18.3) alongside optional conversion
 * provenance.
 */
@Data
@Builder
public class HandlingCostVo {

    private String id;
    private String shipmentId;
    private BigDecimal amount;
    private String currencyCode;
    private String description;
    private BigDecimal exchangeRate;
    private String costDate;
    private String createdAt;
    private String updatedAt;
}
