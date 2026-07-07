package com.adpilot.modules.ai.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.modules.ai.dto.InsightQueryRequest;
import com.adpilot.modules.ai.service.InsightAgentService;
import com.adpilot.modules.ai.vo.InsightResultVo;
import com.adpilot.modules.ai.vo.SavedInsightVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Insight Agent conversational analysis endpoints (Req 24). Generated insights
 * are persisted and retained until manually deleted (item 16).
 */
@RestController
@RequestMapping("/api/insight-agent")
@RequiredArgsConstructor
public class InsightAgentController {

    private final InsightAgentService insightAgentService;

    /** POST /api/insight-agent/query — store-scoped query with source/premium flags (Req 24.1, 24.3). */
    @PostMapping("/query")
    public ApiResponse<InsightResultVo> query(@RequestBody InsightQueryRequest request) {
        return ApiResponse.ok(insightAgentService.query(request));
    }

    /** GET /api/insight-agent/suggestions — suggested prompts shown on page load (Req 24.2). */
    @GetMapping("/suggestions")
    public ApiResponse<List<String>> suggestions() {
        return ApiResponse.ok(insightAgentService.suggestions());
    }

    /** GET /api/insight-agent/insights — saved insights for a store, newest first (item 16). */
    @GetMapping("/insights")
    public ApiResponse<List<SavedInsightVo>> listSavedInsights(@RequestParam(required = false) String storeId) {
        return ApiResponse.ok(insightAgentService.listSavedInsights(storeId));
    }

    /** DELETE /api/insight-agent/insights/{id} — manually delete a saved insight (item 16). */
    @DeleteMapping("/insights/{id}")
    public ApiResponse<Void> deleteSavedInsight(@PathVariable String id) {
        insightAgentService.deleteSavedInsight(id);
        return ApiResponse.ok(null);
    }
}
