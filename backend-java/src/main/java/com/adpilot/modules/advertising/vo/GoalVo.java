package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Canonical API schema for a Goal (Req 14.1, 14.4).
 *
 * <p>Carries the associated {@code campaigns}, {@code products}, and {@code trendData} collections.
 * {@code campaignCount} is a <strong>derived</strong> value that always equals the length of the
 * {@code campaigns} collection (Req 14.4, Property 33) — it is not stored independently, so the count
 * can never drift from the collection it describes.</p>
 */
@Data
@Builder
public class GoalVo {

    private String id;
    private String storeId;
    private String name;
    private String type;
    private String status;
    private double targetAcos;
    private double dailyBudget;
    private double maxCpc;
    private double minBid;
    private double maxBid;
    private List<String> brandKeywords;
    private List<String> categoryKeywords;
    private List<String> competitorBrands;
    private List<String> competitorAsins;
    private boolean autoNegate;
    private boolean autoBid;
    private boolean autoExpand;
    private String optimizeFrequency;
    private String riskPreference;
    private List<String> productIds;

    /**
     * Optimization_Goal as a {@code Machine_Value_Enum} value — one of {@code profit_first},
     * {@code sales_growth}, {@code rank}, or {@code clearance} — normalized from the stored goal
     * {@code type} (Req 14.7, 48.4, 57.4). {@code null} when the stored value is absent or unmappable;
     * the Backend never returns a Chinese display string here. The Frontend owns display translation.
     */
    private String optimizationGoal;

    /** Campaigns associated with this Goal (Req 14.4). {@code campaignCount} is derived from this. */
    private List<CampaignVo> campaigns;

    /** Product identifiers/ASINs associated with this Goal (Req 14.4). */
    private List<String> products;

    /** Period-over-period trend buckets for the Goal's campaigns (Req 14.4). */
    private List<CampaignTrendPointVo> trendData;

    private PerformanceSummaryVo performance;
    private String createdAt;
    private String updatedAt;

    /**
     * The number of associated campaigns, derived strictly from the {@link #campaigns} collection so
     * it always equals that collection's length (Req 14.4, Property 33).
     */
    public int getCampaignCount() {
        return campaigns == null ? 0 : campaigns.size();
    }
}
