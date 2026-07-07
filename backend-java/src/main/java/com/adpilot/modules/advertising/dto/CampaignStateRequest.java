package com.adpilot.modules.advertising.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * JSON body for {@code PATCH /api/campaigns/{id}/state} — the enable/pause
 * toggle (Req 19.5). Accepts {@code enable} / {@code enabled} / {@code active}
 * (→ enabled) and {@code pause} / {@code paused} (→ paused); other tokens are
 * rejected by the service with a domain error.
 */
@Data
public class CampaignStateRequest {

    @NotBlank(message = "State is required")
    private String state;
}
