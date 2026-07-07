package com.adpilot.modules.warehouse.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WarehouseLocationVo {

    private String id;
    private String orgId;
    private String locationName;
    private String locationCode;
    private String locationType;
    private String address;
    private String country;
    private int capacity;
    private String status;
    private String createdAt;
    private String updatedAt;
}
