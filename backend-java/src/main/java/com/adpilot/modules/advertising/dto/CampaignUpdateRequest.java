package com.adpilot.modules.advertising.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Partial-update payload for a campaign, sent as a JSON body by the All Search
 * Ads workspace (e.g. the budget editor sends {@code {"dailyBudget": 60}}).
 *
 * <p>All fields are optional; only the non-null ones are applied. The daily
 * budget is accepted under the field name {@code dailyBudget} (what the frontend
 * sends); {@code budget} is supported as a legacy alias.
 */
@Data
public class CampaignUpdateRequest {

    private String name;
    private String status;
    private BigDecimal dailyBudget;
    /** Legacy alias for {@link #dailyBudget}. */
    private BigDecimal budget;

    /** The effective budget value, preferring {@code dailyBudget} over the alias. */
    public BigDecimal resolveBudget() {
        return dailyBudget != null ? dailyBudget : budget;
    }
}
