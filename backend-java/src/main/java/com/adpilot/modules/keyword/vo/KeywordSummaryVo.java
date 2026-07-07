package com.adpilot.modules.keyword.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class KeywordSummaryVo {
    private String biggestProblem;
    private String bestKeywordToScale;
    private String bestKeywordToNegate;
    private List<String> listingMissingButConverting;
    private List<String> competitorOpportunities;
    private List<String> nextWeekPlan;
}
