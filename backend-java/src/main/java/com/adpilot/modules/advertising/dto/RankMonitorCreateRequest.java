package com.adpilot.modules.advertising.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Payload for {@code POST /api/rank-monitor/tasks} — add a keyword
 * rank-monitoring task (Req 28.1). {@code storeId} and {@code keywordText} are
 * required; {@code productId} is optional.
 */
@Data
public class RankMonitorCreateRequest {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    /** Optional product (parent ASIN's product) the keyword belongs to. */
    private String productId;

    @NotBlank(message = "Keyword is required")
    @Size(max = 255, message = "Keyword must be at most 255 characters")
    private String keywordText;
}
