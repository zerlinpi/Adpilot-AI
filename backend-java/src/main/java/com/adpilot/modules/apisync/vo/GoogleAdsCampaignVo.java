package com.adpilot.modules.apisync.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * A single Google Ads campaign with its identity and aggregated key
 * performance metrics, surfaced in the Google Ads 列表 view
 * (platform-workspace-rbac Req 6.3).
 *
 * <p>Identity ({@code campaignId}, {@code name}, {@code status}) and metrics are
 * derived from the rows returned by the existing
 * {@link com.adpilot.modules.apisync.connector.GoogleAdsConnector}. {@code budget}
 * and {@code conversionValue} are read defensively from the connector payload and
 * may be {@code null} when the connector does not supply them.</p>
 */
@Data
@Builder
public class GoogleAdsCampaignVo {

    private String campaignId;
    private String name;
    private String status;

    /** Campaign budget in account currency; {@code null} when not supplied. */
    private BigDecimal budget;

    private long impressions;
    private long clicks;

    /** Spend in account currency (converted from {@code metrics.cost_micros}). */
    private BigDecimal cost;

    private double conversions;

    /** Total conversion value; {@code null} when not supplied. */
    private BigDecimal conversionValue;
}
