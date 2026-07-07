package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.SearchTermHarvestRequest;
import com.adpilot.modules.advertising.service.SearchTermService;
import com.adpilot.modules.advertising.vo.SearchTermVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/search-terms")
@RequiredArgsConstructor
public class SearchTermController {

    private final SearchTermService searchTermService;

    /**
     * GET /api/search-terms - List search terms with pagination.
     *
     * <p>Errors propagate to {@code GlobalExceptionHandler}, which serializes a JSON
     * error envelope with a 4xx/5xx status. Domain errors are raised as
     * {@code BusinessException} by the service layer.
     */
    @GetMapping
    @RequirePermission("advertising:view")
    public ApiResponse<PageResponse<SearchTermVo>> listSearchTerms(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String campaignId,
            @RequestParam(required = false) String harvested,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        PageResponse<SearchTermVo> result = searchTermService.listSearchTerms(storeId, campaignId, harvested, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/search-terms/{id}/harvest - Harvest a search term as a keyword.
     */
    @PostMapping("/{id}/harvest")
    @RequirePermission("keyword:apply")
    public ApiResponse<SearchTermVo> harvestSearchTerm(@PathVariable String id, @Valid @RequestBody SearchTermHarvestRequest request) {
        String userId = SecurityUtils.getCurrentUserId();
        SearchTermVo searchTerm = searchTermService.harvestSearchTerm(id, request, userId);
        log.info("Search term harvested: {}", id);
        return ApiResponse.ok(searchTerm);
    }
}
