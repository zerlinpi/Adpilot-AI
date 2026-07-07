package com.adpilot.modules.apisync.service;

import com.adpilot.modules.apisync.vo.TikTokAdsCampaignVo;
import com.adpilot.modules.apisync.vo.TikTokAdsPerformanceReportVo;
import com.adpilot.modules.apisync.vo.TikTokAdsReadResult;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-side of the TikTok Ads module, symmetric to
 * {@link GoogleAdsReadService}.
 *
 * <p>Retrieves TikTok Ads campaigns and date-ranged performance reports for an
 * independent-site Store through the
 * {@link com.adpilot.modules.apisync.connector.TikTokAdsReadConnector}. The
 * service is read-only: it never mutates stored data. Every method returns a
 * {@link TikTokAdsReadResult} so the caller can distinguish a successful
 * retrieval ({@code OK}) from the no-active-connection signal
 * ({@code CONNECT_PROMPT}) and a retrieval failure ({@code ERROR}) — honouring
 * the project honesty principle: real fetch, connect prompt when not connected
 * (never fabricated data), readable error on failure.</p>
 */
public interface TikTokAdsReadService {

    /**
     * Retrieve the Store's TikTok Ads campaigns with name, status, budget, and
     * key performance metrics, aggregated across the connector's reporting rows.
     *
     * @param storeId the independent-site Store whose TikTok Ads campaigns to read
     * @return {@code OK} with the campaign list, {@code CONNECT_PROMPT} when the
     *         Store has no active TikTok Ads connection, or {@code ERROR} on a
     *         retrieval failure
     */
    TikTokAdsReadResult<List<TikTokAdsCampaignVo>> getCampaigns(UUID storeId);

    /**
     * Retrieve the Store's TikTok Ads performance metrics (impressions, clicks,
     * spend, conversions, conversion value) for the inclusive date range
     * {@code [from, to]}.
     *
     * @param storeId the independent-site Store whose performance to read
     * @param from    inclusive start date of the report window
     * @param to      inclusive end date of the report window
     * @return {@code OK} with the report, {@code CONNECT_PROMPT} when the Store
     *         has no active TikTok Ads connection, or {@code ERROR} on a
     *         retrieval failure
     */
    TikTokAdsReadResult<TikTokAdsPerformanceReportVo> getPerformanceReport(UUID storeId, LocalDate from, LocalDate to);
}
