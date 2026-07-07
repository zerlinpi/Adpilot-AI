package com.adpilot.modules.insights.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.modules.insights.service.InsightsService;
import com.adpilot.modules.insights.vo.AmcTemplatesVo;
import com.adpilot.modules.insights.vo.BrandMetricsVo;
import com.adpilot.modules.insights.vo.DataSourceActivationVo;
import com.adpilot.modules.insights.vo.MarketInsightsVo;
import com.adpilot.modules.insights.vo.ProductInsightsVo;
import com.adpilot.modules.insights.vo.SqpVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Data Insights / SQP / AMC surfaces (Req 30). All endpoints are store-scoped
 * and return the {@link ApiResponse} envelope. Surfaces backed by Amazon-side
 * data the project does not ingest report an explicit "requires activation" /
 * empty payload rather than fabricating data.
 */
@Slf4j
@RestController
@RequestMapping("/api/insights")
@RequiredArgsConstructor
public class InsightsController {

    private final InsightsService insightsService;

    /** GET /api/insights/product-list — per-product metrics + custom-report quota (Req 30.1, 30.2). */
    @GetMapping("/product-list")
    public ApiResponse<ProductInsightsVo> getProductList(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(insightsService.getProductList(storeId, startDate, endDate));
    }

    /** GET /api/insights/brand-metrics — category-brand metrics (Req 30.3). */
    @GetMapping("/brand-metrics")
    public ApiResponse<BrandMetricsVo> getBrandMetrics(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(insightsService.getBrandMetrics(storeId, startDate, endDate));
    }

    /** GET /api/insights/market-insights — market-monitoring reports (Req 30.4). */
    @GetMapping("/market-insights")
    public ApiResponse<MarketInsightsVo> getMarketInsights(
            @RequestParam(required = false) String storeId) {
        return ApiResponse.ok(insightsService.getMarketInsights(storeId));
    }

    /** GET /api/insights/sqp — brand-versus-market search-query funnel (Req 30.5). */
    @GetMapping("/sqp")
    public ApiResponse<SqpVo> getSqp(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(insightsService.getSqp(storeId, startDate, endDate));
    }

    /** GET /api/insights/amc/models — AMC analytical model templates (Req 30.6, 30.7). */
    @GetMapping("/amc/models")
    public ApiResponse<AmcTemplatesVo> getAmcModels(
            @RequestParam(required = false) String storeId) {
        return ApiResponse.ok(insightsService.getAmcModels(storeId));
    }

    /** GET /api/insights/amc/audiences — AMC audience-creation templates (Req 30.6, 30.7). */
    @GetMapping("/amc/audiences")
    public ApiResponse<AmcTemplatesVo> getAmcAudiences(
            @RequestParam(required = false) String storeId) {
        return ApiResponse.ok(insightsService.getAmcAudiences(storeId));
    }

    /**
     * GET /api/insights/brand-source/activation — current activation status for a
     * per-store data source (item 8). {@code source} defaults to brand_analytics.
     */
    @GetMapping("/brand-source/activation")
    public ApiResponse<DataSourceActivationVo> getActivation(
            @RequestParam String storeId,
            @RequestParam(required = false, defaultValue = "brand_analytics") String source) {
        return ApiResponse.ok(insightsService.getActivation(storeId, source));
    }

    /**
     * POST /api/insights/brand-source/activate — activate a per-store data source,
     * unlocking the brand / SQP / AMC surfaces to use stored data (item 8).
     */
    @PostMapping("/brand-source/activate")
    public ApiResponse<DataSourceActivationVo> activate(
            @RequestParam String storeId,
            @RequestParam(required = false, defaultValue = "brand_analytics") String source) {
        return ApiResponse.ok(insightsService.activate(storeId, source));
    }
}
