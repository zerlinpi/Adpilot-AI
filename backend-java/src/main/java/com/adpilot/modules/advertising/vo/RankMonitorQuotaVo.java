package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Rank-monitoring quota view for the Rank Monitoring page (Req 28.3). Surfaces
 * the consumed and total monitoring quota (for example, 234 of 400) plus the
 * derived remaining count and exhausted flag so the page can block submission
 * when the quota is full (Req 28.4).
 */
@Data
@Builder
public class RankMonitorQuotaVo {

    /** Number of monitoring slots already consumed (active tasks). */
    private int consumed;

    /** Total monitoring quota. */
    private int total;

    /** Remaining slots, never negative. */
    private int remaining;

    /** Whether the quota is exhausted ({@code consumed >= total}). */
    private boolean exhausted;
}
