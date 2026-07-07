package com.adpilot.modules.apisync.connector;

import com.adpilot.common.config.HttpClientFactory;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.PageCursor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link PlatformDataConnector} for the Amazon Selling Partner API (SP-API),
 * pulling orders and inventory (Req 8.1.1). Authenticates with a Login-with-
 * Amazon (LWA) access token sent in the {@code x-amz-access-token} header, and
 * signs each request with AWS Signature Version 4 when IAM credentials are
 * configured (Req 8.1.2, {@link AwsV4Signer}).
 *
 * <h2>Re-auth (Req 8.1.5)</h2>
 * <p>The LWA token is refreshed via {@link AmazonLwaClient} at the start of
 * each pull; an expired/invalid token throws {@link ReauthRequiredException}
 * so the runner marks the connection as requiring re-authorization.</p>
 *
 * <h2>Incremental vs full (Req 1.1.6 / 1.1.7)</h2>
 * <p>A {@code null} {@code since} performs a full pull; a non-null
 * {@code since} adds {@code LastUpdatedAfter} (orders) /
 * {@code startDateTime} (inventory) so only records changed at/after the
 * watermark are returned. SP-API paging uses an opaque {@code NextToken}
 * carried on the {@link PageCursor}.</p>
 */
@Slf4j
@Component
public class AmazonSpApiConnector extends AbstractAmazonConnector {

    static final String PLATFORM = "amazon_sp_api";
    static final String SERVICE = "execute-api";

