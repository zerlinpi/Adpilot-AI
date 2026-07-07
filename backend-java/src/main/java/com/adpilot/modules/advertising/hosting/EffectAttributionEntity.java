package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity mapping to the {@code effect_attributions} table (Req 8.3).
 *
 * <p>Records the post-execution measurement of an Operation's impact on
 * advertising metrics. Each row captures a single metric's observed change,
 * estimated incremental impact (nullable when no reliable baseline exists),
 * and attribution confidence score.</p>
 *
 * <p>Validates: Requirements 8.1, 8.2, 8.3, 8.5.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("effect_attributions")
@Table(name = "effect_attributions")
public class EffectAttributionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "operation_id", nullable = false, columnDefinition = "char(36)")
    private UUID operationId;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    /** Metric type: impressions, clicks, spend, sales, orders, acos. */
    @Column(name = "metric_type", nullable = false, length = 30)
    private String metricType;

    /** Raw before/after delta: post-window metric − pre-window metric. */
    @Column(name = "observed_change", precision = 18, scale = 6)
    private BigDecimal observedChange;

    /**
     * Portion plausibly attributable to the AI action.
     * Null when no reliable baseline/control is available (Req 8.2).
     */
    @Column(name = "estimated_incremental_impact", precision = 18, scale = 6)
    private BigDecimal estimatedIncrementalImpact;

    /** Confidence score 0.0–1.0 based on data quality and overlap (Req 8.2, 8.5). */
    @Column(name = "attribution_confidence", precision = 6, scale = 5)
    private BigDecimal attributionConfidence;

    /** Method used: e.g., naive_before_after, seasonally_adjusted. */
    @Column(name = "attribution_method", length = 40)
    private String attributionMethod;

    /** Version of the attribution method used (for auditability). */
    @Column(name = "method_version", length = 20)
    private String methodVersion;

    /** Start of the measurement window (when operation reached effective). */
    @Column(name = "measurement_window_start")
    private LocalDateTime measurementWindowStart;

    /** End of the measurement window (start + measurement duration). */
    @Column(name = "measurement_window_end")
    private LocalDateTime measurementWindowEnd;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
