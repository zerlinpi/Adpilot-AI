package com.adpilot.modules.advertising.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class GoalCreateRequest {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    @NotBlank(message = "Goal name is required")
    private String name;

    @NotBlank(message = "Goal type is required")
    private String type;

    @NotNull(message = "Target ACoS is required")
    private BigDecimal targetAcos;

    @NotNull(message = "Daily budget is required")
    private BigDecimal dailyBudget;

    private BigDecimal maxCpc;
    private BigDecimal minBid;
    private BigDecimal maxBid;

    private List<String> brandKeywords;
    private List<String> categoryKeywords;
    private List<String> competitorBrands;
    private List<String> competitorAsins;

    private Boolean autoNegate;
    private Boolean autoBid;
    private Boolean autoExpand;

    private String optimizeFrequency;
    private String riskPreference;

    private List<String> productIds;
}
