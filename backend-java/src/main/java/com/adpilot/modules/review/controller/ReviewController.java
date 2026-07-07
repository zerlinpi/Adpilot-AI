package com.adpilot.modules.review.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.review.dto.ReviewResponseDto;
import com.adpilot.modules.review.service.ReviewService;
import com.adpilot.modules.review.vo.CustomerReviewVo;
import com.adpilot.modules.review.vo.ListingQualityCheckVo;
import com.adpilot.modules.review.vo.ReviewAlertVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    /**
     * GET /api/reviews - List reviews with pagination.
     */
    @GetMapping
    @RequirePermission("review:view")
    public ApiResponse<PageResponse<CustomerReviewVo>> listReviews(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<CustomerReviewVo> result = reviewService.listReviews(storeId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/reviews/{id} - Get review by ID.
     */
    @GetMapping("/{id}")
    @RequirePermission("review:view")
    public ApiResponse<CustomerReviewVo> getReview(@PathVariable String id) {
        CustomerReviewVo review = reviewService.getReviewById(id);
        return ApiResponse.ok(review);
    }

    /**
     * POST /api/reviews/{id}/respond - Respond to a review.
     */
    @PostMapping("/{id}/respond")
    @RequirePermission("review:manage")
    public ApiResponse<CustomerReviewVo> respondToReview(
            @PathVariable String id,
            @Valid @RequestBody ReviewResponseDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        CustomerReviewVo review = reviewService.respondToReview(id, dto.getResponseText(), userId);
        return ApiResponse.ok(review);
    }

    /**
     * GET /api/reviews/alerts - List alerts with pagination.
     */
    @GetMapping("/alerts")
    @RequirePermission("review:view")
    public ApiResponse<PageResponse<ReviewAlertVo>> listAlerts(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<ReviewAlertVo> result = reviewService.listAlerts(storeId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/reviews/alerts/{id}/resolve - Resolve an alert.
     */
    @PostMapping("/alerts/{id}/resolve")
    @RequirePermission("review:manage")
    public ApiResponse<ReviewAlertVo> resolveAlert(@PathVariable String id) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        ReviewAlertVo alert = reviewService.resolveAlert(id, userId);
        return ApiResponse.ok(alert);
    }

    /**
     * GET /api/reviews/quality-checks - List quality checks with pagination.
     */
    @GetMapping("/quality-checks")
    @RequirePermission("review:view")
    public ApiResponse<PageResponse<ListingQualityCheckVo>> listQualityChecks(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<ListingQualityCheckVo> result = reviewService.listQualityChecks(storeId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/reviews/quality-checks/{id} - Get quality check by ID.
     */
    @GetMapping("/quality-checks/{id}")
    @RequirePermission("review:view")
    public ApiResponse<ListingQualityCheckVo> getQualityCheck(@PathVariable String id) {
        ListingQualityCheckVo check = reviewService.getQualityCheck(id);
        return ApiResponse.ok(check);
    }

    /**
     * GET /api/reviews/response-templates - List response templates.
     */
    @GetMapping("/response-templates")
    @RequirePermission("review:view")
    public ApiResponse<List<Map<String, Object>>> listResponseTemplates(
            @RequestParam(required = false) String orgId) {
        List<Map<String, Object>> result = reviewService.listResponseTemplates(orgId);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/reviews/response-templates - Create a response template.
     */
    @PostMapping("/response-templates")
    @RequirePermission("review:manage")
    public ApiResponse<Map<String, Object>> createResponseTemplate(
            @RequestBody Map<String, Object> dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        Map<String, Object> result = reviewService.createResponseTemplate(dto, userId);
        return ApiResponse.ok(result);
    }
}
