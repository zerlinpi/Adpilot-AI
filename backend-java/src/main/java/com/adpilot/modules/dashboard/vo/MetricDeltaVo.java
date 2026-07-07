package com.adpilot.modules.dashboard.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * A single dashboard metric value paired with its period-over-period change
 * (Req 18.1). {@link #value} is the metric over the selected date range,
 * {@link #previous} the same metric over the immediately preceding period of
 * equal length, {@link #delta} their difference, and {@link #deltaPct} the
 * percentage change relative to {@link #previous} (0 when the previous value is
 * zero, so the result is always finite).
 */
@Data
@Builder
public class MetricDeltaVo {

    /** Metric value over the selected period. */
    private BigDecimal value;

    /** Metric value over the immediately preceding period of equal length. */
    private BigDecimal previous;

    /** {@code value - previous}. */
    private BigDecimal delta;

    /** Percentage change relative to {@link #previous}; 0 when previous is 0. */
    private BigDecimal deltaPct;
}
