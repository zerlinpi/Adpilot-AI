package com.adpilot.modules.keyword.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Store-level keyword / automation seed configuration (关键词与自动化配置).
 * Carries the four seed-term categories that feed keyword harvesting, negation
 * and automation:
 * <ul>
 *   <li>{@code brand} — 品牌关键词 (brand keywords)</li>
 *   <li>{@code category} — 品类关键词 (category keywords)</li>
 *   <li>{@code competitorBrand} — 竞品品牌 (competitor brands)</li>
 *   <li>{@code competitorAsin} — 竞品 ASIN (competitor ASINs)</li>
 * </ul>
 *
 * <p>Persisted by reusing {@code keyword_libraries} with a dedicated
 * {@code seed_*} library type per category, plus {@code keyword_library_items}
 * for the terms — no new tables required.
 */
@Data
@Builder
public class KeywordConfigVo {

    /** 品牌关键词 (brand keywords). */
    private List<String> brand;

    /** 品类关键词 (category keywords). */
    private List<String> category;

    /** 竞品品牌 (competitor brands). */
    private List<String> competitorBrand;

    /** 竞品 ASIN (competitor ASINs). */
    private List<String> competitorAsin;
}
