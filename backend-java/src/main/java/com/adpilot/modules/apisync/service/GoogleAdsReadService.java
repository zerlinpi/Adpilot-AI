package com.adpilot.modules.apisync.service;

import com.adpilot.modules.apisync.vo.GoogleAdsCampaignVo;
import com.adpilot.modules.apisync.vo.GoogleAdsPerformanceReportVo;
import com.adpilot.modules.apisync.vo.GoogleAdsReadResult;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-side of the GoogleAds_Module (platform-workspace-rbac Req 6).
 *
 * <p>Retrieves Google Ads campaigns and date-ranged performance reports for an
 * independent-site Store through the existing
 * {@link com.adpilot.modules.apisync.connector.GoogleAdsConnector}. The service
 * is read-only: it never mutates stored data. Every method returns a
 * {@link GoogleAdsReadResult} so the caller can distinguish a successful
 * retrieval ({@code OK}) from the no-active-connection signal
 * ({@code CONNECT_PROMPT}, Req 6.6) and a retrieval failure ({@code ERROR},
 * Req 6.5) — on failure the caller leaves any previously displayed data
 * unchanged.</p>
 */
public interface GoogleAdsReadService {

    /**
     * Retrieve the Store's Google Ads campaigns with name, status, budget, and
     * key performance metrics, aggregated across the connector's reporting rows
     * (Req 6.1, 6.3).
     *
     * @param storeId the independent-site Store whose Google Ads campaigns to read
     * @return {@code OK} with the campaign list, {@code CONNECT_PROMPT} when the
     *         Store has no active Google Ads connection, or {@code ERROR} on a
     *         retrieval failure
     */
    GoogleAdsReadResult<List<GoogleAdsCampaignVo>> getCampaigns(UUID storeId);

    /**
     * Retrieve the Store's Google Ads performance metrics (impressions, clicks,
     * cost, conversions, conversion value) for the inclusive date range
     * {@code [from, to]} (Req 6.2, 6.4).
     *
     * @param storeId the independent-site Store whose performance to read
     * @param from    inclusive start date of the report window
     * @param to      inclusive end date of the report window
     * @return {@code OK} with the report, {@code CONNECT_PROMPT} when the Store
     *         has no active Google Ads connection, or {@code ERROR} on a
     *         retrieval failure
     */
    GoogleAdsReadResult<GoogleAdsPerformanceReportVo> getPerformanceReport(UUID storeId, LocalDate from, LocalDate to);
}
