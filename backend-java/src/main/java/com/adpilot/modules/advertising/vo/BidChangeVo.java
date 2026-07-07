package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BidChangeVo {

    private String id;
    private String storeId;
    private String campaignId;
    private String campaignName;
    private String keywordId;
    private String targetId;
    /** keyword / target / placement / campaign. */
    private String entityType;
    private Double oldBid;
    private Double newBid;
    private String changeReason;
    private boolean automated;
    private String createdAt;
}
