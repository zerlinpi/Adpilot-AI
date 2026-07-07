package com.adpilot.modules.apisync.connector;

import com.adpilot.common.config.HttpClientFactory;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.PageCursor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link PlatformDataConnector} for Google Ads, pulling advertising report rows
 * via the Google Ads REST API GAQL search endpoint (Req 8.1.1, 8.1.3).
 * Auto-registered as a Spring bean so the sync runner can resolve it for
 * {@code platform = "google_ads"}.
 *
 * <p>Credentials (decrypted on the {@link ConnectionContext}, schema declared by
 * {@link PlatformConnector#fields(String)}): {@code clientId}, {@code clientSecret},
 * {@code refreshToken}, {@code developerToken}, {@code customerId}. An optional
 * {@code loginCustomerId} (manager / MCC account id) is read defensively when
 * present and sent as the {@code login-customer-id} header; it is not part of the
 * required schema.</p>
 *
 * <h2>Authentication &amp; re-auth (Req 8.1.5)</h2>
 * <p>A fresh OAuth access token is obtained via {@link GoogleAdsTokenClient} at
 * the start of each pull. An expired/invalid refresh token surfaces as a
 * {@link ReauthRequiredException} from the token client, and a 401/403 from the
 * Ads API itself is also translated to {@link ReauthRequiredException} so the
 * runner marks the connection as requiring re-authorization rather than treating
 * it as a transient error.</p>
 *
 * <h2>Incremental vs full (Req 1.1.6 / 1.1.7)</h2>
 * <p>A {@code null} {@code since} pulls the report without a lower date bound; a
 * non-null {@code since} adds a {@code segments.date >= 'YYYY-MM-DD'} filter so
 * only rows on/after the watermark date are returned. Google Ads paging uses an
 * opaque {@code nextPageToken} echoed back on the {@link PageCursor}.</p>
 *
 * <p><strong>VERIFICATION NOTE:</strong> This connector targets Google Ads API
 * <strong>{@value #API_VERSION}</strong> over REST. The API version path,
 * resource/field names, and GAQL grammar are version-specific and cannot be
 * validated offline. Verify against live credentials and adjust the API version
 * / field names (e.g. {@code metrics.cost_micros}, {@code segments.date}) if
 * Google changes them. On any failure the connector throws and the sync job
 * records the error gracefully (it never crashes the app); this mirrors the
 * {@link TikTokConnector} verification note.</p>
 */
@Slf4j
@Component
public class GoogleAdsConnector extends AbstractRestDataConnector {

    static final String PLATFORM = "google_ads";

    /** Google Ads REST API version targeted by this connector. */
    static final String API_VERSION = "v17";

    static final String BASE = "https://googleads.googleapis.com";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final GoogleAdsTokenClient tokenClient;

    public GoogleAdsConnector(ObjectMapper objectMapper, GoogleAdsTokenClient tokenClient,
                              HttpClientFactory httpClientFactory) {
        super(objectMapper, httpClientFactory);
        this.tokenClient = tokenClient;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    /**
     * Google Ads validates its own credentials by refreshing the OAuth token as
     * part of the pull and signalling re-auth via {@link ReauthRequiredException},
     * so it opts out of the runner's generic credential pre-check (Req 8.1.5).
     */
    @Override
    public boolean selfValidatesCredentials() {
        return true;
    }

    /**
     * Pull a page of advertising report rows (campaign-level metrics) via GAQL
     * search. Each row is normalized to an {@link ExternalRecord} of entity type
     * {@code "ad_report"}.
     */
    @Override
    public ExternalPage pullAdReports(ConnectionContext ctx, Instant since, PageCursor cursor) {
        String accessToken = tokenClient.fetchAccessToken(ctx);
        String customerId = normalizeCustomerId(ctx.credential("customerId"));
        String url = buildSearchUrl(customerId);
        String pageToken = (cursor != null && !cursor.isStart()) ? cursor.token() : null;
        String requestBody = buildSearchBody(since, pageToken);

        JsonNode root = search(ctx, url, accessToken, requestBody);

        List<ExternalRecord> records = new ArrayList<>();
        JsonNode results = root.path("results");
        if (results.isArray()) {
            for (JsonNode rowNode : results) {
                records.add(toAdReportRecord(rowNode));
            }
        }
        String nextPageToken = text(root, "nextPageToken");
        return paged(records, nextPageToken);
    }

    /**
     * Google Ads exposes no orders feed; like SP-API does for products, this is
     * unsupported.
     */
    @Override
    public ExternalPage pullOrders(ConnectionContext ctx, Instant since, PageCursor cursor) {
        throw new UnsupportedOperationException(
                "Google Ads connector does not pull orders; use ad reports");
    }

    /**
     * Google Ads exposes no products feed; like SP-API does for products, this is
     * unsupported.
     */
    @Override
    public ExternalPage pullProducts(ConnectionContext ctx, Instant since, PageCursor cursor) {
        throw new UnsupportedOperationException(
                "Google Ads connector does not pull products; use ad reports");
    }

    // ── request building (package-private for offline tests) ───────────────────

    /** Build the GAQL search endpoint URL for a customer. */
    String buildSearchUrl(String customerId) {
        return BASE + "/" + API_VERSION + "/customers/" + customerId + "/googleAds:search";
    }

    /**
     * Build the JSON request body: a GAQL query selecting campaign + core metrics,
     * with an optional {@code segments.date} lower bound and an optional
     * {@code pageToken} continuation.
     */
    String buildSearchBody(Instant since, String pageToken) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", buildGaqlQuery(since));
        if (pageToken != null && !pageToken.isBlank()) {
            body.put("pageToken", pageToken);
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build Google Ads search body", e);
        }
    }

    /**
     * Build the GAQL query string selecting campaign identity plus impressions,
     * clicks, cost, and conversions, filtered by {@code segments.date} when a
     * {@code since} watermark is supplied.
     */
    String buildGaqlQuery(Instant since) {
        StringBuilder gaql = new StringBuilder(
                "SELECT campaign.id, campaign.name, campaign.status, segments.date, "
                        + "metrics.impressions, metrics.clicks, metrics.cost_micros, metrics.conversions "
                        + "FROM campaign");
        if (since != null) {
            String date = DATE.format(LocalDate.ofInstant(since, ZoneOffset.UTC));
            gaql.append(" WHERE segments.date >= '").append(date).append("'");
        }
        gaql.append(" ORDER BY segments.date");
        return gaql.toString();
    }

    /** Normalize a Google Ads customer id by stripping dashes and whitespace. */
    static String normalizeCustomerId(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[^0-9]", "");
    }

    // ── HTTP execution ─────────────────────────────────────────────────────────

    private JsonNode search(ConnectionContext ctx, String url, String accessToken, String body) {
        try {
            String resp = http.post().uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header("developer-token", ctx.credential("developerToken"))
                    .headers(h -> {
                        String loginCustomerId = resolveLoginCustomerId(ctx);
                        if (loginCustomerId != null) {
                            h.set("login-customer-id", loginCustomerId);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return readTree(resp);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            // 401/403 means the access token / developer token was rejected.
            if (status == 401 || status == 403) {
                throw new ReauthRequiredException(ctx.connectionId(),
                        "Google Ads API rejected the credentials (HTTP " + status + ")", e);
            }
            throw new IllegalStateException(
                    "Google Ads API call failed (HTTP " + status + ")", e);
        }
    }

    /**
     * Resolve the optional manager (MCC) login-customer-id header value. Not part
     * of the required credential schema; read defensively and normalized when present.
     */
    private String resolveLoginCustomerId(ConnectionContext ctx) {
        String raw = ctx.credential("loginCustomerId");
        if (raw == null || raw.isBlank()) {
            raw = ctx.credential("managerCustomerId");
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = normalizeCustomerId(raw);
        return normalized.isBlank() ? null : normalized;
    }

    // ── record normalization & paging ───────────────────────────────────────────

    private ExternalRecord toAdReportRecord(JsonNode rowNode) {
        JsonNode campaign = rowNode.path("campaign");
        JsonNode segments = rowNode.path("segments");
        String campaignId = text(campaign, "id");
        String date = text(segments, "date");
        // Composite external id keeps each campaign/day row distinct as the
        // idempotency anchor for the report.
        String externalId = (campaignId != null ? campaignId : "unknown")
                + (date != null ? ":" + date : "");
        Instant changedAt = date != null ? parseInstant(date + "T00:00:00Z") : null;
        String status = text(campaign, "status");
        return new ExternalRecord(externalId, "ad_report", changedAt, status, toFieldMap(rowNode));
    }

    private ExternalPage paged(List<ExternalRecord> records, String nextPageToken) {
        boolean hasMore = nextPageToken != null && !nextPageToken.isBlank();
        log.info("Google Ads pull ad_report -> {} record(s), hasMore={}", records.size(), hasMore);
        return hasMore ? new ExternalPage(records, PageCursor.of(nextPageToken), true)
                : ExternalPage.last(records);
    }
}
