package com.adpilot.modules.apisync.connector;

import com.adpilot.common.config.HttpClientFactory;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.PageCursor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link PlatformDataConnector} for Shopify, pulling orders and products via
 * the Shopify Admin REST API (Req 1.1.1). The admin access token is supplied,
 * already decrypted, on the {@link ConnectionContext} and sent only to the
 * shop's own admin endpoint via the {@code X-Shopify-Access-Token} header;
 * it is never logged.
 *
 * <h2>Incremental vs full (Req 1.1.6 / 1.1.7)</h2>
 * <p>A {@code null} {@code since} performs a full pull. A non-null
 * {@code since} adds {@code updated_at_min} so only records changed at/after
 * the watermark are returned, ordered by {@code updated_at} ascending.</p>
 *
 * <h2>Paging (Req 1.1.6)</h2>
 * <p>Shopify uses cursor-based paging: each response's {@code Link} header
 * carries a {@code page_info} token for the next page. The {@link PageCursor}
 * carries that opaque token; when present, Shopify forbids combining it with
 * filters, so only {@code limit} accompanies a continuation request.
 * {@link ExternalPage#hasMore()} is true while a next {@code page_info} is
 * present.</p>
 */
@Slf4j
@Component
public class ShopifyConnector extends AbstractRestDataConnector {

    static final String PLATFORM = "shopify";
    static final String API_VERSION = "2024-01";
    /** Shopify caps limit at 250 for these resources. */
    static final int LIMIT = 250;

    private static final Pattern NEXT_PAGE_INFO = Pattern.compile(
            "<[^>]*[?&]page_info=([^&>]+)[^>]*>\\s*;\\s*rel=\"next\"");

    public ShopifyConnector(ObjectMapper objectMapper, HttpClientFactory httpClientFactory) {
        super(objectMapper, httpClientFactory);
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public ExternalPage pullOrders(ConnectionContext ctx, Instant since, PageCursor cursor) {
        return pull(ctx, since, cursor, "orders", "order");
    }

    @Override
    public ExternalPage pullProducts(ConnectionContext ctx, Instant since, PageCursor cursor) {
        return pull(ctx, since, cursor, "products", "product");
    }

    private ExternalPage pull(ConnectionContext ctx, Instant since, PageCursor cursor,
                              String resource, String entityType) {
        String url = buildUrl(ctx, resource, since, cursor);
        ResponseEntity<String> resp = http.get().uri(url)
                .header("X-Shopify-Access-Token", ctx.credential("accessToken"))
                .retrieve()
                .toEntity(String.class);

        JsonNode root = readTree(resp.getBody());
        List<ExternalRecord> records = new ArrayList<>();
        JsonNode array = root != null ? root.get(resource) : null;
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                records.add(toRecord(node, entityType));
            }
        }

        String nextPageInfo = nextPageInfo(resp.getHeaders().getFirst("Link"));
        boolean hasMore = nextPageInfo != null;
        log.info("Shopify pull {} -> {} record(s), hasMore={}", entityType, records.size(), hasMore);
        if (!hasMore) {
            return ExternalPage.last(records);
        }
        return new ExternalPage(records, PageCursor.of(nextPageInfo), true);
    }

    /** Normalize a Shopify order/product JSON node into an {@link ExternalRecord}. */
    private ExternalRecord toRecord(JsonNode node, String entityType) {
        String externalId = text(node, "id");
        Instant changedAt = parseInstant(firstNonNull(
                text(node, "updated_at"),
                text(node, "created_at")));
        Map<String, Object> fields = toFieldMap(node);
        return new ExternalRecord(externalId, entityType, changedAt, statusOf(node, entityType), fields);
    }

    /**
     * Derive a normalized status. A cancelled order reports a non-null
     * {@code cancelled_at}; otherwise we fall back to the financial status.
     * Products carry an explicit {@code status} (active/draft/archived).
     */
    private static String statusOf(JsonNode node, String entityType) {
        if ("order".equals(entityType)) {
            if (text(node, "cancelled_at") != null) {
                return "cancelled";
            }
            String financial = text(node, "financial_status");
            return financial != null ? financial : text(node, "fulfillment_status");
        }
        return text(node, "status");
    }

    private String buildUrl(ConnectionContext ctx, String resource, Instant since, PageCursor cursor) {
        String shop = stripTrailingSlash(ctx.credential("shopDomain")).replaceFirst("^https?://", "");
        StringBuilder url = new StringBuilder("https://").append(shop)
                .append("/admin/api/").append(API_VERSION).append("/")
                .append(resource).append(".json?limit=").append(LIMIT);
        if (cursor != null && !cursor.isStart()) {
            // page_info continuation: Shopify forbids combining it with filters.
            url.append("&page_info=").append(cursor.token());
        } else {
            url.append("&order=updated_at+asc");
            if (since != null) {
                url.append("&updated_at_min=").append(toIso(since));
            }
            if ("orders".equals(resource)) {
                // Default order listing excludes archived/cancelled; "any" includes all.
                url.append("&status=any");
            }
        }
        return url.toString();
    }

    /** Extract the {@code page_info} of the {@code rel="next"} link, or null. */
    static String nextPageInfo(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) {
            return null;
        }
        Matcher m = NEXT_PAGE_INFO.matcher(linkHeader);
        return m.find() ? m.group(1) : null;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
