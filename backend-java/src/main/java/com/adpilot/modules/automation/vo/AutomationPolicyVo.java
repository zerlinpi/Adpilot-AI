package com.adpilot.modules.automation.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class AutomationPolicyVo {

    private String id;
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
    private String createdAt;
    private String updatedAt;
}
