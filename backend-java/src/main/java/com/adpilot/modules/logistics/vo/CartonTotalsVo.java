package com.adpilot.modules.logistics.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Response VO for the aggregated carton totals of a shipment: total box count
 * (sum of box counts) and total unit quantity (sum of units-per-box × box
 * count). An empty set yields totals of 0 and 0 (Req 5.3, 5.4).
 */
@Data
@Builder
public class CartonTotalsVo {

    private String shipmentId;
    private long totalBoxCount;
    private long totalUnitQuantity;
}
