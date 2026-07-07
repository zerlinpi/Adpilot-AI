package com.adpilot.modules.insights.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Search Query Performance (SQP分析) Data Insights surface (Req 30.5):
 * brand-versus-market funnel metrics per search query (click-through rate,
 * add-to-cart rate, conversion rate, impressions, impression share).
 *
 * <p>SQP draws on Amazon Brand Analytics search-query data, which this project
 * does not ingest; when that source is not activated the surface returns
 * {@code requiresActivation = true} with no rows rather than fabricating
 * Amazon-side data (Req 30.7, 30.8).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SqpVo {

    private boolean requiresActivation;

    private String message;

    /** Per-query funnel rows; empty when the source is not activated. */
    private List<SqpRowVo> rows;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SqpRowVo {
        private String searchQuery;
        /** 曝光量 — impressions. */
        private Long impressions;
        /** 曝光份额 — impression share, as a percentage. */
        private BigDecimal impressionShare;
        /** Brand click-through rate, as a percentage. */
        private BigDecimal brandClickRate;
        /** Market click-through rate, as a percentage. */
        private BigDecimal marketClickRate;
        /** Brand add-to-cart rate, as a percentage. */
        private BigDecimal brandAddToCartRate;
        /** Market add-to-cart rate, as a percentage. */
        private BigDecimal marketAddToCartRate;
        /** Brand conversion rate, as a percentage. */
        private BigDecimal brandConversionRate;
        /** Market conversion rate, as a percentage. */
        private BigDecimal marketConversionRate;
    }
}
