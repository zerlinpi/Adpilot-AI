package com.adpilot.modules.advertising.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Payload for {@code PUT /api/ad-portfolios/{id}} — update an ad portfolio
 * (Req 20). All fields are optional; only the supplied (non-null) fields are
 * applied. Set {@code budgetType} to {@code none} to clear a budget cap.
 */
@Data
public class AdPortfolioUpdateRequest {

    private String name;

    /** Targeting status (投放状态). */
    private String state;

    /** Budget type (预算类型): {@code none} | {@code recurring} | {@code date_range}. */
    private String budgetType;

    /** Budget cap (预算); {@code null} leaves the existing value unchanged. */
    private BigDecimal budget;

    private String startDate;
    private String endDate;
    private String externalId;
}
