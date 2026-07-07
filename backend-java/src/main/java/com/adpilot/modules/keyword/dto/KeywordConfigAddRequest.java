package com.adpilot.modules.keyword.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * Payload for {@code POST /api/keyword-libraries/config} — add one or more seed
 * terms to a store-level keyword-configuration category (关键词与自动化配置).
 *
 * <p>{@code category} is one of: {@code brand} (品牌关键词),
 * {@code category} (品类关键词), {@code competitorBrand} (竞品品牌),
 * {@code competitorAsin} (竞品 ASIN).
 */
@Data
public class KeywordConfigAddRequest {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    /** Seed category: brand | category | competitorBrand | competitorAsin. */
    @NotBlank(message = "Category is required")
    private String category;

    /** Terms to add to the category. Blank / duplicate terms are ignored. */
    private List<String> terms;
}
