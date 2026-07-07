package com.adpilot.modules.logistics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Request DTO for a {@code Tracking_Event} (轨迹) entry.
 * Bounds (Req 8.1): timestamp required, description 1–500 chars, optional leg
 * reference.
 */
@Data
public class TrackingEventDto {

    @NotNull(message = "Event timestamp is required")
    private LocalDateTime eventTime;

    @NotBlank(message = "Description is required")
    @Size(min = 1, max = 500, message = "Description must be between 1 and 500 characters")
    private String description;

    /** Optional reference to an associated shipment leg; may be unset. */
    private String legId;
}
