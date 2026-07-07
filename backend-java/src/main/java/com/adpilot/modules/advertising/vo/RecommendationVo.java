package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class RecommendationVo {

    private String id;
    private String storeId;
    private String campaignId;
    private String keywordId;
    private String targetId;
    private String type;
    private String priority;
    private String title;
    private String description;
    private String reason;
    private String expectedImpact;
    private String riskLevel;
    private String targetEntityType;
    private String targetEntityName;
    private Object currentData;
    private String currentValue;
    private String recommendedValue;
    private double estimatedImpact;
    private double confidence;
    private String status;
    private String appliedAt;
    private String dismissedAt;
    private String createdAt;
    private String updatedAt;
}
