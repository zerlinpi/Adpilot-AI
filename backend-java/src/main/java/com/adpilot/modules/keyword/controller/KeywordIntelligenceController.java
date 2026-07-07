package com.adpilot.modules.keyword.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.keyword.dto.KeywordAnalyzeRequest;
import com.adpilot.modules.keyword.dto.KeywordInsightQueryRequest;
import com.adpilot.modules.keyword.service.KeywordIntelligenceService;
import com.adpilot.modules.keyword.vo.KeywordInsightOverviewVo;
import com.adpilot.modules.keyword.vo.KeywordInsightVo;
import com.adpilot.modules.keyword.vo.KeywordSummaryVo;
import com.adpilot.modules.store.service.StoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/keyword-intelligence")
@RequiredArgsConstructor
public class KeywordIntelligenceController {

    private final KeywordIntelligenceService service;
    private final StoreService storeService;

    @GetMapping("/overview")
    @RequirePermission("keyword:view")
    public ApiResponse<KeywordInsightOverviewVo> getOverview(@RequestParam String storeId) {
        // Reject a storeId outside the caller's data scope before aggregating
        // (throws STORE_NOT_FOUND/STORE_FORBIDDEN), mirroring ProfitController.
        storeService.getStoreById(storeId);
        return ApiResponse.ok(service.getOverview(storeId));
    }

    @GetMapping("/insights")
    @RequirePermission("keyword:view")
    public ApiResponse<PageResponse<KeywordInsightVo>> getInsights(KeywordInsightQueryRequest request) {
        return ApiResponse.ok(service.getInsights(request));
    }

    @GetMapping("/summary")
    @RequirePermission("keyword:view")
    public ApiResponse<KeywordSummaryVo> getSummary(@RequestParam String storeId) {
        storeService.getStoreById(storeId);
        return ApiResponse.ok(service.getSummary(storeId));
    }

    @PostMapping("/analyze")
    @RequirePermission("keyword:manage")
    public ApiResponse<Object> analyze(@Valid @RequestBody KeywordAnalyzeRequest request) {
        storeService.getStoreById(request.getStoreId());
        int count = service.analyzeKeywordHealth(request.getStoreId());
        return ApiResponse.ok(Map.of("message", "Analysis completed", "count", count));
    }

    @PostMapping("/{id}/apply")
    @RequirePermission("keyword:apply")
    public ApiResponse<KeywordInsightVo> apply(@PathVariable String id) {
        return ApiResponse.ok(service.applyInsight(id, SecurityUtils.getCurrentUserIdOrNull()));
    }

    @PostMapping("/{id}/watch")
    @RequirePermission("keyword:manage")
    public ApiResponse<KeywordInsightVo> watch(@PathVariable String id) {
        return ApiResponse.ok(service.watchInsight(id));
    }

    @PostMapping("/{id}/dismiss")
    @RequirePermission("keyword:manage")
    public ApiResponse<KeywordInsightVo> dismiss(@PathVariable String id) {
        return ApiResponse.ok(service.dismissInsight(id));
    }
}
