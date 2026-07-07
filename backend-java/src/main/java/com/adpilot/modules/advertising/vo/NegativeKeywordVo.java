package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NegativeKeywordVo {

    private String id;
    private String campaignId;
    private String campaignName;
    private String adGroupId;
    private String storeId;
    private String keywordText;
    private String matchType;
    /** Scope at which the negative applies: campaign / ad group. */
    private String level;
    private String source;
    private String status;
    private String externalId;
    private String createdAt;
    private String updatedAt;
}
