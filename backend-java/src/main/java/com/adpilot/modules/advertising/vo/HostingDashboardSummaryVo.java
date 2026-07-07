package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response view for {@code GET /api/advertising/hosting/dashboard/summary} (Req 11.1, 27.1).
 *
 * <p>All counts are computed against real data filtered by the Active_Store and "today"
 * in the store's Marketplace_Timezone (Req 11.2, 27.2). Estimated savings are derived from
 * {@code effect_attributions} and are explicitly labelled as estimates (Req 11.3, 27.3) —
 * never presented as realized/observed figures.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostingDashboardSummaryVo {

    /** Owning store id. */
    @JsonProperty("store_id")
    private String storeId;

    /** Total managed (hosted) campaigns count (Req 11.1, 27.1). */
    @JsonProperty("hosted_campaigns_count")
    private long hostedCampaignsCount;

    /** Today's AI decisions count, in marketplace timezone (Req 11.1, 27.1, 27.2). */
    @JsonProperty("today_decisions_count")
    private long todayDecisionsCount;

    /** Decisions awaiting approval count (Req 11.1, 27.1). */
    @JsonProperty("awaiting_approval_count")
    private long awaitingApprovalCount;

    /** Operations that became effective today, in marketplace timezone (Req 11.1, 27.1). */
    @JsonProperty("effective_today_count")
    private long effectiveTodayCount;

    /** Operations that failed today, in marketplace timezone (Req 11.1, 27.1). */
    @JsonProperty("failed_today_count")
    private long failedTodayCount;

    /** Estimated spend savings over the trailing 7 days (Req 27.1, 27.3) — an estimate. */
    @JsonProperty("estimated_savings_7d")
    private BigDecimal estimatedSavings7d;

    /** Estimated spend savings over the trailing 30 days (Req 27.1, 27.3) — an estimate. */
    @JsonProperty("estimated_savings_30d")
    private BigDecimal estimatedSavings30d;

    /**
     * Explicit label clarifying that the savings figures are estimates derived from
     * {@code estimated_incremental_impact}, never realized values (Req 11.3, 27.3).
     */
    @JsonProperty("estimated_savings_label")
    @Builder.Default
    private String estimatedSavingsLabel = "estimate";

    /** Number of hosted campaigns currently in their learning period (Req 19.5). */
    @JsonProperty("learning_period_campaigns_count")
    private long learningPeriodCampaignsCount;

    /** Per-campaign learning-period status for hosted campaigns still in their window (Req 19.5). */
    @JsonProperty("learning_periods")
    private List<LearningPeriodVo> learningPeriods;

    /**
     * Learning-period status for a single campaign (Req 19.5).
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LearningPeriodVo {

        @JsonProperty("campaign_id")
        private String campaignId;

        @JsonProperty("days_remaining")
        private int daysRemaining;

        @JsonProperty("total_days")
        private int totalDays;
    }
}
