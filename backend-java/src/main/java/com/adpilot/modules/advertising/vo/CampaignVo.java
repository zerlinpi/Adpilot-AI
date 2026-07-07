package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class CampaignVo {

    private String id;
    private String goalId;
    private String storeId;
    private String name;
    private String campaignType;
    private String portfolio;
    private String status;
    private double budget;
    private String budgetType;
    private String startDate;
    private String endDate;
    private String targetingType;
    private String state;
    private double spend;
    private double sales;
    private int orders;
    private long impressions;
    private int clicks;
    private double acos;
    private double roas;
    private double conversionRate;
    private double avgCpc;
    private int adGroupCount;
    private int keywordCount;
    private int negativeKeywordCount;
    private String externalId;
    private boolean hostingEnabled;

    /**
     * AI_Hosting_Status as a {@code Machine_Value_Enum} value — exactly {@code hosted} or
     * {@code not_hosted} — derived from {@link #hostingEnabled} (Req 14.7, 48.4). The Frontend
     * translates this to display copy (托管中 / 未托管); the Backend never returns the Chinese string.
     */
    private String aiHostingStatus;

    private String hostingGoal;

    /**
     * Optimization_Goal as a {@code Machine_Value_Enum} value — one of {@code profit_first},
     * {@code sales_growth}, {@code rank}, or {@code clearance} — normalized from the stored hosting
     * goal (Req 14.7, 48.4). {@code null} when the stored value is absent or unmappable; the Backend
     * never returns a Chinese display string here.
     */
    private String optimizationGoal;

    /**
     * Campaign-level AI_Personality override as a {@code Machine_Value_Enum} value — one of
     * {@code conservative}, {@code balanced}, or {@code aggressive} — or {@code null} when no override
     * is set (Req 14.7, 48.4, 49.2).
     */
    private String campaignPersonality;

    private Double targetAcos;
    private boolean aiManaged;
    private String portfolioId;
    private String parentAsin;
    private String targetingGoal;

    /**
     * IMMUTABLE origin audit fact — exactly {@code local} (created inside the Advertising_Module) or
     * {@code amazon_import} (ingested from Amazon) (Req 12.6). Surfaced so the Frontend can render a
     * local-source badge without re-deriving it.
     */
    private String origin;

    /**
     * The Amazon-assigned campaign id, or {@code null} when the Campaign has not been confirmed on
     * Amazon (Req 12.7). A non-empty value is one of the two conditions that gate Amazon-synced-list
     * inclusion (see {@link #syncedToAmazon}).
     */
    private String amazonCampaignId;

    /**
     * Whether the Campaign belongs in the Amazon-synced campaign list (Req 12.7, 12.8; Property 29).
     * {@code true} iff the Campaign has a non-empty {@link #amazonCampaignId} AND its creation
     * Operation reached the {@code effective} Sync_State (an {@code amazon_import} Campaign with an
     * Amazon id is treated as already effective on the platform). When {@code false}, the Campaign is
     * a local draft that MUST NOT be merged into the Amazon-synced list.
     */
    private boolean syncedToAmazon;

    /**
     * The local-source badge flag (Req 12.8). {@code true} when the Campaign is NOT yet synced to
     * Amazon and must therefore be presented only in a local-drafts view / with a local-source badge
     * ("仅保存在系统，Amazon 未变更"). It is exactly the negation of {@link #syncedToAmazon}.
     */
    private boolean localSource;

    private List<String> tags;
    private String createdAt;
    private String updatedAt;
}
