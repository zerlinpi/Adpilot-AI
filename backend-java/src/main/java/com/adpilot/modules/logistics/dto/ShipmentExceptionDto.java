package com.adpilot.modules.logistics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request DTO for raising a {@code Shipment_Exception} (异常).
 * Bounds (Req 9.1): type one of delay/damage/customs-hold, description
 * 1–1000 chars.
 */
@Data
public class ShipmentExceptionDto {

    @NotBlank(message = "Exception type is required")
    @Pattern(
            regexp = "delay|damage|customs-hold",
            message = "Exception type must be one of delay, damage, customs-hold")
    private String exceptionType;

    @NotBlank(message = "Description is required")
    @Size(min = 1, max = 1000, message = "Description must be between 1 and 1000 characters")
    private String description;
}
