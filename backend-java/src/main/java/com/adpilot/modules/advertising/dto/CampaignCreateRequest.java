package com.adpilot.modules.advertising.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Payload for {@code POST /api/campaigns} — create a Campaign in the All Search
 * Ads workspace (Req 19.6). Only {@code storeId} and {@code name} are required;
 * the remaining fields are optional and default sensibly when omitted.
 */
@Data
public class CampaignCreateRequest {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    @NotBlank(message = "Campaign name is required")
    private String name;

    /** Ad type (SP / SB / SD); stored as the campaign type. */
    private String campaignType;

    /** Owning goal, optional. */
    private String goalId;

    /** Owning ad portfolio, optional. */
    private String portfolioId;

    /** Legacy free-text portfolio label, optional. */
    private String portfolio;

    /** Initial status; defaults to {@code enabled} when omitted. */
    private String status;

    private BigDecimal budget;

    /** Budget type ({@code daily} / {@code lifetime}); defaults to {@code daily}. */
    private String budgetType;

    private String startDate;
    private String endDate;

    /** Targeting type (自动 / 手动 → auto / manual). */
    private String targetingType;

    private List<String> tags;
}
