package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.CampaignBulkRequest;
import com.adpilot.modules.advertising.dto.CampaignCreateRequest;
import com.adpilot.modules.advertising.dto.CampaignHostingRequest;
import com.adpilot.modules.advertising.dto.CampaignStateRequest;
import com.adpilot.modules.advertising.dto.CampaignUpdateRequest;
import com.adpilot.modules.advertising.service.CampaignService;
import com.adpilot.modules.advertising.support.CampaignFilter;
import com.adpilot.modules.advertising.vo.CampaignBulkResultVo;
import com.adpilot.modules.advertising.vo.CampaignTrendPointVo;
import com.adpilot.modules.advertising.vo.CampaignVo;
import com.adpilot.modules.tableview.dto.ExportRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignService campaignService;

    /**
     * GET /api/campaigns - List campaigns with pagination and the All Search Ads
     * workspace filters (Req 19.4): smart filter, ad type (SP/SB/SD), parent
     * ASIN, ad portfolio, targeting goal, campaign status, and an inclusive
     * Target ACoS range. All filters are optional and combined with AND; results
     * are scoped to the active store.
     *
     * <p>Errors propagate to {@code GlobalExceptionHandler}, which serializes a JSON
     * error envelope with a 4xx/5xx status. Domain errors are raised as
     * {@code BusinessException} by the service layer.
     */
    @GetMapping
    @RequirePermission("advertising:view")
    public ApiResponse<PageResponse<CampaignVo>> listCampaigns(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String goalId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String adType,
            @RequestParam(required = false) String portfolioId,
            @RequestParam(required = false) String parentAsin,
            @RequestParam(required = false) String targetingGoal,
            @RequestParam(required = false) BigDecimal targetAcosMin,
            @RequestParam(required = false) BigDecimal targetAcosMax,
            @RequestParam(required = false) String smartFilter,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        CampaignFilter filter = CampaignFilter.builder()
                .storeId(storeId)
                .status(status)
                .adType(adType)
                .portfolioId(portfolioId)
                .parentAsin(parentAsin)
                .targetingGoal(targetingGoal)
                .targetAcosMin(targetAcosMin)
                .targetAcosMax(targetAcosMax)
                .smartFilter(smartFilter)
                .build();
        PageResponse<CampaignVo> result = campaignService.listCampaigns(filter, goalId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/campaigns/export - Server-side CSV export over the full filtered,
     * sorted result set (Req 2.8).
     *
     * <p>The request body carries the active advanced filters, sort order, and the
     * visible columns (in order). The backend streams a {@code text/csv} body
     * containing a header row plus every matching campaign across all pages,
     * limited to the visible columns and honoring the filters/sort — not just the
     * rows on the current page. Results are scoped to the requester's data scope.
     *
     * <p>The method writes directly to the {@link HttpServletResponse} output
     * stream and returns {@code void}, so the standard JSON envelope wrapper does
     * not apply and the client receives a raw CSV download. A leading UTF-8 BOM is
     * emitted so spreadsheet tools render non-ASCII (e.g. campaign names) correctly.
     */
    @PostMapping("/export")
    @RequirePermission("advertising:view")
    public void exportCampaigns(@RequestBody ExportRequest request, HttpServletResponse response) {
        String filename = "campaigns-export-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv";
        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        try {
            Writer writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
            // UTF-8 BOM for spreadsheet compatibility with non-ASCII content.
            writer.write('\uFEFF');
            campaignService.exportCsv(request, writer);
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write campaign CSV export", e);
        }
        log.info("Campaign CSV export streamed: {}", filename);
    }

    /**
     * GET /api/campaigns/{id} - Get campaign by ID.
     */
    @GetMapping("/{id}")
    @RequirePermission("advertising:view")
    public ApiResponse<CampaignVo> getCampaign(@PathVariable String id) {
        CampaignVo campaign = campaignService.getCampaignById(id);
        return ApiResponse.ok(campaign);
    }

    /**
     * PUT|PATCH /api/campaigns/{id} - Update a campaign from a JSON body.
     *
     * <p>The All Search Ads workspace sends a partial JSON body (e.g. the budget
     * editor posts {@code {"dailyBudget": 60}}) via PATCH; PUT is also accepted
     * for backward compatibility. Only non-null fields are applied.
     */
    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    @RequirePermission("advertising:manage")
    public ApiResponse<CampaignVo> updateCampaign(
            @PathVariable String id,
            @RequestBody CampaignUpdateRequest request) {
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        CampaignVo campaign = campaignService.updateCampaign(
                id, request.getName(), request.getStatus(), request.resolveBudget(), userId);
        log.info("Campaign updated: {}", id);
        return ApiResponse.ok(campaign);
    }

    /**
     * POST /api/campaigns - Create a campaign (Req 19.6).
     *
     * <p>Guarded by {@code advertising:manage}, matching the update endpoint.
     * Domain errors (invalid store id, etc.) propagate as {@code BusinessException}
     * and are serialized to a JSON error envelope by {@code GlobalExceptionHandler}.
     */
    @PostMapping
    @RequirePermission("advertising:manage")
    public ApiResponse<CampaignVo> createCampaign(@Valid @RequestBody CampaignCreateRequest request) {
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        CampaignVo campaign = campaignService.createCampaign(request, userId);
        log.info("Campaign created: {}", campaign.getId());
        return ApiResponse.ok(campaign);
    }

    /**
     * PATCH /api/campaigns/{id}/state - Toggle enable/pause via a JSON body
     * (Req 19.5), e.g. {@code {"state":"pause"}}. Accepts enable/enabled/active
     * and pause/paused; other tokens yield a JSON domain error.
     */
    @PatchMapping("/{id}/state")
    @RequirePermission("advertising:manage")
    public ApiResponse<CampaignVo> updateCampaignState(
            @PathVariable String id,
            @Valid @RequestBody CampaignStateRequest request) {
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        CampaignVo campaign = campaignService.updateState(id, request.getState(), userId);
        log.info("Campaign state updated: id={}, state={}", id, request.getState());
        return ApiResponse.ok(campaign);
    }

    /**
     * POST /api/campaigns/bulk - Apply one operation (enable / pause / delete)
     * to many campaigns, returning a per-item result so partial failures are
     * visible (Req 19.7).
     */
    @PostMapping("/bulk")
    @RequirePermission("advertising:manage")
    public ApiResponse<List<CampaignBulkResultVo>> bulkOperation(@Valid @RequestBody CampaignBulkRequest request) {
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        List<CampaignBulkResultVo> results = campaignService.bulkOperation(request, userId);
        log.info("Campaign bulk op '{}' applied to {} ids", request.getOperation(), request.getIds().size());
        return ApiResponse.ok(results);
    }

    /**
     * GET /api/campaigns/trend - Campaign data-trend panel (Req 19.2),
     * aggregated from {@code performance_daily} and scoped to the active store.
     * Optional date range and granularity (day / week / month).
     */
    @GetMapping("/trend")
    @RequirePermission("advertising:view")
    public ApiResponse<List<CampaignTrendPointVo>> getCampaignTrend(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "day") String granularity) {
        List<CampaignTrendPointVo> trend = campaignService.getTrend(storeId, startDate, endDate, granularity);
        return ApiResponse.ok(trend);
    }

    /**
     * PUT /api/campaigns/{id}/hosting - Place a campaign under AI_Hosting by
     * assigning a Hosting_Goal and a Target_ACoS (Req 21.1). The body's
     * {@code targetAcos} is required and validated (Req 21.6); an invalid or
     * missing value yields a JSON validation error rather than persisting.
     */
    @PutMapping("/{id}/hosting")
    @RequirePermission("hosting:manage")
    public ApiResponse<CampaignVo> assignHosting(
            @PathVariable String id,
            @Valid @RequestBody CampaignHostingRequest request) {
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        CampaignVo campaign = campaignService.assignHosting(id, request, userId);
        log.info("Campaign hosting assigned: id={}, targetAcos={}", id, request.getTargetAcos());
        return ApiResponse.ok(campaign);
    }

    /**
     * DELETE /api/campaigns/{id}/hosting - Remove a campaign from AI_Hosting
     * (Req 21.5). Persists the un-hosted state so the {@code AiHostingOptimizer}
     * stops applying automatic optimizations to the campaign.
     */
    @DeleteMapping("/{id}/hosting")
    @RequirePermission("hosting:manage")
    public ApiResponse<CampaignVo> removeHosting(@PathVariable String id) {
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        CampaignVo campaign = campaignService.removeHosting(id, userId);
        log.info("Campaign hosting removed: id={}", id);
        return ApiResponse.ok(campaign);
    }
}
