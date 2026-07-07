package com.adpilot.modules.keyword.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.keyword.dto.KeywordInsightQueryRequest;
import com.adpilot.modules.keyword.vo.KeywordInsightOverviewVo;
import com.adpilot.modules.keyword.vo.KeywordInsightVo;
import com.adpilot.modules.keyword.vo.KeywordSummaryVo;

public interface KeywordIntelligenceService {

    /**
     * Get keyword intelligence overview for a store.
     */
    KeywordInsightOverviewVo getOverview(String storeId);

    /**
     * Get paginated keyword insights with filters.
     */
    PageResponse<KeywordInsightVo> getInsights(KeywordInsightQueryRequest request);

    /**
     * Get natural language summary of keyword insights.
     */
    KeywordSummaryVo getSummary(String storeId);

    /**
     * Run keyword health analysis for a store. Returns count of insights generated.
     */
    int analyzeKeywordHealth(String storeId);

    /**
     * Apply a keyword insight recommendation.
     */
    KeywordInsightVo applyInsight(String id, String userId);

    /**
     * Mark an insight as watched.
     */
    KeywordInsightVo watchInsight(String id);

    /**
     * Dismiss a keyword insight.
     */
    KeywordInsightVo dismissInsight(String id);
}
