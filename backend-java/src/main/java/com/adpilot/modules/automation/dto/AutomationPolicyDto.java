package com.adpilot.modules.automation.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class AutomationPolicyDto {

    @NotBlank(message = "orgId is required")
    private String orgId;

    private String storeId;

    private String mode;

    private Integer maxBidChangePct;

    private Integer maxBudgetChangePct;

    private Integer minClicksBeforeNegative;

    private Boolean blockBrandNegative;

    private Boolean blockCompetitorInListing;

    private Integer inventoryThreshold;

    private Integer minDaysOfSupplyToScale;

    private Boolean requireApprovalForHighRisk;

    private Boolean requireApprovalForProductUpload;

    private Boolean requireApprovalForReplenishment;

    private BigDecimal dailyBudgetLimit;
}
