package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.AdPortfolioCreateRequest;
import com.adpilot.modules.advertising.dto.AdPortfolioUpdateRequest;
import com.adpilot.modules.advertising.service.AdPortfolioService;
import com.adpilot.modules.advertising.vo.AdPortfolioVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Ad Portfolio management endpoints (Req 20). Portfolios group campaigns
 * (campaigns reference their portfolio via {@code portfolio_id}); the list
 * response carries rollup metrics aggregated across member campaigns.
 *
 * <p>Errors propagate to {@code GlobalExceptionHandler}, which serializes a JSON
 * error envelope with a 4xx/5xx status. Domain errors are raised as
 * {@code BusinessException} by the service layer.
 */
@Slf4j
@RestController
@RequestMapping("/api/ad-portfolios")
@RequiredArgsConstructor
public class AdPortfolioController {

    private final AdPortfolioService adPortfolioService;

    /**
     * GET /api/ad-portfolios - List ad portfolios for the active store with
     * member-campaign rollup metrics (Req 20.1).
     */
    @GetMapping
    @RequirePermission("advertising:view")
    public ApiResponse<List<AdPortfolioVo>> listPortfolios(@RequestParam(required = false) String storeId) {
        List<AdPortfolioVo> result = adPortfolioService.listPortfolios(storeId);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/ad-portfolios/{id} - Get a single portfolio with rollup metrics.
     */
    @GetMapping("/{id}")
    @RequirePermission("advertising:view")
    public ApiResponse<AdPortfolioVo> getPortfolio(@PathVariable String id) {
        AdPortfolioVo portfolio = adPortfolioService.getPortfolioById(id);
        return ApiResponse.ok(portfolio);
    }

    /**
     * POST /api/ad-portfolios - Create an ad portfolio (Req 20.2).
     */
    @PostMapping
    @RequirePermission("advertising:manage")
    public ApiResponse<AdPortfolioVo> createPortfolio(@Valid @RequestBody AdPortfolioCreateRequest request) {
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        AdPortfolioVo portfolio = adPortfolioService.createPortfolio(request, userId);
        log.info("Ad portfolio created: {}", portfolio.getId());
        return ApiResponse.ok(portfolio);
    }

    /**
     * PUT /api/ad-portfolios/{id} - Update an ad portfolio (Req 20).
     */
    @PutMapping("/{id}")
    @RequirePermission("advertising:manage")
    public ApiResponse<AdPortfolioVo> updatePortfolio(
            @PathVariable String id,
            @Valid @RequestBody AdPortfolioUpdateRequest request) {
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        AdPortfolioVo portfolio = adPortfolioService.updatePortfolio(id, request, userId);
        log.info("Ad portfolio updated: {}", id);
        return ApiResponse.ok(portfolio);
    }
}
