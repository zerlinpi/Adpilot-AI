package com.adpilot.modules.insights.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Product list Data Insights surface (Req 30.1, 30.2).
 *
 * <p>Carries the per-product performance rows (product list / 商品列表) together
 * with the analyst's custom-report quota (自定义报告). All amounts are expressed
 * in {@link #currency}. Metrics are computed from the project's own stored data
 * ({@code products}, {@code performance_daily}, {@code orders}); no Amazon-side
 * data is fabricated.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductInsightsVo {

    /** Reporting currency the monetary metrics are expressed in. */
    private String currency;

    /** Per-product performance rows (Req 30.1). */
    private List<ProductListItemVo> items;

    /** Consumed custom-report count for the scope (Req 30.2). */
    private long customReportConsumed;

    /** Total custom-report quota for the scope (Req 30.2). */
    private long customReportTotal;

    /** The analyst's scheduled custom reports (Req 30.2). */
    private List<CustomReportVo> customReports;
}
