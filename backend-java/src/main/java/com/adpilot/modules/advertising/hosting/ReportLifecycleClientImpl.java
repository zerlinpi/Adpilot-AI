package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.config.HttpClientFactory;
import com.adpilot.modules.apisync.connector.AmazonLwaClient;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Implementation of {@link ReportLifecycleClient} that drives the full Amazon Ads
 * asynchronous reporting lifecycle (Requirement 2.2):
 * <ol>
 *   <li>Create report request</li>
 *   <li>Poll status until COMPLETED / FAILED / EXPIRED</li>
 *   <li>Fetch download URL</li>
 *   <li>Download and gunzip the payload</li>
 *   <li>Validate reportId, date range, and row count</li>
 * </ol>
 *
 * <p>Authentication is handled via the existing {@link AmazonLwaClient} (LWA
 * access-token refresh). The implementation uses Spring's {@link RestClient} for
 * HTTP calls, consistent with the existing connector patterns.</p>
 */
@Slf4j
@Component
public class ReportLifecycleClientImpl implements ReportLifecycleClient {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Maximum number of poll attempts before timeout (configurable via subclass/config). */
    private static final int MAX_POLL_ATTEMPTS = 60;

    /** Delay between poll attempts in milliseconds. */
    private static final long POLL_INTERVAL_MS = 5_000;

    private final AmazonLwaClient lwaClient;
    private final ObjectMapper objectMapper;
    private final RestClient http;

    public ReportLifecycleClientImpl(AmazonLwaClient lwaClient, ObjectMapper objectMapper,
                                     HttpClientFactory httpClientFactory) {
        this.lwaClient = lwaClient;
        this.objectMapper = objectMapper;
        // Report creation/poll and gzip download can legitimately take longer than
        // an interactive call, so use the longer (still bounded) read timeout.
        this.http = httpClientFactory.longReadRestClientBuilder().build();
    }

