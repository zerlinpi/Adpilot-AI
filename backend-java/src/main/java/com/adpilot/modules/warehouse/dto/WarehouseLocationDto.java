package com.adpilot.modules.warehouse.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WarehouseLocationDto {

    private String orgId;

    @NotBlank(message = "Location name is required")
    private String locationName;

    private String locationCode;
    private String locationType;
    private String address;
    private String country;
    private Integer capacity;
}
