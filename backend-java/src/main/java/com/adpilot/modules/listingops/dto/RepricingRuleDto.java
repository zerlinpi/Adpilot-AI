package com.adpilot.modules.listingops.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RepricingRuleDto {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    private String asin;

    private String sku;

    @NotBlank(message = "Rule name is required")
    private String ruleName;

    private BigDecimal minPrice;

    private BigDecimal maxPrice;

    private String strategy;
}
