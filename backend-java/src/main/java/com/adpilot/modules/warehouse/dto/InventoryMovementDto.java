package com.adpilot.modules.warehouse.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class InventoryMovementDto {

    @NotBlank(message = "Warehouse location ID is required")
    private String warehouseLocationId;

    @NotBlank(message = "SKU is required")
    private String sku;

    @NotBlank(message = "Movement type is required")
    private String movementType;

    @NotNull(message = "Quantity is required")
    private Integer quantity;

    private String referenceType;
    private String referenceId;
    private String notes;
}
