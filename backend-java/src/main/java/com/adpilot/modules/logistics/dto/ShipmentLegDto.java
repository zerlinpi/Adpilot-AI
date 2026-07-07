package com.adpilot.modules.logistics.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request DTO for recording/updating a {@code Shipment_Leg} (头程/尾程 segment).
 * Bounds (Req 4.1): leg_type/sequence/carrier/dates required, leg cost
 * 0.00–999,999,999.99. The arrival ≥ departure and leg-count ≤ 20 business
 * rules are enforced in the service layer.
 */
@Data
public class ShipmentLegDto {

    @NotBlank(message = "Leg type is required")
    @Size(max = 50, message = "Leg type must not exceed 50 characters")
    private String legType;

    @NotNull(message = "Sequence number is required")
    private Integer sequenceNo;

    @NotBlank(message = "Carrier is required")
    private String carrierId;

    @NotNull(message = "Departure date is required")
    private LocalDate departureDate;

    @NotNull(message = "Arrival date is required")
    private LocalDate arrivalDate;

    @NotNull(message = "Leg cost is required")
    @DecimalMin(value = "0.00", message = "Leg cost must be greater than or equal to 0.00")
    @DecimalMax(value = "999999999.99", message = "Leg cost must be less than or equal to 999,999,999.99")
    private BigDecimal legCost;
}
