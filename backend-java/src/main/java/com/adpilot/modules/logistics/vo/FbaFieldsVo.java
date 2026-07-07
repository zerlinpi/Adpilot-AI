package com.adpilot.modules.logistics.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Response VO for the core {@code FBA_Shipment_Fields} plus line items.
 */
@Data
@Builder
public class FbaFieldsVo {

    private String shipmentId;
    private String fbaShipmentId;
    private String amazonShipmentStatus;
    private String destinationFcCode;
    private List<FbaLineItemVo> lineItems;

    /**
     * A single FBA shipment line item.
     */
    @Data
    @Builder
    public static class FbaLineItemVo {
        private String id;
        private String shipmentId;
        private String sku;
        private String msku;
        private String asin;
        private Integer quantity;
    }
}
