package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.apisync.model.ConnectionContext;

import java.util.List;
import java.util.Map;

/**
 * Encapsulates the full Amazon Ads asynchronous reporting lifecycle per
 * Requirement 2.2: create report → poll until COMPLETED/FAILED/EXPIRED →
 * fetch download URL → download and decompress (GZIP) → validate reportId,
 * date range, and row count.
 *
 * <p>Supports three Sponsored Products report types: campaign, keyword, and
 * search term. Uses the store's {@link ConnectionContext} for authentication
 * via the {@link com.adpilot.modules.apisync.connector.AmazonLwaClient}.</p>
 */
public interface ReportLifecycleClient {

    /**
     * Create a report request on the Amazon Ads Reporting API.
     *
     * @param ctx        the connection context with decrypted credentials
     * @param reportType the SP report type to request
     * @param dateRange  the date range for the report
     * @return the Amazon-assigned report request ID
     * @throws ReportLifecycleException if the report creation fails
     */
    String createReport(ConnectionContext ctx, ReportType reportType, ReportDateRange dateRange);

    /**
     * Poll the Amazon Ads Reporting API for the status of a previously created
     * report request. This method polls repeatedly (with configurable interval
     * and timeout) until the report reaches a terminal state (COMPLETED, FAILED,
     * or EXPIRED).
     *
     * @param ctx      the connection context with decrypted credentials
     * @param reportId the report request ID returned by {@link #createReport}
     * @return the terminal status of the report
     * @throws ReportLifecycleException if polling times out or encounters an error
     */
    ReportStatus pollStatus(ConnectionContext ctx, String reportId);

    /**
     * Fetch the download URL for a completed report.
     *
     * @param ctx      the connection context with decrypted credentials
     * @param reportId the report ID that has reached COMPLETED status
     * @return the URL from which the report payload can be downloaded
     * @throws ReportLifecycleException if the download URL cannot be retrieved
     */
    String fetchDownloadUrl(ConnectionContext ctx, String reportId);

    /**
     * Download the report payload from the given URL and decompress it (GZIP).
     * The downloaded content is parsed into a list of row maps.
     *
     * @param url the download URL returned by {@link #fetchDownloadUrl}
     * @return the decompressed and parsed report rows
     * @throws ReportLifecycleException if download or decompression fails
     */
    List<Map<String, Object>> downloadAndDecompress(String url);

    /**
     * Validate a downloaded report by checking that:
     * <ul>
     *   <li>The reportId matches the expected value</li>
     *   <li>The date range in the report matches the requested range</li>
     *   <li>The row count is non-negative</li>
     * </ul>
     *
     * @param reportId  the expected report ID
     * @param dateRange the requested date range
     * @param rows      the downloaded rows to validate
     * @throws ReportLifecycleException if validation fails
     */
    void validateReport(String reportId, ReportDateRange dateRange, List<Map<String, Object>> rows);

    /**
     * Execute the full asynchronous report lifecycle: create → poll → fetch
     * download URL → download and decompress → validate. This is the primary
     * entry point for callers that want the entire lifecycle in one call.
     *
     * @param ctx        the connection context with decrypted credentials
     * @param reportType the SP report type to request
     * @param dateRange  the date range for the report
     * @return the complete lifecycle result with validated rows
     * @throws ReportLifecycleException if any step in the lifecycle fails
     */
    ReportLifecycleResult executeLifecycle(ConnectionContext ctx, ReportType reportType, ReportDateRange dateRange);
}
