package com.adpilot.modules.logistics.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Response VO for a {@code Customs_Clearance} (清关) record. When no record
 * exists, services return a not-started state (Req 7.6).
 */
@Data
@Builder
public class CustomsClearanceVo {

    private String id;
    private String shipmentId;
    private String clearanceStatus;
    private String declarationRef;
    private BigDecimal dutiesTaxes;
    private String createdAt;
    private String updatedAt;
}
