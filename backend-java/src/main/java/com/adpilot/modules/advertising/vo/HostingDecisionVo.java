package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Decision list-item view for {@code GET /api/advertising/hosting/decisions} (Req 11.4).
 *
 * <p>Each item summarizes an {@code ai_decisions} row joined to its promoted Operation (when one
 * exists) so the dashboard can show campaign name, decision type, proposed change, risk score,
 * the current SyncState, and timestamp (Req 11.4).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostingDecisionVo {

    @JsonProperty("id")
    private String id;

    @JsonProperty("store_id")
    private String storeId;

    @JsonProperty("campaign_id")
    private String campaignId;

    /** Campaign display name resolved from the campaigns table; null when unresolved. */
    @JsonProperty("campaign_name")
    private String campaignName;

    /** Producing engine: v1_bid, v2_budget, v3_keyword. */
    @JsonProperty("engine")
    private String engine;

    /** Decision type: bid_adjustment, budget_adjustment, keyword_addition, negative_keyword_addition. */
    @JsonProperty("decision_type")
    private String decisionType;

    /** Resolved execution mode at decision time. */
    @JsonProperty("execution_mode")
    private String executionMode;

    /** Routing outcome from the DecisionRoutingPipeline. */
    @JsonProperty("routing_outcome")
    private String routingOutcome;

    /** Risk score in [0.0, 1.0]. */
    @JsonProperty("risk_score")
    private BigDecimal riskScore;

    /** Changed field from the promoted Operation; null when the decision was not promoted. */
    @JsonProperty("field")
    private String field;

    /** Before value (JSON) from the promoted Operation; null when not promoted. */
    @JsonProperty("before_value")
    private Object beforeValue;

    /** Proposed/after value (JSON) from the promoted Operation; null when not promoted. */
    @JsonProperty("after_value")
    private Object afterValue;

    /** The promoted Operation id; null for observe/recommend decisions. */
    @JsonProperty("promoted_operation_id")
    private String promotedOperationId;

    /** Current SyncState of the promoted Operation (machine value); null when not promoted. */
    @JsonProperty("sync_state")
    private String syncState;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("expires_at")
    private LocalDateTime expiresAt;
}
