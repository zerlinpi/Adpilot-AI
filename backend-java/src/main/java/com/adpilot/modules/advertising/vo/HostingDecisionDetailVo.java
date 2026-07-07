package com.adpilot.modules.advertising.vo;

import com.adpilot.modules.advertising.hosting.DecisionSnapshot;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Decision detail / explanation view for {@code GET /api/advertising/hosting/decisions/{id}}
 * (Req 11.5, 13).
 *
 * <p>Carries the list-item summary, the immutable {@link DecisionSnapshot} (triggering metrics,
 * personality, boundaries, risk, predicted impact), and the effect-attribution results once the
 * measurement window has completed (Req 11.5). The snapshot is the sole source of truth and is
 * never reconstructed from live data (Req 34, 37.5).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostingDecisionDetailVo {

    /** The decision summary (same shape as the list item). */
    @JsonProperty("decision")
    private HostingDecisionVo decision;

    /** The immutable decision snapshot deserialized from {@code ai_decisions.decision_snapshot}. */
    @JsonProperty("snapshot")
    private DecisionSnapshot snapshot;

    /**
     * Effect attribution results for the promoted Operation's measurement window; empty until the
     * window completes or when the decision was never promoted (Req 11.5).
     */
    @JsonProperty("attributions")
    private List<EffectAttributionVo> attributions;
}
