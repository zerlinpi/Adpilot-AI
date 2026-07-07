package com.adpilot.modules.tableview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request DTO for saving a {@code Saved_View}.
 *
 * <p>Bounds (Req 2.11, 2.13): {@code name} 1–100 characters; the view is scoped
 * to the requesting user and {@code tableKey}, independent of any Store. The
 * {@code config} string holds the column configuration, filters, and sort order
 * as JSON.</p>
 */
@Data
public class SavedViewDto {

    @NotBlank(message = "Table key is required")
    @Size(min = 1, max = 100, message = "Table key must be between 1 and 100 characters")
    private String tableKey;

    @NotBlank(message = "Saved view name is required")
    @Size(min = 1, max = 100, message = "Saved view name must be between 1 and 100 characters")
    private String name;

    /** Column configuration + filters + sort order, serialized as JSON. */
    private String config;
}
