package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response body for {@code GET /api/advertising/hosting/optimization-runs/{runId}} (Req 28.7).
 *
 * <p>Surfaces a triggered run's status, aggregate counts, per-campaign results, and skip reasons
 * so the frontend can poll a manual (or scheduled) run to completion and render the decisions /
 * operations it generated. {@code per_campaign_results} and {@code skip_reasons} are returned as
 * parsed JSON objects (campaign_id &rarr; detail).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OptimizationRunDetailVo {

    /** The optimization run id. */
    @JsonProperty("run_id")
    private String runId;

    /** The store the run belongs to. */
    @JsonProperty("store_id")
    private String storeId;

    /** Trigger type: {@code scheduled} or {@code manual}. */
    @JsonProperty("trigger_type")
    private String triggerType;

    /** Run status: {@code running}, {@code completed}, or {@code failed}. */
    @JsonProperty("status")
    private String status;

    /** The hosting phase (V1/V2/V3) active at run time. */
    @JsonProperty("phase")
    private String phase;

    @JsonProperty("campaigns_processed")
    private Integer campaignsProcessed;

    @JsonProperty("campaigns_skipped")
    private Integer campaignsSkipped;

    @JsonProperty("operations_created")
    private Integer operationsCreated;

    @JsonProperty("decisions_generated")
    private Integer decisionsGenerated;

    @JsonProperty("decisions_auto_executed")
    private Integer decisionsAutoExecuted;

    @JsonProperty("decisions_requiring_approval")
    private Integer decisionsRequiringApproval;

    /** Per-campaign result detail (campaign_id &rarr; result), parsed from the run's JSON column. */
    @JsonProperty("per_campaign_results")
    private Object perCampaignResults;

    /** Per-campaign skip reasons (campaign_id &rarr; reason code), parsed from the run's JSON column. */
    @JsonProperty("skip_reasons")
    private Object skipReasons;

    @JsonProperty("started_at")
    private LocalDateTime startedAt;

    @JsonProperty("completed_at")
    private LocalDateTime completedAt;

    /** Correlation id echoed back for tracing (Req 26.5). */
    @JsonProperty("request_id")
    private String requestId;
}