    public AmazonSpApiConnector(ObjectMapper objectMapper, AmazonLwaClient lwaClient,
                                HttpClientFactory httpClientFactory) {
        super(objectMapper, lwaClient, httpClientFactory);
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public ExternalPage pullOrders(ConnectionContext ctx, Instant since, PageCursor cursor) {
        String accessToken = lwaClient.fetchAccessToken(ctx);
        String url = buildOrdersUrl(ctx, since, cursor);
        JsonNode payload = get(ctx, url, accessToken).path("payload");

        List<ExternalRecord> records = new ArrayList<>();
        JsonNode orders = payload.path("Orders");
        if (orders.isArray()) {
            for (JsonNode node : orders) {
                records.add(toOrderRecord(node));
            }
        }
        return paged(records, payload, "order");
    }

    @Override
    public ExternalPage pullInventory(ConnectionContext ctx, Instant since, PageCursor cursor) {
        String accessToken = lwaClient.fetchAccessToken(ctx);
        String url = buildInventoryUrl(ctx, since, cursor);
        JsonNode root = get(ctx, url, accessToken);
        JsonNode payload = root.path("payload");

        List<ExternalRecord> records = new ArrayList<>();
        JsonNode summaries = payload.path("inventorySummaries");
        if (summaries.isArray()) {
            for (JsonNode node : summaries) {
                records.add(toInventoryRecord(node));
            }
        }
        // FBA inventory paging carries the token under pagination.nextToken.
        String nextToken = text(payload.path("pagination"), "nextToken");
        return pagedWithToken(records, nextToken, "inventory");
    }

    /**
     * SP-API does not back the generic product feed used by WooCommerce/Shopify;
     * Amazon catalog/listing sync is out of scope for this connector.
     */
    @Override
    public ExternalPage pullProducts(ConnectionContext ctx, Instant since, PageCursor cursor) {
        throw new UnsupportedOperationException(
                "Amazon SP-API connector does not pull products; use orders/inventory");
    }

    // ── request building & signing ────────────────────────────────────────────

    private JsonNode get(ConnectionContext ctx, String url, String accessToken) {
        URI uri = URI.create(url);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-amz-access-token", accessToken);
        headers.put("Accept", "application/json");

        // Req 8.1.2: sign with SigV4 when IAM credentials are configured.
        String awsKey = ctx.credential("awsAccessKeyId");
        String awsSecret = ctx.credential("awsSecretKey");
        if (awsKey != null && awsSecret != null) {
            Map<String, String> signed = AwsV4Signer.sign(
                    "GET", uri, SERVICE, awsRegion(ctx),
                    awsKey, awsSecret, ctx.credential("awsSessionToken"),
                    new byte[0], Instant.now());
            headers.putAll(signed);
        }

        org.springframework.web.client.RestClient.RequestHeadersSpec<?> spec = http.get().uri(uri);
        headers.forEach(spec::header);
        try {
            String body = spec.retrieve().body(String.class);
            return readTree(body);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            // A 401/403 here means the access token was rejected as expired/invalid.
            if (status == 401 || status == 403) {
                throw new ReauthRequiredException(ctx.connectionId(),
                        "Amazon SP-API rejected the access token (HTTP " + status + ")", e);
            }
            throw e;
        }
    }

    private String buildOrdersUrl(ConnectionContext ctx, Instant since, PageCursor cursor) {
        StringBuilder url = new StringBuilder(host(ctx)).append("/orders/v0/orders");
        if (cursor != null && !cursor.isStart()) {
            // NextToken continuation must be sent alone (URL-encoded).
            url.append("?NextToken=").append(enc(cursor.token()));
            return url.toString();
        }
        url.append("?MarketplaceIds=").append(enc(marketplaceId(ctx)));
        if (since != null) {
            url.append("&LastUpdatedAfter=").append(enc(toIso(since)));
        } else {
            // SP-API requires a lower bound; use the epoch for a full pull.
            url.append("&CreatedAfter=").append(enc("1970-01-01T00:00:00Z"));
        }
        return url.toString();
    }

    private String buildInventoryUrl(ConnectionContext ctx, Instant since, PageCursor cursor) {
        StringBuilder url = new StringBuilder(host(ctx)).append("/fba/inventory/v1/summaries");
        if (cursor != null && !cursor.isStart()) {
            url.append("?nextToken=").append(enc(cursor.token()));
            return url.toString();
        }
        url.append("?details=true")
           .append("&granularityType=Marketplace")
           .append("&granularityId=").append(enc(marketplaceId(ctx)))
           .append("&marketplaceIds=").append(enc(marketplaceId(ctx)));
        if (since != null) {
            url.append("&startDateTime=").append(enc(toIso(since)));
        }
        return url.toString();
    }

    // ── record normalization ───────────────────────────────────────────────────

    private ExternalRecord toOrderRecord(JsonNode node) {
        String externalId = text(node, "AmazonOrderId");
        Instant changedAt = parseInstant(firstNonNull(
                text(node, "LastUpdateDate"),
                text(node, "PurchaseDate")));
        String status = text(node, "OrderStatus");
        return new ExternalRecord(externalId, "order", changedAt, status, toFieldMap(node));
    }

    private ExternalRecord toInventoryRecord(JsonNode node) {
        String externalId = firstNonNull(text(node, "sellerSku"), text(node, "asin"), text(node, "fnSku"));
        Instant changedAt = parseInstant(text(node, "lastUpdatedTime"));
        return new ExternalRecord(externalId, "inventory", changedAt, text(node, "condition"), toFieldMap(node));
    }

    // ── paging helpers ──────────────────────────────────────────────────────────

    private ExternalPage paged(List<ExternalRecord> records, JsonNode payload, String entityType) {
        return pagedWithToken(records, text(payload, "NextToken"), entityType);
    }

    private ExternalPage pagedWithToken(List<ExternalRecord> records, String nextToken, String entityType) {
        boolean hasMore = nextToken != null && !nextToken.isBlank();
        log.info("Amazon SP-API pull {} -> {} record(s), hasMore={}", entityType, records.size(), hasMore);
        if (!hasMore) {
            return ExternalPage.last(records);
        }
        return new ExternalPage(records, PageCursor.of(nextToken), true);
    }

    // ── config helpers ───────────────────────────────────────────────────────────

    /** SP-API regional endpoint host derived from the connection's region. */
    private String host(ConnectionContext ctx) {
        return switch (region(ctx.credential("region"))) {
            case "eu" -> "https://sellingpartnerapi-eu.amazon.com";
            case "fe" -> "https://sellingpartnerapi-fe.amazon.com";
            default -> "https://sellingpartnerapi-na.amazon.com";
        };
    }

    /** AWS region used for SigV4 signing, mapped from the SP-API region. */
    private String awsRegion(ConnectionContext ctx) {
        return switch (region(ctx.credential("region"))) {
            case "eu" -> "eu-west-1";
            case "fe" -> "us-west-2";
            default -> "us-east-1";
        };
    }

    private String marketplaceId(ConnectionContext ctx) {
        String marketplaceId = ctx.credential("marketplaceId");
        return marketplaceId != null ? marketplaceId : "";
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
