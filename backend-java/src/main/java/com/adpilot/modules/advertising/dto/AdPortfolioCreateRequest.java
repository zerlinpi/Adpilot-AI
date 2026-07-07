package com.adpilot.modules.advertising.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Payload for {@code POST /api/ad-portfolios} — create an ad portfolio
 * (Req 20.2). Only {@code storeId} and {@code name} are required; the budget is
 * optional and, when omitted with a {@code none} budget type, the portfolio has
 * no budget cap (无预算上限, Req 20.3).
 */
@Data
public class AdPortfolioCreateRequest {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    @NotBlank(message = "Portfolio name is required")
    private String name;

    /** Targeting status (投放状态); defaults to {@code enabled} when omitted. */
    private String state;

    /** Budget type (预算类型): {@code none} | {@code recurring} | {@code date_range}; defaults to {@code none}. */
    private String budgetType;

    /** Budget cap (预算); {@code null} => no budget cap. */
    private BigDecimal budget;

    private String startDate;
    private String endDate;
    private String externalId;
}
