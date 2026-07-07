package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.advertising.service.BidChangeService;
import com.adpilot.modules.advertising.vo.BidChangeVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/bid-changes")
@RequiredArgsConstructor
public class BidChangeController {

    private final BidChangeService bidChangeService;

    /**
     * GET /api/bid-changes - List recent bid adjustments (manual + automated)
     * for the active store, optionally narrowed to a single campaign and/or
     * entity type, ordered newest-first with pagination.
     *
     * <p>Errors propagate to {@code GlobalExceptionHandler}, which serializes a JSON
     * error envelope with a 4xx/5xx status. Domain errors are raised as
     * {@code BusinessException} by the service layer.
     */
    @GetMapping
    @RequirePermission("advertising:view")
    public ApiResponse<PageResponse<BidChangeVo>> listBidChanges(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String campaignId,
            @RequestParam(required = false) String entityType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        PageResponse<BidChangeVo> result =
                bidChangeService.listBidChanges(storeId, campaignId, entityType, page, pageSize);
        return ApiResponse.ok(result);
    }
}
