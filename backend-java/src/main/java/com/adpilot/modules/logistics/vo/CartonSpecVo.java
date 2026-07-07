package com.adpilot.modules.logistics.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Response VO for a {@code Carton_Spec} (箱规) entry.
 */
@Data
@Builder
public class CartonSpecVo {

    private String id;
    private String shipmentId;
    private BigDecimal boxLengthCm;
    private BigDecimal boxWidthCm;
    private BigDecimal boxHeightCm;
    private BigDecimal boxWeightKg;
    private Integer unitsPerBox;
    private Integer boxCount;
    private String createdAt;
    private String updatedAt;
}
