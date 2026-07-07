package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.KeywordUpdateRequest;
import com.adpilot.modules.advertising.service.KeywordService;
import com.adpilot.modules.advertising.vo.KeywordVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/keywords")
@RequiredArgsConstructor
public class KeywordController {

    private final KeywordService keywordService;

    /**
     * GET /api/keywords - List keywords with pagination.
     *
     * <p>Errors propagate to {@code GlobalExceptionHandler}, which serializes a JSON
     * error envelope with a 4xx/5xx status. Domain errors are raised as
     * {@code BusinessException} by the service layer.
     */
    @GetMapping
    @RequirePermission("keyword:view")
    public ApiResponse<PageResponse<KeywordVo>> listKeywords(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String campaignId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        PageResponse<KeywordVo> result = keywordService.listKeywords(storeId, campaignId, status, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * PUT|PATCH /api/keywords/{id} - Update keyword. The frontend sends PATCH with
     * a JSON body ({@code {"bid":..,"status":..}}); PUT is also accepted.
     */
    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    @RequirePermission("keyword:manage")
    public ApiResponse<KeywordVo> updateKeyword(@PathVariable String id, @Valid @RequestBody KeywordUpdateRequest request) {
        String userId = SecurityUtils.getCurrentUserId();
        KeywordVo keyword = keywordService.updateKeyword(id, request, userId);
        log.info("Keyword updated: {}", id);
        return ApiResponse.ok(keyword);
    }
}
