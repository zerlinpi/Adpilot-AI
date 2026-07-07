package com.adpilot.modules.logistics.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Response VO for a {@code Shipment_Leg} (头程/尾程 segment).
 */
@Data
@Builder
public class ShipmentLegVo {

    private String id;
    private String shipmentId;
    private String legType;
    private Integer sequenceNo;
    private String carrierId;
    private String carrierName;
    private String departureDate;
    private String arrivalDate;
    private BigDecimal legCost;
    private String createdAt;
    private String updatedAt;
}
