package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response view for {@code GET /api/advertising/hosting/analytics} (Req 29).
 *
 * <p>Aggregates decision counts and success rates computed over {@code ai_decisions} joined to
 * {@code operations} (Req 29.2, 29.4) plus effect-attribution aggregates. Every impact figure is
 * an <strong>estimate</strong> derived from {@code estimated_incremental_impact}, never the raw
 * observed change (Req 29.3).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostingAnalyticsVo {

    @JsonProperty("store_id")
    private String storeId;

    /** Requested period: 7d, 30d, or 90d. */
    @JsonProperty("period")
    private String period;

    @JsonProperty("total_decisions")
    private long totalDecisions;

    @JsonProperty("auto_executed_count")
    private long autoExecutedCount;

    @JsonProperty("approval_required_count")
    private long approvalRequiredCount;

    /** Decisions promoted to an Operation that was actually submitted to the platform. */
    @JsonProperty("attempted_count")
    private long attemptedCount;

    /** Promoted operations that reached the effective state. */
    @JsonProperty("effective_count")
    private long effectiveCount;

    /** effective / attempted; 0 when nothing was attempted (Req 29.2). */
    @JsonProperty("success_rate")
    private BigDecimal successRate;

    @JsonProperty("average_risk_score")
    private BigDecimal averageRiskScore;

    /** Most common failure reasons, descending by count (Req 29.2). */
    @JsonProperty("top_failure_reasons")
    private List<FailureReasonVo> topFailureReasons;

    // ---- Effect-attribution aggregates (all estimates, Req 29.3) ----

    @JsonProperty("average_acos_improvement")
    private BigDecimal averageAcosImprovement;

    @JsonProperty("total_estimated_spend_saved")
    private BigDecimal totalEstimatedSpendSaved;

    @JsonProperty("total_estimated_sales_lift")
    private BigDecimal totalEstimatedSalesLift;

    @JsonProperty("average_attribution_confidence")
    private BigDecimal averageAttributionConfidence;

    /** Per-engine breakdown partitioning the totals (Req 29.4). */
    @JsonProperty("per_engine")
    private List<EngineBreakdownVo> perEngine;

    /** Label clarifying impact figures are estimates (Req 29.3). */
    @JsonProperty("impact_label")
    @Builder.Default
    private String impactLabel = "estimate";

    /**
     * A failure reason and how many failed operations carried it (Req 29.2).
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FailureReasonVo {

        @JsonProperty("reason")
        private String reason;

        @JsonProperty("count")
        private long count;
    }

    /**
     * Per-engine success rate and estimated impact (Req 29.4).
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EngineBreakdownVo {

        @JsonProperty("engine")
        private String engine;

        @JsonProperty("total_decisions")
        private long totalDecisions;

        @JsonProperty("attempted_count")
        private long attemptedCount;

        @JsonProperty("effective_count")
        private long effectiveCount;

        @JsonProperty("success_rate")
        private BigDecimal successRate;

        @JsonProperty("estimated_spend_saved")
        private BigDecimal estimatedSpendSaved;
    }
}
