package com.adpilot.modules.logistics.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Response VO for a {@code Carrier} (承运商).
 */
@Data
@Builder
public class CarrierVo {

    private String id;
    private String orgId;
    private String name;
    private String serviceType;
    private String createdAt;
    private String updatedAt;
}
