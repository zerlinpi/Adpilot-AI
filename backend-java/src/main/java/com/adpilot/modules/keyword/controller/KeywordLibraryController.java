package com.adpilot.modules.keyword.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.keyword.dto.KeywordConfigAddRequest;
import com.adpilot.modules.keyword.dto.KeywordLibraryCreateRequest;
import com.adpilot.modules.keyword.service.KeywordLibraryService;
import com.adpilot.modules.keyword.vo.KeywordConfigVo;
import com.adpilot.modules.keyword.vo.KeywordLibraryVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Keyword Library + keyword-recommendation endpoints (Req 27). Libraries (词库)
 * are store-scoped collections of keywords; the recommendation actions reuse the
 * existing harvest / negative-keyword flows.
 *
 * <p>Errors propagate to {@code GlobalExceptionHandler}, which serializes a JSON
 * error envelope with a 4xx/5xx status.
 */
@Slf4j
@RestController
@RequestMapping("/api/keyword-libraries")
@RequiredArgsConstructor
public class KeywordLibraryController {

    private final KeywordLibraryService keywordLibraryService;

    /**
     * GET /api/keyword-libraries - List keyword libraries for the active store
     * with derived keyword / associated-product counts (Req 27.1, 27.2).
     */
    @GetMapping
    public ApiResponse<List<KeywordLibraryVo>> listLibraries(@RequestParam(required = false) String storeId) {
        return ApiResponse.ok(keywordLibraryService.listLibraries(storeId));
    }

    /**
     * POST /api/keyword-libraries - Create a keyword library (创建词库, Req 27.3).
     */
    @PostMapping
    @RequirePermission("keyword:manage")
    public ApiResponse<KeywordLibraryVo> createLibrary(@Valid @RequestBody KeywordLibraryCreateRequest request) {
        KeywordLibraryVo library = keywordLibraryService.createLibrary(request);
        log.info("Keyword library created: {}", library.getId());
        return ApiResponse.ok(library);
    }

    /**
     * POST /api/keyword-libraries/recommendations/{id}/harvest - Harvest a keyword
     * recommendation as a positive keyword scoped to the active store (Req 27.4).
     */
    @PostMapping("/recommendations/{id}/harvest")
    @RequirePermission("keyword:apply")
    public ApiResponse<Map<String, Object>> harvest(@PathVariable String id) {
        Map<String, Object> result = keywordLibraryService.harvestRecommendation(id, SecurityUtils.getCurrentUserIdOrNull());
        log.info("Keyword recommendation harvested: {}", id);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/keyword-libraries/recommendations/{id}/negate - Add a keyword
     * recommendation as a negative keyword scoped to the active store (Req 27.4).
     */
    @PostMapping("/recommendations/{id}/negate")
    @RequirePermission("keyword:apply")
    public ApiResponse<Map<String, Object>> negate(@PathVariable String id) {
        Map<String, Object> result = keywordLibraryService.negateRecommendation(id, SecurityUtils.getCurrentUserIdOrNull());
        log.info("Keyword recommendation negated: {}", id);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/keyword-libraries/config - Get the store-level keyword / automation
     * seed configuration (关键词与自动化配置): brand keywords, category keywords,
     * competitor brands and competitor ASINs. Returns empty lists when nothing is
     * configured.
     */
    @GetMapping("/config")
    public ApiResponse<KeywordConfigVo> getConfig(@RequestParam String storeId) {
        return ApiResponse.ok(keywordLibraryService.getKeywordConfig(storeId));
    }

    /**
     * POST /api/keyword-libraries/config - Add seed terms to a keyword-config
     * category (品牌关键词 / 品类关键词 / 竞品品牌 / 竞品 ASIN) and return the
     * updated configuration.
     */
    @PostMapping("/config")
    @RequirePermission("keyword:manage")
    public ApiResponse<KeywordConfigVo> addConfig(@Valid @RequestBody KeywordConfigAddRequest request) {
        KeywordConfigVo config = keywordLibraryService.addKeywordConfig(
                request.getStoreId(), request.getCategory(), request.getTerms());
        log.info("Keyword config terms added: store={}, category={}", request.getStoreId(), request.getCategory());
        return ApiResponse.ok(config);
    }
}
