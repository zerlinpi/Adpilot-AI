package com.adpilot.modules.logistics.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Request DTO for the core {@code FBA_Shipment_Fields} plus line items.
 * Bounds (Req 16.1, 16.2): FBA shipment id 1–100, Amazon status, destination
 * FC code 1–50, line items (SKU/MSKU 1–100, ASIN 1–20, quantity 1–1,000,000).
 */
@Data
public class FbaFieldsDto {

    @Size(min = 1, max = 100, message = "FBA shipment ID must be between 1 and 100 characters")
    private String fbaShipmentId;

    @Size(max = 50, message = "Amazon shipment status must not exceed 50 characters")
    private String amazonShipmentStatus;

    @Size(min = 1, max = 50, message = "Destination FC code must be between 1 and 50 characters")
    private String destinationFcCode;

    @Valid
    private List<FbaLineItemDto> lineItems;

    /**
     * A single FBA shipment line item (SKU/MSKU/ASIN with a quantity).
     */
    @Data
    public static class FbaLineItemDto {

        @NotBlank(message = "SKU is required")
        @Size(min = 1, max = 100, message = "SKU must be between 1 and 100 characters")
        private String sku;

        @Size(min = 1, max = 100, message = "MSKU must be between 1 and 100 characters")
        private String msku;

        @Size(min = 1, max = 20, message = "ASIN must be between 1 and 20 characters")
        private String asin;

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        @Max(value = 1_000_000, message = "Quantity must not exceed 1,000,000")
        private Integer quantity;
    }
}