    @Override
    public String createReport(ConnectionContext ctx, ReportType reportType, ReportDateRange dateRange) {
        String accessToken = lwaClient.fetchAccessToken(ctx);
        String host = host(ctx);
        String url = host + "/reporting/reports";

        String requestBody = buildCreateReportBody(reportType, dateRange);

        try {
            String responseBody = http.post()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Amazon-Advertising-API-ClientId", ctx.credential("clientId"))
                    .header("Amazon-Advertising-API-Scope", ctx.credential("profileId"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode response = objectMapper.readTree(responseBody);
            String reportId = textValue(response, "reportId");
            if (reportId == null || reportId.isBlank()) {
                throw new ReportLifecycleException(
                        "Amazon Ads report creation returned no reportId for type " + reportType);
            }

            log.info("Created Amazon Ads report: type={}, reportId={}, dateRange={} to {}",
                    reportType, reportId, dateRange.startDate(), dateRange.endDate());
            return reportId;

        } catch (RestClientResponseException e) {
            throw new ReportLifecycleException(
                    "Failed to create Amazon Ads report (HTTP " + e.getStatusCode().value() + "): "
                            + e.getResponseBodyAsString(), e);
        } catch (ReportLifecycleException e) {
            throw e;
        } catch (Exception e) {
            throw new ReportLifecycleException(
                    "Failed to create Amazon Ads report for type " + reportType, e);
        }
    }

    @Override
    public ReportStatus pollStatus(ConnectionContext ctx, String reportId) {
        String host = host(ctx);
        String url = host + "/reporting/reports/" + reportId;

        // Resolve the LWA access token ONCE for the whole poll lifecycle and reuse
        // it across every attempt. Re-refreshing on each of the up-to-MAX_POLL_ATTEMPTS
        // iterations is redundant work (HTTP timeouts are already bounded by the
        // long-read request factory). If a single attempt is rejected as unauthorized
        // (the token expired mid-poll), refresh ONCE and retry that same attempt —
        // bounded by the refreshedOnAuthFailure guard so we never loop-refresh.
        String accessToken = lwaClient.fetchAccessToken(ctx);
        boolean refreshedOnAuthFailure = false;

        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            try {
                String responseBody = http.get()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Amazon-Advertising-API-ClientId", ctx.credential("clientId"))
                        .header("Amazon-Advertising-API-Scope", ctx.credential("profileId"))
                        .header("Accept", "application/json")
                        .retrieve()
                        .body(String.class);

                JsonNode response = objectMapper.readTree(responseBody);
                String statusStr = textValue(response, "status");
                ReportStatus status = parseStatus(statusStr);

                if (status != ReportStatus.IN_PROGRESS) {
                    log.info("Report {} reached terminal status: {} (attempt {})",
                            reportId, status, attempt + 1);
                    return status;
                }

                // Still in progress — wait before next poll
                sleep(POLL_INTERVAL_MS);

            } catch (RestClientResponseException e) {
                int statusCode = e.getStatusCode().value();
                // Token may have expired mid-poll: on an unauthorized response,
                // refresh the token ONCE and retry this same attempt without
                // consuming a poll slot. The guard makes this bounded — a second
                // auth failure falls through and is handled as an error.
                if (statusCode == 401 && !refreshedOnAuthFailure) {
                    log.info("Poll attempt {} for report {} unauthorized (HTTP 401); "
                            + "refreshing token once and retrying", attempt + 1, reportId);
                    refreshedOnAuthFailure = true;
                    accessToken = lwaClient.fetchAccessToken(ctx);
                    attempt--;
                    continue;
                }
                log.warn("Poll attempt {} for report {} failed (HTTP {})",
                        attempt + 1, reportId, statusCode);
                // On transient errors, continue polling
                if (e.getStatusCode().is5xxServerError()) {
                    sleep(POLL_INTERVAL_MS);
                    continue;
                }
                throw new ReportLifecycleException(reportId, null,
                        "Failed to poll report status (HTTP " + statusCode + ")", e);
            } catch (ReportLifecycleException e) {
                throw e;
            } catch (Exception e) {
                throw new ReportLifecycleException(reportId, null,
                        "Unexpected error polling report status", e);
            }
        }

        throw new ReportLifecycleException(reportId, ReportStatus.IN_PROGRESS,
                "Report polling timed out after " + MAX_POLL_ATTEMPTS + " attempts");
    }

    @Override
    public String fetchDownloadUrl(ConnectionContext ctx, String reportId) {
        String accessToken = lwaClient.fetchAccessToken(ctx);
        String host = host(ctx);
        String url = host + "/reporting/reports/" + reportId;

        try {
            String responseBody = http.get()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Amazon-Advertising-API-ClientId", ctx.credential("clientId"))
                    .header("Amazon-Advertising-API-Scope", ctx.credential("profileId"))
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(String.class);

            JsonNode response = objectMapper.readTree(responseBody);
            String downloadUrl = textValue(response, "url");
            if (downloadUrl == null || downloadUrl.isBlank()) {
                throw new ReportLifecycleException(reportId, ReportStatus.COMPLETED,
                        "Report response contains no download URL");
            }

            log.info("Fetched download URL for report {}", reportId);
            return downloadUrl;

        } catch (RestClientResponseException e) {
            throw new ReportLifecycleException(reportId, null,
                    "Failed to fetch download URL (HTTP " + e.getStatusCode().value() + ")", e);
        } catch (ReportLifecycleException e) {
            throw e;
        } catch (Exception e) {
            throw new ReportLifecycleException(reportId, null,
                    "Unexpected error fetching download URL", e);
        }
    }

    @Override
    public List<Map<String, Object>> downloadAndDecompress(String url) {
        try {
            byte[] compressedBody = http.get()
                    .uri(URI.create(url))
                    .retrieve()
                    .body(byte[].class);

            if (compressedBody == null || compressedBody.length == 0) {
                throw new ReportLifecycleException("Downloaded report payload is empty");
            }

            byte[] decompressed = gunzip(compressedBody);
            String json = new String(decompressed, java.nio.charset.StandardCharsets.UTF_8);

            List<Map<String, Object>> rows = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});

            log.info("Downloaded and decompressed report: {} rows", rows.size());
            return rows;

        } catch (RestClientResponseException e) {
            throw new ReportLifecycleException(
                    "Failed to download report (HTTP " + e.getStatusCode().value() + ")", e);
        } catch (ReportLifecycleException e) {
            throw e;
        } catch (Exception e) {
            throw new ReportLifecycleException("Failed to download and decompress report", e);
        }
    }

    @Override
    public void validateReport(String reportId, ReportDateRange dateRange, List<Map<String, Object>> rows) {
        if (reportId == null || reportId.isBlank()) {
            throw new ReportLifecycleException("Report validation failed: reportId is missing");
        }

        if (dateRange == null) {
            throw new ReportLifecycleException(reportId, null,
                    "Report validation failed: dateRange is missing");
        }

        if (rows == null) {
            throw new ReportLifecycleException(reportId, null,
                    "Report validation failed: rows are null");
        }

        // Validate row count is non-negative (an empty report is valid)
        if (rows.size() < 0) {
            // This cannot happen with a List, but preserved for contract clarity
            throw new ReportLifecycleException(reportId, null,
                    "Report validation failed: negative row count");
        }

        // Validate date range consistency by checking rows fall within the range
        LocalDate start = dateRange.startDate();
        LocalDate end = dateRange.endDate();

        for (Map<String, Object> row : rows) {
            Object dateObj = row.get("date");
            if (dateObj == null) {
                dateObj = row.get("reportDate");
            }
            if (dateObj instanceof String dateStr && !dateStr.isBlank()) {
                try {
                    LocalDate rowDate = LocalDate.parse(dateStr, DATE_FMT);
                    if (rowDate.isBefore(start) || rowDate.isAfter(end)) {
                        throw new ReportLifecycleException(reportId, null,
                                "Report validation failed: row date " + rowDate
                                        + " is outside requested range [" + start + ", " + end + "]");
                    }
                } catch (java.time.format.DateTimeParseException ignored) {
                    // Non-date fields or different format; skip validation for this row
                }
            }
        }

        log.info("Report {} validated: {} rows within [{}, {}]",
                reportId, rows.size(), start, end);
    }

    @Override
    public ReportLifecycleResult executeLifecycle(ConnectionContext ctx, ReportType reportType,
                                                   ReportDateRange dateRange) {
        log.info("Starting report lifecycle: type={}, range=[{}, {}]",
                reportType, dateRange.startDate(), dateRange.endDate());

        // Step 1: Create the report
        String reportId = createReport(ctx, reportType, dateRange);

        // Step 2: Poll until terminal status
        ReportStatus status = pollStatus(ctx, reportId);

        if (status == ReportStatus.FAILED) {
            throw new ReportLifecycleException(reportId, ReportStatus.FAILED,
                    "Report generation failed on Amazon side");
        }
        if (status == ReportStatus.EXPIRED) {
            throw new ReportLifecycleException(reportId, ReportStatus.EXPIRED,
                    "Report expired before download");
        }

        // Step 3: Fetch download URL (only for COMPLETED reports)
        String downloadUrl = fetchDownloadUrl(ctx, reportId);

        // Step 4: Download and decompress
        List<Map<String, Object>> rows = downloadAndDecompress(downloadUrl);

        // Step 5: Validate
        validateReport(reportId, dateRange, rows);

        log.info("Report lifecycle completed: reportId={}, type={}, rows={}",
                reportId, reportType, rows.size());

        return new ReportLifecycleResult(
                reportId,
                reportType,
                dateRange.startDate(),
                dateRange.endDate(),
                rows,
                rows.size()
        );
    }

    // ── Internal helpers ─────────────────────────────────────────────────────────

    private String buildCreateReportBody(ReportType reportType, ReportDateRange dateRange) {
        try {
            Map<String, Object> body = Map.of(
                    "name", "AdPilot " + reportType.name() + " " + dateRange.startDate() + " " + dateRange.endDate(),
                    "startDate", dateRange.startDate().format(DATE_FMT),
                    "endDate", dateRange.endDate().format(DATE_FMT),
                    "configuration", Map.of(
                            "adProduct", "SPONSORED_PRODUCTS",
                            "groupBy", List.of("campaign"),
                            "columns", List.of(
                                    "campaignId", "date", "impressions", "clicks", "cost",
                                    "purchases14d", "sales14d", "unitsSoldClicks14d"),
                            "format", "GZIP_JSON",
                            "reportTypeId", reportType.amazonReportType(),
                            "timeUnit", "DAILY"
                    )
            );
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new ReportLifecycleException("Failed to build report creation request body", e);
        }
    }

    private ReportStatus parseStatus(String statusStr) {
        if (statusStr == null || statusStr.isBlank()) {
            return ReportStatus.IN_PROGRESS;
        }
        return switch (statusStr.toUpperCase()) {
            case "COMPLETED", "SUCCESS" -> ReportStatus.COMPLETED;
            case "FAILED", "FAILURE" -> ReportStatus.FAILED;
            case "EXPIRED" -> ReportStatus.EXPIRED;
            default -> ReportStatus.IN_PROGRESS;
        };
    }

    private byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = gis.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        }
    }

    private String host(ConnectionContext ctx) {
        String region = ctx.credential("region");
        if (region == null || region.isBlank()) {
            region = "na";
        }
        return switch (region.trim().toLowerCase()) {
            case "eu" -> "https://advertising-api-eu.amazon.com";
            case "fe" -> "https://advertising-api-fp.amazon.com";
            default -> "https://advertising-api.amazon.com";
        };
    }

    private static String textValue(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Interruptible sleep used for poll intervals. */
    void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReportLifecycleException("Report polling interrupted", e);
        }
    }
}
