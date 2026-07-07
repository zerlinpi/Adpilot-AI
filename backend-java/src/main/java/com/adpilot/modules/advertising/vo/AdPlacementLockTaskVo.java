package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Ad Placement Lock task row for the 任务管理 (task management) tab (Req 26.2).
 * Each task represents the enforcement state for one keyword under a strategy:
 * the last bid the evaluator applied and when it last ran.
 */
@Data
@Builder
public class AdPlacementLockTaskVo {

    private String id;
    private String strategyId;

    /** Target placement of the owning strategy, surfaced for display. */
    private String targetPlacement;

    /** Resolved campaign name of the owning strategy. */
    private String campaignName;

    private String keywordId;

    /** Resolved keyword text for display; {@code null} if unresolved. */
    private String keywordText;

    private Double lastBid;
    private String lastRunAt;
    private String createdAt;
}
