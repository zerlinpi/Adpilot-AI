package com.adpilot.modules.advertising.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Payload for {@code PUT /api/ai-notifications/config} — update the per-store
 * configuration that controls which core-ops AI notifications are raised
 * (Req 23.5). Upserts the single config row for the store.
 */
@Data
public class AiNotificationConfigRequest {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    /** Configuration blob (which core-ops items are raised). */
    @NotNull(message = "Configuration is required")
    private JsonNode config;
}
