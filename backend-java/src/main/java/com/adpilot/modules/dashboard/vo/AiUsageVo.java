package com.adpilot.modules.dashboard.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * AI Usage panel (AI使用) of the AI advertising dashboard (Req 18.4): the AI
 * coverage percentage (share of ad spend under AI hosting, clamped to
 * {@code [0,100]} by {@code AdMetrics}) plus the AI ad spend and AI ad sales for
 * the active store.
 */
@Data
@Builder
public class AiUsageVo {

    /** Reporting currency the monetary metrics are expressed in. */
    private String currency;

    /** AI coverage percentage in {@code [0,100]} (AI ad spend / total ad spend). */
    private BigDecimal coveragePercent;

    /** Ad spend attributed to AI-managed campaigns. */
    private BigDecimal aiAdSpend;

    /** Ad sales attributed to AI-managed campaigns. */
    private BigDecimal aiAdSales;
}
