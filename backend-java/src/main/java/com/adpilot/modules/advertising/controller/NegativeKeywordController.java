package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.advertising.service.NegativeKeywordService;
import com.adpilot.modules.advertising.vo.NegativeKeywordVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/negative-keywords")
@RequiredArgsConstructor
public class NegativeKeywordController {

    private final NegativeKeywordService negativeKeywordService;

    /**
     * GET /api/negative-keywords - List negative keywords/targets for the active
     * store, optionally narrowed to a single campaign and/or scope level, with
     * pagination.
     *
     * <p>Errors propagate to {@code GlobalExceptionHandler}, which serializes a JSON
     * error envelope with a 4xx/5xx status. Domain errors are raised as
     * {@code BusinessException} by the service layer.
     */
    @GetMapping
    @RequirePermission("keyword:view")
    public ApiResponse<PageResponse<NegativeKeywordVo>> listNegativeKeywords(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String campaignId,
            @RequestParam(required = false) String level,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        PageResponse<NegativeKeywordVo> result =
                negativeKeywordService.listNegativeKeywords(storeId, campaignId, level, page, pageSize);
        return ApiResponse.ok(result);
    }
}
