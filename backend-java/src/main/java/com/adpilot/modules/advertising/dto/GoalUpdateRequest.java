package com.adpilot.modules.advertising.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class GoalUpdateRequest {

    private String name;
    private String type;
    private String status;

    private BigDecimal targetAcos;
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
