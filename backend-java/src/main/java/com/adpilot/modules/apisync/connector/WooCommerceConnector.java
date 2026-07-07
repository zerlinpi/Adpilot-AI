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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * {@link PlatformDataConnector} for WooCommerce, pulling orders and products
 * via the WooCommerce REST API v3 (Req 1.1.1). Credentials are supplied,
 * already decrypted, on the {@link ConnectionContext}; they are sent only to
 * the store's own endpoint as HTTP Basic auth and never logged.
 *
 * <h2>Incremental vs full (Req 1.1.6 / 1.1.7)</h2>
 * <p>A {@code null} {@code since} performs a full pull. A non-null
 * {@code since} adds {@code modified_after} (+{@code dates_are_gmt=true}) so
 * only records changed strictly after the watermark are returned. Results are
 * ordered by modification time ascending so the runner can advance the
 * watermark to the last processed record.</p>
 *
 * <h2>Paging (Req 1.1.6)</h2>
 * <p>WooCommerce uses page-number paging. The {@link PageCursor} carries the
 * 1-based page number; {@link ExternalPage#hasMore()} is true while a full
 * page ({@code per_page}) is returned.</p>
 */
@Slf4j
@Component
public class WooCommerceConnector extends AbstractRestDataConnector {

    static final String PLATFORM = "woocommerce";
    /** WooCommerce caps per_page at 100. */
    static final int PER_PAGE = 100;

    public WooCommerceConnector(ObjectMapper objectMapper, HttpClientFactory httpClientFactory) {
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
        int page = pageOf(cursor);
        String url = buildUrl(ctx, resource, since, page);
        String body = http.get().uri(url)
                .header("Authorization", basicAuth(ctx))
                .retrieve()
                .body(String.class);

        JsonNode root = readTree(body);
        List<ExternalRecord> records = new ArrayList<>();
        if (root != null && root.isArray()) {
            for (JsonNode node : root) {
                records.add(toRecord(node, entityType));
            }
        }

        boolean hasMore = records.size() >= PER_PAGE;
        log.info("WooCommerce pull {} page={} -> {} record(s), hasMore={}",
                entityType, page, records.size(), hasMore);
        if (!hasMore) {
            return ExternalPage.last(records);
        }
        return new ExternalPage(records, PageCursor.of(Integer.toString(page + 1)), true);
    }

    /** Normalize a WooCommerce order/product JSON node into an {@link ExternalRecord}. */
    private ExternalRecord toRecord(JsonNode node, String entityType) {
        String externalId = text(node, "id");
        // Prefer the GMT modification time for incremental correctness; fall back
        // to created time, then non-GMT variants.
        Instant changedAt = parseInstant(firstNonNull(
                text(node, "date_modified_gmt"),
                text(node, "date_created_gmt"),
                text(node, "date_modified"),
                text(node, "date_created")));
        String status = text(node, "status");
        Map<String, Object> fields = toFieldMap(node);
        return new ExternalRecord(externalId, entityType, changedAt, status, fields);
    }

    private String buildUrl(ConnectionContext ctx, String resource, Instant since, int page) {
        String site = stripTrailingSlash(ctx.credential("siteUrl"));
        StringBuilder url = new StringBuilder(site)
                .append("/wp-json/wc/v3/").append(resource)
                .append("?per_page=").append(PER_PAGE)
                .append("&page=").append(page)
                .append("&orderby=modified&order=asc");
        if (since != null) {
            url.append("&modified_after=").append(toIso(since))
               .append("&dates_are_gmt=true");
        }
        return url.toString();
    }

    private static String basicAuth(ConnectionContext ctx) {
        String creds = ctx.credential("consumerKey") + ":" + ctx.credential("consumerSecret");
        return "Basic " + Base64.getEncoder()
                .encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    private static int pageOf(PageCursor cursor) {
        if (cursor == null || cursor.isStart()) return 1;
        try {
            int p = Integer.parseInt(cursor.token().trim());
            return Math.max(p, 1);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
