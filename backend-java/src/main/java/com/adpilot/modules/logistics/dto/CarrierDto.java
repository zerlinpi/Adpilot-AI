package com.adpilot.modules.logistics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request DTO for creating/updating a {@code Carrier} (承运商).
 * Bounds (Req 6.1): name 1–200 chars, service type 1–100 chars.
 */
@Data
public class CarrierDto {

    @NotBlank(message = "Carrier name is required")
    @Size(min = 1, max = 200, message = "Carrier name must be between 1 and 200 characters")
    private String name;

    @NotBlank(message = "Service type is required")
    @Size(min = 1, max = 100, message = "Service type must be between 1 and 100 characters")
    private String serviceType;
}
