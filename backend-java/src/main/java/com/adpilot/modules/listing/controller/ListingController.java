package com.adpilot.modules.listing.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.listing.dto.ListingGenerateRequest;
import com.adpilot.modules.listing.service.ListingService;
import com.adpilot.modules.listing.vo.ComplianceCheckVo;
import com.adpilot.modules.listing.vo.ListingContentVo;
import com.adpilot.modules.listing.vo.ListingScoreVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/listing-ai")
@RequiredArgsConstructor
public class ListingController {
    private final ListingService listingService;

    @GetMapping("/products/{id}/listing-content")
    @RequirePermission("product:view")
    public ApiResponse<ListingContentVo> getListingContent(@PathVariable String id) {
        requireUuid(id);
        try {
            return ApiResponse.ok(listingService.getListingContent(id));
        } catch (BusinessException e) {
            if ("LISTING_NOT_FOUND".equals(e.getCode())) {
                return ApiResponse.ok(null);
            }
            throw e;
        }
    }

    @GetMapping("/products/{id}/keyword-mapping")
    @RequirePermission("product:view")
    public ApiResponse<Map<String, Object>> getKeywordMapping(@PathVariable String id) {
        requireUuid(id);
        return ApiResponse.ok(listingService.getKeywordMapping(id));
    }

    @GetMapping("/products/{id}/versions")
    @RequirePermission("product:view")
    public ApiResponse<List<ListingContentVo>> getVersions(@PathVariable String id) {
        requireUuid(id);
        return ApiResponse.ok(listingService.listVersions(id));
    }

    @PostMapping("/products/{id}/generate")
    @RequirePermission("product:update")
    public ApiResponse<ListingContentVo> generate(@PathVariable String id,
                                                   @RequestBody ListingGenerateRequest request) {
        requireUuid(id);
        return ApiResponse.ok(listingService.generateDraft(
                id, request, SecurityUtils.getCurrentUserIdOrNull()));
    }

    @PostMapping("/products/{id}/score")
    @RequirePermission("product:update")
    public ApiResponse<ListingScoreVo> score(@PathVariable String id) {
        requireUuid(id);
        return ApiResponse.ok(listingService.scoreListing(id));
    }

    @PostMapping("/products/{id}/compliance-check")
    @RequirePermission("product:view")
    public ApiResponse<ComplianceCheckVo> complianceCheck(@PathVariable String id) {
        requireUuid(id);
        return ApiResponse.ok(listingService.checkCompliance(id));
    }

    @PatchMapping("/drafts/{id}")
    @RequirePermission("product:update")
    public ApiResponse<ListingContentVo> updateDraft(@PathVariable String id,
                                                      @RequestBody ListingGenerateRequest request) {
        requireUuid(id);
        return ApiResponse.ok(listingService.updateDraft(id, request));
    }

    @PostMapping("/drafts/{id}/approve")
    @RequirePermission("product:update")
    public ApiResponse<ListingContentVo> approveDraft(@PathVariable String id) {
        requireUuid(id);
        return ApiResponse.ok(listingService.approveDraft(
                id, SecurityUtils.getCurrentUserIdOrNull()));
    }

    private void requireUuid(String value) {
        if (value == null) {
            throw new BusinessException("INVALID_ID", "ID is required");
        }
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_ID", "Invalid ID: " + value);
        }
    }
}