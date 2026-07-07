package com.adpilot.modules.keyword.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KeywordInsightVo {
    private String id;
    private String storeId;
    private String text;
    private String source;
    private String segment;
    private int healthScore;
    private int opportunityScore;
    private int wasteScore;
    private int confidenceScore;
    private String recommendedAction;
    private String reason;
    private Object currentData;
    private String expectedImpact;
    private String riskLevel;
    private String status;
    private String createdAt;
}
