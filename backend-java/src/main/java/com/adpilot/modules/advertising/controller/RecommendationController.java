package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.service.RecommendationEngineService;
import com.adpilot.modules.advertising.service.RecommendationService;
import com.adpilot.modules.advertising.vo.RecommendationVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final RecommendationEngineService recommendationEngineService;

    /**
     * GET /api/recommendations - List recommendations with pagination.
     *
     * <p>Errors propagate to {@code GlobalExceptionHandler}, which serializes a JSON
     * error envelope with a 4xx/5xx status. Domain errors are raised as
     * {@code BusinessException} by the service layer.
     */
    @GetMapping
    @RequirePermission("advertising:view")
    public ApiResponse<PageResponse<RecommendationVo>> listRecommendations(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        PageResponse<RecommendationVo> result = recommendationService.listRecommendations(storeId, status, type, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/recommendations/generate - Generate recommendations for a store.
     */
    @PostMapping("/generate")
    @RequirePermission("advertising:manage")
    public ApiResponse<Integer> generateRecommendations(@RequestParam String storeId) {
        int count = recommendationEngineService.generateRecommendations(storeId);
        log.info("Generated {} recommendations for store {}", count, storeId);
        return ApiResponse.ok(count);
    }

    /**
     * POST /api/recommendations/{id}/apply - Apply a recommendation.
     */
    @PostMapping("/{id}/apply")
    @RequirePermission("advertising:execute")
    public ApiResponse<RecommendationVo> applyRecommendation(@PathVariable String id) {
        String userId = SecurityUtils.getCurrentUserId();
        RecommendationVo recommendation = recommendationService.applyRecommendation(id, userId);
        log.info("Recommendation applied: {}", id);
        return ApiResponse.ok(recommendation);
    }

    /**
     * POST /api/recommendations/{id}/dismiss - Dismiss a recommendation.
     */
    @PostMapping("/{id}/dismiss")
    @RequirePermission("advertising:manage")
    public ApiResponse<RecommendationVo> dismissRecommendation(@PathVariable String id) {
        String userId = SecurityUtils.getCurrentUserId();
        RecommendationVo recommendation = recommendationService.dismissRecommendation(id, userId);
        log.info("Recommendation dismissed: {}", id);
        return ApiResponse.ok(recommendation);
    }

    /**
     * POST /api/recommendations/{id}/watch - Watch a recommendation.
     */
    @PostMapping("/{id}/watch")
    @RequirePermission("advertising:manage")
    public ApiResponse<RecommendationVo> watchRecommendation(@PathVariable String id) {
        String userId = SecurityUtils.getCurrentUserId();
        RecommendationVo recommendation = recommendationService.watchRecommendation(id, userId);
        log.info("Recommendation set to watching: {}", id);
        return ApiResponse.ok(recommendation);
    }
}
