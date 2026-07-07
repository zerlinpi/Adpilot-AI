package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.advertising.service.AdGroupService;
import com.adpilot.modules.advertising.vo.AdGroupVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/ad-groups")
@RequiredArgsConstructor
public class AdGroupController {

    private final AdGroupService adGroupService;

    /**
     * GET /api/ad-groups - List ad groups for the active store, optionally
     * narrowed to a single campaign and/or status, with pagination.
     *
     * <p>Errors propagate to {@code GlobalExceptionHandler}, which serializes a JSON
     * error envelope with a 4xx/5xx status. Domain errors are raised as
     * {@code BusinessException} by the service layer.
     */
    @GetMapping
    @RequirePermission("advertising:view")
    public ApiResponse<PageResponse<AdGroupVo>> listAdGroups(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String campaignId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        PageResponse<AdGroupVo> result = adGroupService.listAdGroups(storeId, campaignId, status, page, pageSize);
        return ApiResponse.ok(result);
    }
}
