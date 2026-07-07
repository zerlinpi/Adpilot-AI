package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Canonical API schema for a Search_Term (Req 14.1, 14.3).
 *
 * <p>Includes {@code harvestingStatus}, {@code periodStart}, and {@code periodEnd}, and exposes the
 * cost-per-click metric under the single canonical field {@code cpc} (the divergent {@code avgCpc}
 * name is retired here) so the Frontend and Backend agree on one field name.</p>
 */
@Data
@Builder
public class SearchTermVo {

    private String id;
    private String campaignId;
    private String adGroupId;
    private String keywordId;
    private String storeId;
    private String searchTerm;
    private long impressions;
    private int clicks;
    private double spend;
    private double sales;
    private int orders;
    private double acos;
    private double ctr;
    private double cvr;

    /** Canonical cost-per-click (Req 14.3); replaces the divergent {@code avgCpc} field name. */
    private double cpc;

    private double roas;
    private boolean harvested;

    /**
     * Harvest lifecycle status (Req 14.3). One of {@code candidate}, {@code add_exact},
     * {@code add_phrase}, {@code add_broad}, {@code add_negative}, {@code watchlist}, or
     * {@code waste}; a {@code Machine_Value_Enum} the Frontend translates for display.
     */
    private String harvestingStatus;

    /** Inclusive start of the reporting period the metrics cover ({@code yyyy-MM-dd}), or null. */
    private String periodStart;

    /** Inclusive end of the reporting period the metrics cover ({@code yyyy-MM-dd}), or null. */
    private String periodEnd;

    private String createdAt;
    private String updatedAt;
}
