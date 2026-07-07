package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/**
 * AI Notification configuration view (Req 23.5) — the per-store settings that
 * control which core-ops items are raised.
 */
@Data
@Builder
public class AiNotificationConfigVo {

    private String storeId;

    /** Configuration blob controlling which core-ops items are raised. */
    private JsonNode config;

    private String updatedAt;
}
