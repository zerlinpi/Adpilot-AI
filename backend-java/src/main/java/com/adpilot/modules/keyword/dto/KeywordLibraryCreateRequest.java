package com.adpilot.modules.keyword.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * Payload for {@code POST /api/keyword-libraries} — create a Keyword Library
 * (创建词库, Req 27.3). {@code storeId}, {@code name}, and {@code libraryType}
 * are required; the keyword list and schedule are optional.
 */
@Data
public class KeywordLibraryCreateRequest {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    @NotBlank(message = "Library name is required")
    private String name;

    /** Library type (词库类型): harvest | negative | brand | competitor. */
    @NotBlank(message = "Library type is required")
    private String libraryType;

    /** Optional execution schedule (cron expression). */
    private String scheduleCron;

    /** Optional product ids associated with the library (关联商品). */
    private List<String> productIds;

    /** Optional initial keywords to seed the library with. */
    private List<String> keywords;
}
