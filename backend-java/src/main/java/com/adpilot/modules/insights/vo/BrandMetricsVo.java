package com.adpilot.modules.insights.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Brand metrics Data Insights surface (Req 30.3).
 *
 * <p>Category-brand metrics (total brand customers, engagement rate, conversion
 * rate, new-to-brand sales share) originate from Amazon Brand Analytics, which
 * this project does not ingest. When that source is not activated the surface
 * returns {@code requiresActivation = true} with a message and null metrics
 * rather than fabricating Amazon-side figures (Req 30.7, 30.8).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrandMetricsVo {

    /** True when the brand-analytics data source has not been activated. */
    private boolean requiresActivation;

    /** Human-readable explanation shown by the activation / empty state. */
    private String message;

    /** 品牌顾客总数 — total brand customers. */
    private Long totalBrandCustomers;

    /** 顾客互动率 — engagement rate, as a percentage. */
    private BigDecimal engagementRate;

    /** 顾客转化率 — conversion rate, as a percentage. */
    private BigDecimal conversionRate;

    /** 品牌新客销售额占比 — new-to-brand sales share, as a percentage. */
    private BigDecimal newToBrandSalesShare;
}
