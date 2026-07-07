package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * View of a single {@code effect_attributions} row for the Decision_Explanation card (Req 11.5, 13).
 *
 * <p>The {@code estimatedIncrementalImpact} is the honest, baseline-aware estimate and may be
 * {@code null} when no reliable baseline exists (Req 8.2); the raw {@code observedChange} is the
 * naive before/after delta and is never presented as the attributable impact.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EffectAttributionVo {

    @JsonProperty("metric_type")
    private String metricType;

    @JsonProperty("observed_change")
    private BigDecimal observedChange;

    @JsonProperty("estimated_incremental_impact")
    private BigDecimal estimatedIncrementalImpact;

    @JsonProperty("attribution_confidence")
    private BigDecimal attributionConfidence;

    @JsonProperty("attribution_method")
    private String attributionMethod;

    @JsonProperty("method_version")
    private String methodVersion;

    @JsonProperty("measurement_window_start")
    private LocalDateTime measurementWindowStart;

    @JsonProperty("measurement_window_end")
    private LocalDateTime measurementWindowEnd;
}
