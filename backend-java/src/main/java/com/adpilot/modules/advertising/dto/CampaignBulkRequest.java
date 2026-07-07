package com.adpilot.modules.advertising.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Payload for {@code POST /api/campaigns/bulk} — apply one operation to several
 * Campaigns at once (Req 19.7). The backend applies {@code operation} to each
 * id independently and returns a per-item result so a single failure does not
 * abort the others.
 *
 * <p>Supported operations: {@code enable}, {@code pause}, {@code delete}.
 */
@Data
public class CampaignBulkRequest {

    @NotEmpty(message = "At least one campaign id is required")
    private List<String> ids;

    @NotBlank(message = "Operation is required")
    private String operation;
}
