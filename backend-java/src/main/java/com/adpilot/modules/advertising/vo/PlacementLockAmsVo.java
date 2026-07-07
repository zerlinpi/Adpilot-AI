package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

/**
 * AMS real-time data row for the AMS实时数据 (AMS real-time data) tab (Req 26.1).
 * AMS (Amazon Marketing Stream) real-time placement data is not available in this
 * project — this row is <em>stubbed from stored data</em>: it reflects the current
 * enforcement state of a placement-lock strategy (its target placement, the
 * latest bid applied to a linked keyword, and when it was observed).
 */
@Data
@Builder
public class PlacementLockAmsVo {

    private String strategyId;
    private String campaignName;

    /** Target placement key, e.g. {@code top_of_search_1_1}. */
    private String targetPlacement;

    private String keywordText;

    /** Latest bid applied within the configured range (Req 26.3). */
    private Double currentBid;

    private Double bidMin;
    private Double bidMax;

    /** Strategy status (active / paused). */
    private String status;

    /** Timestamp of the last enforcement run that produced this data point. */
    private String observedAt;
}
