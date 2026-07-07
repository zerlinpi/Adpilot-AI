package com.adpilot.modules.tableview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request DTO for saving a {@code Column_Configuration}.
 *
 * <p>Keyed by the requesting user and {@code tableKey}, store-independent
 * (Req 2.12). The {@code config} string holds column visibility/order/pin state
 * as JSON.</p>
 */
@Data
public class ColumnConfigDto {

    @NotBlank(message = "Table key is required")
    @Size(min = 1, max = 100, message = "Table key must be between 1 and 100 characters")
    private String tableKey;

    /** Column visibility/order/pin state, serialized as JSON. */
    private String config;
}
