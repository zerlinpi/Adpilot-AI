package com.adpilot.modules.keyword.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KeywordInsightOverviewVo {
    private int totalKeywords;
    private int activeKeywords;
    private int wasteKeywords;
    private int winnerKeywords;
    private int lowImpressionHighCvrKeywords;
    private int negativeCandidates;
    private int harvestCandidates;
    private int keywordHealthScore;
    private double searchTermCoverageRate;
}
