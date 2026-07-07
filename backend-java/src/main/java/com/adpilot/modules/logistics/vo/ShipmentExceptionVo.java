package com.adpilot.modules.logistics.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Response VO for a {@code Shipment_Exception} (异常). Newly raised exceptions
 * are returned in the open state (Req 9.2).
 */
@Data
@Builder
public class ShipmentExceptionVo {

    private String id;
    private String shipmentId;
    private String exceptionType;
    private String description;
    private String resolutionState;
    private String resolvedBy;
    private String resolvedAt;
    private String createdAt;
    private String updatedAt;
}
