package com.adpilot.modules.review.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.review.dto.CustomerReviewDto;
import com.adpilot.modules.review.dto.ReviewResponseDto;
import com.adpilot.modules.review.vo.CustomerReviewVo;
import com.adpilot.modules.review.vo.ListingQualityCheckVo;
import com.adpilot.modules.review.vo.ReviewAlertVo;

import java.util.List;
import java.util.Map;

public interface ReviewService {

    /**
     * List reviews with pagination, optionally filtered by storeId.
     */
    PageResponse<CustomerReviewVo> listReviews(String storeId, int page, int pageSize);

    /**
     * Get a single review by ID.
     */
    CustomerReviewVo getReviewById(String id);

    /**
     * Respond to a review.
     */
    CustomerReviewVo respondToReview(String id, String responseText, String userId);

    /**
     * List alerts with pagination, optionally filtered by storeId.
     */
    PageResponse<ReviewAlertVo> listAlerts(String storeId, int page, int pageSize);

    /**
     * Resolve an alert.
     */
    ReviewAlertVo resolveAlert(String id, String userId);

    /**
     * List quality checks with pagination, optionally filtered by storeId.
     */
    PageResponse<ListingQualityCheckVo> listQualityChecks(String storeId, int page, int pageSize);

    /**
     * Get a single quality check by ID.
     */
    ListingQualityCheckVo getQualityCheck(String id);

    /**
     * List response templates for an organization.
     */
    List<Map<String, Object>> listResponseTemplates(String orgId);

    /**
     * Create a new response template.
     */
    Map<String, Object> createResponseTemplate(Map<String, Object> dto, String userId);
}
