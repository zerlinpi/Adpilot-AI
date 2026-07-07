package com.adpilot.modules.apisync.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * A single TikTok Ads campaign with its identity and aggregated key performance
 * metrics, surfaced in the TikTok Ads 列表 view. Mirrors
 * {@link GoogleAdsCampaignVo} so the frontend renders both platforms with one
 * shape.
 *
 * <p>Identity ({@code campaignId}, {@code name}, {@code status}) and metrics are
 * derived from the rows returned by the
 * {@link com.adpilot.modules.apisync.connector.TikTokAdsReadConnector}. TikTok's
 * integrated report does not always expose campaign {@code status} / {@code
 * budget}; both are read defensively and may be {@code null} when the connector
 * does not supply them.</p>
 */
@Data
@Builder
public class TikTokAdsCampaignVo {

    private String campaignId;
    private String name;
    private String status;

    /** Campaign budget in account currency; {@code null} when not supplied. */
    private BigDecimal budget;

    private long impressions;
    private long clicks;

    /** Spend in account currency (TikTok reports spend directly, not in micros). */
    private BigDecimal cost;

    private double conversions;

    /** Total conversion value; {@code null} when not supplied. */
    private BigDecimal conversionValue;
}
