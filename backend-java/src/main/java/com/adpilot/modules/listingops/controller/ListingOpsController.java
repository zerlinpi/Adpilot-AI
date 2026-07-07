package com.adpilot.modules.listingops.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.listingops.dto.RepricingRuleDto;
import com.adpilot.modules.listingops.service.ListingOpsService;
import com.adpilot.modules.listingops.vo.BuyBoxAlertVo;
import com.adpilot.modules.listingops.vo.HijackerAlertVo;
import com.adpilot.modules.listingops.vo.ListingMonitorVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ListingOpsController {

    private final ListingOpsService listingOpsService;

    /**
     * GET /api/listing-monitor - List listing quality monitors with pagination.
     */
    @GetMapping("/listing-monitor")
    @RequirePermission("product:view")
    public ApiResponse<PageResponse<ListingMonitorVo>> listListingMonitors(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<ListingMonitorVo> result = listingOpsService.listListingMonitors(page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/buy-box - List buy box alerts with pagination.
     */
    @GetMapping("/buy-box")
    @RequirePermission("product:view")
    public ApiResponse<PageResponse<BuyBoxAlertVo>> listBuyBoxAlerts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<BuyBoxAlertVo> result = listingOpsService.listBuyBoxAlerts(page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/hijacker-alerts - List hijacker alerts with pagination.
     */
    @GetMapping("/hijacker-alerts")
    @RequirePermission("product:view")
    public ApiResponse<PageResponse<HijackerAlertVo>> listHijackerAlerts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<HijackerAlertVo> result = listingOpsService.listHijackerAlerts(page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/repricing/rules - List repricing rules with pagination.
     */
    @GetMapping("/repricing/rules")
    @RequirePermission("product:view")
    public ApiResponse<PageResponse<RepricingRuleDto>> listRepricingRules(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<RepricingRuleDto> result = listingOpsService.listRepricingRules(page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/repricing/rules - Create a new repricing rule.
     */
    @PostMapping("/repricing/rules")
    @RequirePermission("product:update")
    public ApiResponse<RepricingRuleDto> createRepricingRule(@Valid @RequestBody RepricingRuleDto dto) {
        RepricingRuleDto result = listingOpsService.createRepricingRule(dto);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/repricing/rules/{id}/apply - Apply a repricing rule.
     */
    @PostMapping("/repricing/rules/{id}/apply")
    @RequirePermission("product:update")
    public ApiResponse<Void> applyRepricingRule(@PathVariable String id) {
        listingOpsService.applyRepricingRule(id);
        return ApiResponse.ok(null);
    }
}
