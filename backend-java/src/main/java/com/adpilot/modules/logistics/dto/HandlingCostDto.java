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
 * Request DTO for a {@code Handling_Cost} (费用) line on a shipment.
 * Bounds (Req 18.1): amount 0.00–999,999,999.99, currency code exactly 3
 * chars, description/category 1–200 chars. Exchange rate and cost date are
 * optional conversion provenance used by the cost chain.
 */
@Data
public class HandlingCostDto {

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.00", message = "Amount must be greater than or equal to 0.00")
    @DecimalMax(value = "999999999.99", message = "Amount must be less than or equal to 999,999,999.99")
    private BigDecimal amount;

    @NotBlank(message = "Currency code is required")
    @Size(min = 3, max = 3, message = "Currency code must be exactly 3 characters")
    private String currencyCode;

    @NotBlank(message = "Description is required")
    @Size(min = 1, max = 200, message = "Description must be between 1 and 200 characters")
    private String description;

    /** Optional per-component exchange rate used for cost-chain conversion. */
    private BigDecimal exchangeRate;

    /** Optional cost date used to resolve a store rate for conversion. */
    private LocalDate costDate;
}
