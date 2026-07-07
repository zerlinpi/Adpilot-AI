package com.adpilot.modules.keyword.service;

import com.adpilot.modules.keyword.dto.KeywordLibraryCreateRequest;
import com.adpilot.modules.keyword.vo.KeywordConfigVo;
import com.adpilot.modules.keyword.vo.KeywordLibraryVo;

import java.util.List;
import java.util.Map;

/**
 * Keyword Library management and keyword-recommendation actions (Req 27).
 * Libraries are store-scoped collections of keywords (词库); the recommendation
 * actions reuse the existing harvest / negative-keyword flows.
 */
public interface KeywordLibraryService {

    /** List keyword libraries for a store, with derived keyword / product counts (Req 27.2). */
    List<KeywordLibraryVo> listLibraries(String storeId);

    /** Create a keyword library, optionally seeded with products and keywords (Req 27.3). */
    KeywordLibraryVo createLibrary(KeywordLibraryCreateRequest request);

    /**
     * Act on a keyword recommendation by harvesting it as a positive keyword
     * scoped to the active store (Req 27.4). The {@code recommendationId} is a
     * keyword-insight id.
     */
    Map<String, Object> harvestRecommendation(String recommendationId, String userId);

    /**
     * Act on a keyword recommendation by adding it as a negative keyword scoped
     * to the active store (Req 27.4). The {@code recommendationId} is a
     * keyword-insight id.
     */
    Map<String, Object> negateRecommendation(String recommendationId, String userId);

    /**
     * Get the store-level keyword / automation seed configuration
     * (关键词与自动化配置): brand keywords, category keywords, competitor brands
     * and competitor ASINs. Returns empty lists when nothing is configured.
     */
    KeywordConfigVo getKeywordConfig(String storeId);

    /**
     * Add seed terms to a keyword-configuration category and return the updated
     * configuration (关键词与自动化配置). {@code category} is one of
     * {@code brand | category | competitorBrand | competitorAsin}.
     */
    KeywordConfigVo addKeywordConfig(String storeId, String category, List<String> terms);
}
