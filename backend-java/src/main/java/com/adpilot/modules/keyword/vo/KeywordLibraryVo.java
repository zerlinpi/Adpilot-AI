package com.adpilot.modules.keyword.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Keyword Library row for the 词库 tab (Req 27.2). Carries the library's own
 * attributes plus derived counts: associated-product count (关联商品),
 * keyword count (关键词数量), library type (词库类型), and the last and next
 * execution times.
 */
@Data
@Builder
public class KeywordLibraryVo {

    private String id;
    private String storeId;
    private String name;

    /** Library type (词库类型): harvest | negative | brand | competitor. */
    private String libraryType;

    private String scheduleCron;

    /** Number of distinct associated products (关联商品). */
    private int associatedProductCount;

    /** Number of keywords in the library (关键词数量). */
    private int keywordCount;

    /** Last execution time (上次执行). */
    private String lastRunAt;

    /** Next execution time (下次执行). */
    private String nextRunAt;

    private String createdAt;
}
