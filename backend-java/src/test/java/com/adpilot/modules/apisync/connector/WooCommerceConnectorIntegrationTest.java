package com.adpilot.modules.apisync.connector;

import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.PageCursor;
import com.adpilot.modules.apisync.support.MockApiServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link WooCommerceConnector} against a real local HTTP
 * server ({@link MockApiServer}). WooCommerce builds its request URL from the
 * configured {@code siteUrl}, so the connector is pointed at the mock server's
 * {@code http://localhost:port} host and its full {@code RestClient} stack —
 * URL building, HTTP Basic auth, JSON parsing, and paging — runs end to end
 * over a real socket.
 *
 * <ul>
 *   <li>Req 1.1.1 — orders and products are retrieved using the connection's
 *       stored credentials (sent as HTTP Basic auth).</li>
 * </ul>
 */
class WooCommerceConnectorIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID connectionId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();

    private MockApiServer server;
    private WooCommerceConnector connector;

    @BeforeEach
    void setUp() throws IOException {
        server = MockApiServer.http();
        connector = new WooCommerceConnector(mapper, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    /** Build a context whose {@code siteUrl} points at the plain-HTTP mock server. */
    private ConnectionContext context() {
        Map<String, String> creds = new LinkedHashMap<>();
        creds.put("siteUrl", "http://" + server.host());
        creds.put("consumerKey", "ck_test");
        creds.put("consumerSecret", "cs_test");
        return new ConnectionContext(connectionId, storeId, WooCommerceConnector.PLATFORM, creds);
    }

    private static String expectedBasicAuth() {
        String creds = "ck_test:cs_test";
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    // ── Req 1.1.1: orders pull + Basic auth ────────────────────────────────────

    @Test
    void pullOrdersRetrievesRecordsAndSendsBasicAuth() {
        server.route("/wp-json/wc/v3/orders", query -> MockApiServer.Response.json(200, """
                [
                  {"id": 1001, "status": "completed",
                   "date_modified_gmt": "2024-03-01T10:00:00", "total": "49.99"},
                  {"id": 1002, "status": "processing",
                   "date_modified_gmt": "2024-03-02T11:30:00", "total": "19.99"}
                ]
                """));

        ExternalPage page = connector.pullOrders(context(), null, PageCursor.start());

        // Req 1.1.1: orders were retrieved and normalized.
        assertThat(page.records()).hasSize(2);
        ExternalRecord first = page.records().get(0);
        assertThat(first.externalId()).isEqualTo("1001");
        assertThat(first.entityType()).isEqualTo("order");
        assertThat(first.status()).isEqualTo("completed");
        assertThat(first.changedAt()).isEqualTo(Instant.parse("2024-03-01T10:00:00Z"));
        assertThat(first.fields()).containsEntry("total", "49.99");
        // A short page (< per_page) is the final page.
        assertThat(page.hasMore()).isFalse();

        // Stored credentials are sent as HTTP Basic auth to the store endpoint.
        MockApiServer.RecordedRequest req = server.lastRequestTo("/wp-json/wc/v3/orders");
        assertThat(req).isNotNull();
        assertThat(req.header("authorization")).isEqualTo(expectedBasicAuth());
    }

    // ── Req 1.1.1: products pull ───────────────────────────────────────────────

    @Test
    void pullProductsRetrievesRecords() {
        server.route("/wp-json/wc/v3/products", query -> MockApiServer.Response.json(200, """
                [
                  {"id": 2001, "status": "publish", "name": "Widget", "sku": "W-1",
                   "date_modified_gmt": "2024-03-01T09:00:00"}
                ]
                """));

        ExternalPage page = connector.pullProducts(context(), null, PageCursor.start());

        assertThat(page.records()).hasSize(1);
        ExternalRecord product = page.records().get(0);
        assertThat(product.externalId()).isEqualTo("2001");
        assertThat(product.entityType()).isEqualTo("product");
        assertThat(product.status()).isEqualTo("publish");
        assertThat(product.fields()).containsEntry("sku", "W-1");
        assertThat(page.hasMore()).isFalse();

        MockApiServer.RecordedRequest req = server.lastRequestTo("/wp-json/wc/v3/products");
        assertThat(req.header("authorization")).isEqualTo(expectedBasicAuth());
    }

    // ── Req 1.1.1: incremental pull adds the modified_after window ─────────────

    @Test
    void incrementalPullSendsModifiedAfterQueryParam() {
        server.route("/wp-json/wc/v3/orders", query -> MockApiServer.Response.json(200, "[]"));

        Instant since = Instant.parse("2024-02-01T00:00:00Z");
        ExternalPage page = connector.pullOrders(context(), since, PageCursor.start());

        assertThat(page.records()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        // The incremental window is expressed via modified_after (+ dates_are_gmt).
        String requestLine = server.requests().get(server.requests().size() - 1);
        assertThat(requestLine).contains("modified_after=2024-02-01T00:00:00Z");
        assertThat(requestLine).contains("dates_are_gmt=true");
    }

    // ── Req 1.1.1: a rejected credential surfaces as an error from the pull ────

    @Test
    void pullPropagatesPlatformErrorOnUnauthorized() {
        server.route("/wp-json/wc/v3/orders", query -> MockApiServer.Response.json(401,
                "{\"code\":\"woocommerce_rest_authentication_error\",\"message\":\"Invalid signature\"}"));

        ConnectionContext ctx = context();
        assertThatThrownBy(() -> connector.pullOrders(ctx, null, PageCursor.start()))
                .isInstanceOf(RuntimeException.class);
    }
}
