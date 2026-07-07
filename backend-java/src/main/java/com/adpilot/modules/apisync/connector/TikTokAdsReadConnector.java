package com.adpilot.modules.apisync.connector;

import com.adpilot.common.config.HttpClientFactory;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.PageCursor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-side connector for TikTok Ads, pulling campaign-level advertising report
 * rows via the TikTok Marketing API integrated report endpoint
 * ({@code GET /open_api/{ver}/report/integrated/get/}). Auto-registered as a
 * Spring bean for {@code platform = "tiktok_ads"}.
 *
 * <p>This is the symmetric read counterpart to
 * {@link com.adpilot.modules.apisync.connector.GoogleAdsConnector#pullAdReports}.
 * Like {@link TikTokAdsWriteConnector} it authenticates with the
 * {@code Access-Token} header and an {@code advertiser_id} query parameter (the
 * TikTok Marketing API does not use the TikTok Shop HMAC signing scheme). It
 * performs no writes.</p>
 *
 * <h2>Honesty contract</h2>
 * <p>Every pull is a REAL signed HTTP call. The connector never fabricates data:
 * <ul>
 *   <li>missing {@code accessToken} / {@code advertiserId} throws before any HTTP call;</li>
 *   <li>a non-zero TikTok business {@code code} on an HTTP-200 envelope is a real
 *       failure surfaced verbatim, never treated as success;</li>
 *   <li>4xx/5xx and transport failures are surfaced as readable errors.</li>
 * </ul>
 * The single HTTP call is isolated in {@link #doExecuteHttp} so offline unit
 * tests can subclass and supply canned responses without touching the network.</p>
 *
 * <h2>Incremental vs full</h2>
 * <p>The integrated report requires an explicit {@code start_date}/{@code end_date}.
 * A non-null {@code since} sets {@code start_date} to its UTC date; a {@code null}
 * {@code since} defaults the window to the last {@value #DEFAULT_LOOKBACK_DAYS}
 * days. {@code end_date} is always "today" (UTC); the read service filters the
 * pulled rows down to the caller's requested window. Paging uses the report's
 * 1-based {@code page} / {@code total_page} echoed on the {@link PageCursor}.</p>
 *
 * <p><strong>VERIFICATION NOTE:</strong> This targets the TikTok Marketing API at
 * <strong>{@value #BASE}</strong> version <strong>{@value #API_VERSION}</strong>.
 * The endpoint path ({@code report/integrated/get/}), the {@code Access-Token}
 * header, the {@code advertiser_id} requirement, the report {@code data_level} /
 * {@code dimensions} / {@code metrics} names, and the response envelope are
 * version-specific and cannot be validated offline. Verify against live
 * credentials and adjust if TikTok changes them; this mirrors the
 * {@link TikTokAdsWriteConnector} verification note.</p>
 */
@Slf4j
@Component
public class TikTokAdsReadConnector {

    static final String PLATFORM = "tiktok_ads";

    /** TikTok Marketing API base URL (matches {@link TikTokAdsWriteConnector}). */
    static final String BASE = "https://business-api.tiktok.com";

    /** TikTok Marketing API version targeted by this connector. */
    static final String API_VERSION = "v1.3";

    /** TikTok integrated-report max page size. */
    static final int PAGE_SIZE = 1000;

    /** Default window when no incremental watermark is supplied. */
    static final int DEFAULT_LOOKBACK_DAYS = 30;

    /** Defensive paging cap to avoid an unbounded loop on a misbehaving feed. */
    static final int MAX_PAGES = 1000;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ObjectMapper objectMapper;
    private final RestClient http;

    public TikTokAdsReadConnector(ObjectMapper objectMapper, HttpClientFactory httpClientFactory) {
        this.objectMapper = objectMapper;
        this.http = httpClientFactory.timeoutRestClientBuilder().build();
    }

    public String platform() {
        return PLATFORM;
    }

    /**
     * Pull a page of campaign/day advertising report rows. Each row is normalized
     * to an {@link ExternalRecord} of entity type {@code "ad_report"} whose
     * {@code fields} carry the same canonical {@code campaign} / {@code metrics} /
     * {@code segments} shape the Google Ads read path uses, so the read service
     * can aggregate both platforms identically.
     */
    public ExternalPage pullAdReports(ConnectionContext ctx, Instant since, PageCursor cursor) {
        String accessToken = trimToNull(ctx.credential("accessToken"));
        if (accessToken == null) {
            throw new IllegalStateException("Connection is missing the required TikTok Ads accessToken");
        }
        String advertiserId = resolveAdvertiserId(ctx);
        if (advertiserId == null) {
            throw new IllegalStateException("Connection is missing the required TikTok Ads advertiserId");
        }

        int page = parsePage(cursor);
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = since != null ? LocalDate.ofInstant(since, ZoneOffset.UTC)
                : end.minusDays(DEFAULT_LOOKBACK_DAYS);
        if (start.isAfter(end)) {
            start = end;
        }

        String url = buildReportUrl(advertiserId, start, end, page);
        String responseBody = execute(url, accessToken);

        JsonNode root = readTree(responseBody);
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            String message = root.path("message").asText("");
            throw new IllegalStateException("TikTok Ads report failed (code " + code + "): "
                    + (message == null || message.isBlank() ? "unknown error" : message));
        }

        JsonNode data = root.path("data");
        List<ExternalRecord> records = new ArrayList<>();
        JsonNode list = data.path("list");
        if (list.isArray()) {
            for (JsonNode item : list) {
                records.add(toAdReportRecord(item));
            }
        }
        return paged(records, data, page);
    }

    // ── request building (package-private for offline tests) ───────────────────

    /**
     * Build the integrated-report GET URL: BASIC report at the AUCTION_CAMPAIGN
     * data level, dimensioned by campaign id + day, requesting the core metrics.
     */
    String buildReportUrl(String advertiserId, LocalDate start, LocalDate end, int page) {
        return UriComponentsBuilder.fromHttpUrl(BASE)
                .path("/open_api/" + API_VERSION + "/report/integrated/get/")
                .queryParam("advertiser_id", advertiserId)
                .queryParam("report_type", "BASIC")
                .queryParam("data_level", "AUCTION_CAMPAIGN")
                .queryParam("dimensions", "[\"campaign_id\",\"stat_time_day\"]")
                .queryParam("metrics",
                        "[\"campaign_name\",\"spend\",\"impressions\",\"clicks\",\"conversion\",\"total_complete_payment\"]")
                .queryParam("start_date", DATE.format(start))
                .queryParam("end_date", DATE.format(end))
                .queryParam("page", page)
                .queryParam("page_size", PAGE_SIZE)
                .encode(StandardCharsets.UTF_8)
                .toUriString();
    }

    // ── HTTP execution ─────────────────────────────────────────────────────────

    private String execute(String url, String accessToken) {
        try {
            return doExecuteHttp("GET", url, accessToken);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new IllegalStateException(
                        "TikTok Ads API rejected the credentials (HTTP " + status
                                + "); re-authorization may be required", e);
            }
            throw new IllegalStateException("TikTok Ads API call failed (HTTP " + status + ")", e);
        }
    }

    /**
     * Performs the single HTTP call. Protected so instrumented subclasses in
     * tests can return canned responses and verify the no-network contract offline.
     */
    protected String doExecuteHttp(String method, String url, String accessToken) {
        return http.method(HttpMethod.valueOf(method)).uri(url)
                .header("Access-Token", accessToken)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .retrieve()
                .body(String.class);
    }

    // ── record normalization & paging ───────────────────────────────────────────

    private ExternalRecord toAdReportRecord(JsonNode item) {
        JsonNode dims = item.path("dimensions");
        JsonNode metrics = item.path("metrics");

        String campaignId = text(dims, "campaign_id");
        String date = normalizeDate(text(dims, "stat_time_day"));

        Map<String, Object> campaign = new LinkedHashMap<>();
        campaign.put("id", campaignId);
        campaign.put("name", text(metrics, "campaign_name"));
        // TikTok's integrated report does not always carry status/budget; read
        // them defensively so the VO surfaces null rather than fabricated values.
        campaign.put("status", firstText(metrics, "campaign_status", "operation_status", "secondary_status"));
        campaign.put("budget", firstText(metrics, "budget", "campaign_budget"));

        Map<String, Object> metricMap = new LinkedHashMap<>();
        metricMap.put("impressions", text(metrics, "impressions"));
        metricMap.put("clicks", text(metrics, "clicks"));
        metricMap.put("spend", text(metrics, "spend"));
        metricMap.put("conversions", text(metrics, "conversion"));
        metricMap.put("conversionValue", firstText(metrics, "total_complete_payment", "conversion_value"));

        Map<String, Object> segments = new LinkedHashMap<>();
        segments.put("date", date);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("campaign", campaign);
        fields.put("metrics", metricMap);
        fields.put("segments", segments);

        String externalId = (campaignId != null ? campaignId : "unknown")
                + (date != null ? ":" + date : "");
        Instant changedAt = date != null ? parseDateToInstant(date) : null;
        return new ExternalRecord(externalId, "ad_report", changedAt, null, fields);
    }

    private ExternalPage paged(List<ExternalRecord> records, JsonNode data, int requestedPage) {
        JsonNode pageInfo = data.path("page_info");
        int currentPage = pageInfo.path("page").asInt(requestedPage);
        int totalPage = pageInfo.path("total_page").asInt(currentPage);
        boolean hasMore = currentPage < totalPage;
        log.info("TikTok Ads pull ad_report -> {} record(s), page={}/{}, hasMore={}",
                records.size(), currentPage, totalPage, hasMore);
        return hasMore
                ? new ExternalPage(records, PageCursor.of(String.valueOf(currentPage + 1)), true)
                : ExternalPage.last(records);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** TikTok report pages are 1-based; the start cursor maps to page 1. */
    private int parsePage(PageCursor cursor) {
        if (cursor == null || cursor.isStart()) {
            return 1;
        }
        try {
            int p = Integer.parseInt(cursor.token().trim());
            return p < 1 ? 1 : p;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Read the advertiser id from either {@code advertiserId} or {@code advertiser_id}. */
    private String resolveAdvertiserId(ConnectionContext ctx) {
        String id = trimToNull(ctx.credential("advertiserId"));
        if (id == null) {
            id = trimToNull(ctx.credential("advertiser_id"));
        }
        return id;
    }

    /** Normalize a TikTok {@code stat_time_day} (e.g. {@code "2024-01-01 00:00:00"}) to {@code yyyy-MM-dd}. */
    private static String normalizeDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        int space = s.indexOf(' ');
        if (space > 0) {
            s = s.substring(0, space);
        }
        if (s.length() > 10) {
            s = s.substring(0, 10);
        }
        return s;
    }

    private Instant parseDateToInstant(String date) {
        try {
            return LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse TikTok Ads response JSON: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = text(node, f);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim();
        return s.isEmpty() ? null : s;
    }
}
