package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Ad Placement Lock strategy row for the 策略管理 (strategy management) tab of the
 * Ad Placement Lock page (Req 26.1, 26.2). Carries the strategy attributes plus
 * the resolved campaign name for display.
 */
@Data
@Builder
public class AdPlacementLockStrategyVo {

    private String id;
    private String storeId;
    private String campaignId;

    /** Resolved campaign name (广告活动) for display; {@code null} if unresolved. */
    private String campaignName;

    /** Target placement key, e.g. {@code top_of_search_1_1}. */
    private String targetPlacement;

    private Double bidMin;
    private Double bidMax;
    private String status;
    private String createdAt;
}
