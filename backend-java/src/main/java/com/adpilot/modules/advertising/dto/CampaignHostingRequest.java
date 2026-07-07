package com.adpilot.modules.advertising.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * JSON body for {@code PUT /api/campaigns/{id}/hosting} — place a Campaign under
 * AI_Hosting by assigning a Hosting_Goal and a Target_ACoS (Req 21.1).
 *
 * <p>{@code targetAcos} is required and must be positive (Req 21.6): a hosting
 * request without a Target_ACoS is rejected with a validation error rather than
 * being persisted. {@code hostingGoal} is optional and defaults to
 * {@code maximize_sales_at_target} when omitted.
 */
@Data
public class CampaignHostingRequest {

    /** Target ACoS (目标ACOS) as a percentage; required (Req 21.6). */
    @NotNull(message = "Target ACoS is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Target ACoS must be positive")
    private BigDecimal targetAcos;

    /** Hosting goal (托管目标); defaults to {@code maximize_sales_at_target} when omitted. */
    private String hostingGoal;
}
