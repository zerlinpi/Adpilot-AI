package com.adpilot.modules.apisync.connector;

import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.PageCursor;
import com.adpilot.modules.apisync.support.MockApiServer;
import com.adpilot.modules.apisync.support.MockHttpRedirect;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link ShopifyConnector} against a real local HTTP
 * server ({@link MockApiServer}). Shopify builds absolute {@code https://}
 * admin URLs from the shop domain, so the connector's outbound calls are
 * redirected to the mock server via {@link MockHttpRedirect}; the full
 * {@code RestClient} stack — URL building, the {@code X-Shopify-Access-Token}
 * header, JSON parsing, and {@code Link}-header cursor paging — runs end to end
 * over a real socket.
 *
 * <ul>
 *   <li>Req 1.1.1 — orders and products are retrieved using the connection's
 *       stored admin access token.</li>
 * </ul>
 */
class ShopifyConnectorIntegrationTest {

    private static final String ACCESS_TOKEN = "shpat_test_token";

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID connectionId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();

    private MockApiServer server;
    private ShopifyConnector connector;

    @BeforeEach
    void setUp() throws IOException {
        server = MockApiServer.http();
        connector = new ShopifyConnector(mapper, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));
        // Shopify hard-codes the https host, so rewrite the host to the mock server.
        MockHttpRedirect.redirect(connector, server.host());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private ConnectionContext context() {
        Map<String, String> creds = new LinkedHashMap<>();
        creds.put("shopDomain", "test-shop.myshopify.com");
        creds.put("accessToken", ACCESS_TOKEN);
        return new ConnectionContext(connectionId, storeId, ShopifyConnector.PLATFORM, creds);
    }

    // ── Req 1.1.1: orders pull + access-token header ───────────────────────────

    @Test
    void pullOrdersRetrievesRecordsAndSendsAccessTokenHeader() {
        server.route("/admin/api/2024-01/orders.json", query -> MockApiServer.Response.json(200, """
                {
                  "orders": [
                    {"id": 555, "financial_status": "paid", "cancelled_at": null,
                     "updated_at": "2024-03-01T10:00:00-05:00"},
                    {"id": 556, "financial_status": "pending", "cancelled_at": null,
                     "updated_at": "2024-03-02T12:00:00-05:00"}
                  ]
                }
                """));

        ExternalPage page = connector.pullOrders(context(), null, PageCursor.start());

        // Req 1.1.1: orders were retrieved and normalized.
        assertThat(page.records()).hasSize(2);
        ExternalRecord first = page.records().get(0);
        assertThat(first.externalId()).isEqualTo("555");
        assertThat(first.entityType()).isEqualTo("order");
        assertThat(first.status()).isEqualTo("paid");
        // No "next" Link header → final page.
        assertThat(page.hasMore()).isFalse();

        // The stored admin access token is sent on the shop's own endpoint.
        MockApiServer.RecordedRequest req = server.lastRequestTo("/admin/api/2024-01/orders.json");
        assertThat(req).isNotNull();
        assertThat(req.header("x-shopify-access-token")).isEqualTo(ACCESS_TOKEN);
    }

    // ── Req 1.1.1: a cancelled order is reported as cancelled ──────────────────

    @Test
    void pullOrdersDerivesCancelledStatusFromCancelledAt() {
        server.route("/admin/api/2024-01/orders.json", query -> MockApiServer.Response.json(200, """
                {
                  "orders": [
                    {"id": 700, "financial_status": "refunded",
                     "cancelled_at": "2024-03-03T08:00:00-05:00",
                     "updated_at": "2024-03-03T08:00:00-05:00"}
                  ]
                }
                """));

        ExternalPage page = connector.pullOrders(context(), null, PageCursor.start());

        assertThat(page.records()).hasSize(1);
        assertThat(page.records().get(0).status()).isEqualTo("cancelled");
    }

    // ── Req 1.1.1: products pull ───────────────────────────────────────────────

    @Test
    void pullProductsRetrievesRecords() {
        server.route("/admin/api/2024-01/products.json", query -> MockApiServer.Response.json(200, """
                {
                  "products": [
                    {"id": 999, "status": "active", "title": "Gadget",
                     "updated_at": "2024-03-01T09:00:00-05:00"}
                  ]
                }
                """));

        ExternalPage page = connector.pullProducts(context(), null, PageCursor.start());

        assertThat(page.records()).hasSize(1);
        ExternalRecord product = page.records().get(0);
        assertThat(product.externalId()).isEqualTo("999");
        assertThat(product.entityType()).isEqualTo("product");
        assertThat(product.status()).isEqualTo("active");
        assertThat(page.hasMore()).isFalse();

        MockApiServer.RecordedRequest req = server.lastRequestTo("/admin/api/2024-01/products.json");
        assertThat(req.header("x-shopify-access-token")).isEqualTo(ACCESS_TOKEN);
    }

    // ── Req 1.1.1: cursor paging follows the rel="next" Link header ────────────

    @Test
    void pullOrdersFollowsCursorPagingAcrossPages() {
        // First page advertises a next page_info via the Link header; the
        // continuation request (carrying page_info) returns the final page.
        server.route("/admin/api/2024-01/orders.json", query -> {
            boolean continuation = query != null && query.contains("page_info=");
            if (!continuation) {
                String nextLink = "<https://test-shop.myshopify.com/admin/api/2024-01/orders.json"
                        + "?limit=250&page_info=NEXTPAGE>; rel=\"next\"";
                return new MockApiServer.Response(200,
                        "{\"orders\":[{\"id\":1,\"financial_status\":\"paid\","
                                + "\"updated_at\":\"2024-03-01T10:00:00-05:00\"}]}",
                        Map.of("Link", nextLink));
            }
            return MockApiServer.Response.json(200,
                    "{\"orders\":[{\"id\":2,\"financial_status\":\"paid\","
                            + "\"updated_at\":\"2024-03-02T10:00:00-05:00\"}]}");
        });

        ExternalPage first = connector.pullOrders(context(), null, PageCursor.start());
        assertThat(first.records()).extracting(ExternalRecord::externalId).containsExactly("1");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.next()).isNotNull();
        assertThat(first.next().token()).isEqualTo("NEXTPAGE");

        ExternalPage second = connector.pullOrders(context(), null, first.next());
        assertThat(second.records()).extracting(ExternalRecord::externalId).containsExactly("2");
        assertThat(second.hasMore()).isFalse();
    }

    // ── Req 1.1.1: a rejected token surfaces as an error from the pull ─────────

    @Test
    void pullPropagatesPlatformErrorOnUnauthorized() {
        server.route("/admin/api/2024-01/orders.json", query -> MockApiServer.Response.json(401,
                "{\"errors\":\"[API] Invalid API key or access token\"}"));

        ConnectionContext ctx = context();
        assertThatThrownBy(() -> connector.pullOrders(ctx, null, PageCursor.start()))
                .isInstanceOf(RuntimeException.class);
    }
}
