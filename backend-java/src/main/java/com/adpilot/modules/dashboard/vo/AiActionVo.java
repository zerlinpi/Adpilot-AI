package com.adpilot.modules.dashboard.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * A single AI_Action row of the AI Actions panel (Req 18.3): the action's key
 * and Chinese label, the number of times it ran over the period, and the
 * measured impact derivable from {@code automation_executions} (the count of
 * distinct affected campaigns/entities and an optional impact amount, e.g.
 * revenue protected or spend saved).
 */
@Data
@Builder
public class AiActionVo {

    /** Stable action key, e.g. {@code keyword_harvesting}. */
    private String key;

    /** Human-readable Chinese label, e.g. 关键词收割. */
    private String label;

    /** Number of executions of this action over the period. */
    @Builder.Default
    private long count = 0L;

    /** Distinct campaigns/entities affected by this action over the period. */
    @Builder.Default
    private long affectedCampaigns = 0L;

    /** Optional measured impact amount (revenue protected / spend saved); may be {@code null}. */
    private BigDecimal impactValue;

    /** Label describing what {@link #impactValue} measures, e.g. 节省花费; may be {@code null}. */
    private String impactLabel;
}
