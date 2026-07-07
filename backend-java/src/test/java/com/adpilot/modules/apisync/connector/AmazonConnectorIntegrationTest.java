package com.adpilot.modules.apisync.connector;

import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.PageCursor;
import com.adpilot.modules.advertising.hosting.ReportLifecycleClient;
import com.adpilot.modules.advertising.hosting.ReportLifecycleResult;
import com.adpilot.modules.advertising.hosting.ReportType;
import com.adpilot.modules.apisync.support.MockApiServer;
import com.adpilot.modules.apisync.support.MockHttpRedirect;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the Amazon connectors against a real local HTTP server
 * ({@link MockApiServer}). A connector's outbound calls are redirected to the
 * mock server, so the full {@code RestClient} stack — URL building, the LWA
 * token exchange, request signing, response parsing, and re-auth detection —
 * runs end to end over a real socket.
 *
 * <ul>
 *   <li>Req 8.1.1 — orders, inventory, and advertising reports are retrieved.</li>
 *   <li>Req 8.1.2 — the outbound request carries the platform's required signing
 *       scheme (SigV4 for SP-API, Bearer + client-id/scope for Ads).</li>
 *   <li>Req 8.1.5 — an expired/invalid token (LWA {@code invalid_grant} or a
 *       data-endpoint 401) raises {@link ReauthRequiredException}.</li>
 * </ul>
 */
class AmazonConnectorIntegrationTest {

    private static final String LWA_TOKEN_PATH = "/auth/o2/token";
    private static final String ACCESS_TOKEN = "ACCESS-TOKEN-XYZ";

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID connectionId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();

    private MockApiServer server;
    private AmazonLwaClient lwaClient;
    private AmazonSpApiConnector spApi;
    private AmazonAdsConnector ads;
    private ReportLifecycleClient reportLifecycleClient;

    @BeforeEach
    void setUp() throws IOException {
        server = MockApiServer.http();
        // The LWA token endpoint is shared by both connectors; default it to a
        // valid token so individual tests only override the data endpoints.
        server.route(LWA_TOKEN_PATH, query -> MockApiServer.Response.json(200,
                "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"token_type\":\"bearer\",\"expires_in\":3600}"));

        lwaClient = new AmazonLwaClient(mapper, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));
        spApi = new AmazonSpApiConnector(mapper, lwaClient, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));
        reportLifecycleClient = mock(ReportLifecycleClient.class);
        ads = new AmazonAdsConnector(mapper, lwaClient, reportLifecycleClient, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));

        // Redirect the connectors' data calls and the shared LWA token call to
        // the mock server (Amazon hosts are hard-coded, so we rewrite the host).
        MockHttpRedirect.redirect(lwaClient, server.host());
        MockHttpRedirect.redirect(spApi, server.host());
        MockHttpRedirect.redirect(ads, server.host());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private ConnectionContext spApiContext() {
        Map<String, String> creds = new LinkedHashMap<>();
        creds.put("refreshToken", "refresh-abc");
        creds.put("clientId", "amzn1.application.client");
        creds.put("clientSecret", "client-secret");
        creds.put("marketplaceId", "ATVPDKIKX0DER");
        creds.put("region", "na");
        // IAM credentials trigger SigV4 signing of the SP-API request (Req 8.1.2).
        creds.put("awsAccessKeyId", "AKIAEXAMPLE");
        creds.put("awsSecretKey", "secret-key-example");
        return new ConnectionContext(connectionId, storeId, AmazonSpApiConnector.PLATFORM, creds);
    }

    private ConnectionContext adsContext() {
        Map<String, String> creds = new LinkedHashMap<>();
        creds.put("refreshToken", "refresh-abc");
        creds.put("clientId", "amzn1.application.client");
        creds.put("clientSecret", "client-secret");
        creds.put("profileId", "profile-123");
        creds.put("region", "na");
        return new ConnectionContext(connectionId, storeId, AmazonAdsConnector.PLATFORM, creds);
    }

    // ── Req 8.1.1 / 8.1.2: SP-API orders pull + SigV4 signing ──────────────────

    @Test
    void spApiPullOrdersRetrievesRecordsAndSignsRequestWithSigV4() {
        server.route("/orders/v0/orders", query -> MockApiServer.Response.json(200, """
                {
                  "payload": {
                    "Orders": [
                      {"AmazonOrderId": "111-2223334-4445556",
                       "OrderStatus": "Shipped",
                       "PurchaseDate": "2024-03-01T10:15:30Z",
                       "LastUpdateDate": "2024-03-02T08:00:00Z"}
                    ]
                  }
                }
                """));

        ExternalPage page = spApi.pullOrders(spApiContext(), null, PageCursor.start());

        // Req 8.1.1: the order was retrieved and normalized.
        assertThat(page.records()).hasSize(1);
        ExternalRecord order = page.records().get(0);
        assertThat(order.externalId()).isEqualTo("111-2223334-4445556");
        assertThat(order.entityType()).isEqualTo("order");
        assertThat(order.status()).isEqualTo("Shipped");
        assertThat(page.hasMore()).isFalse();

        // Req 8.1.2: the outbound request carried the LWA token and SigV4 headers.
        MockApiServer.RecordedRequest req = server.lastRequestTo("/orders/v0/orders");
        assertThat(req).isNotNull();
        assertThat(req.header("x-amz-access-token")).isEqualTo(ACCESS_TOKEN);
        assertThat(req.header("authorization"))
                .as("SP-API requests are signed with AWS Signature Version 4")
                .startsWith("AWS4-HMAC-SHA256")
                .contains("Credential=AKIAEXAMPLE/")
                .contains("SignedHeaders=")
                .contains("Signature=");
        assertThat(req.header("x-amz-date")).isNotBlank();
        assertThat(req.header("x-amz-content-sha256")).isNotBlank();
    }

    // ── Req 8.1.1: SP-API inventory pull ───────────────────────────────────────

    @Test
    void spApiPullInventoryRetrievesRecords() {
        server.route("/fba/inventory/v1/summaries", query -> MockApiServer.Response.json(200, """
                {
                  "payload": {
                    "inventorySummaries": [
                      {"sellerSku": "SKU-1", "asin": "B00TEST001",
                       "condition": "NewItem", "lastUpdatedTime": "2024-03-03T00:00:00Z"}
                    ],
                    "pagination": {"nextToken": ""}
                  }
                }
                """));

        ExternalPage page = spApi.pullInventory(spApiContext(), null, PageCursor.start());

        assertThat(page.records()).hasSize(1);
        ExternalRecord inv = page.records().get(0);
        assertThat(inv.externalId()).isEqualTo("SKU-1");
        assertThat(inv.entityType()).isEqualTo("inventory");
        assertThat(page.hasMore()).isFalse();

        MockApiServer.RecordedRequest req = server.lastRequestTo("/fba/inventory/v1/summaries");
        assertThat(req.header("x-amz-access-token")).isEqualTo(ACCESS_TOKEN);
        assertThat(req.header("authorization")).startsWith("AWS4-HMAC-SHA256");
    }

    // ── Req 8.1.1 / 8.1.2: Ads report pull + Bearer/client-id/scope scheme ──────

    @Test
    void adsPullAdReportsUsesRealReportLifecycle() {
        LocalDate reportDate = LocalDate.now();
        when(reportLifecycleClient.executeLifecycle(eq(adsContext()), eq(ReportType.SP_CAMPAIGN), any()))
                .thenReturn(new ReportLifecycleResult(
                        "report-1", ReportType.SP_CAMPAIGN, reportDate, reportDate,
                        List.of(Map.of(
                                "campaignId", "C-100", "date", reportDate.toString(),
                                "impressions", 1000, "clicks", 50, "cost", 12.50,
                                "sales14d", 200.00, "purchases14d", 8)),
                        1));

        ConnectionContext context = adsContext();
        ExternalPage page = ads.pullAdReports(context, null, PageCursor.start());

        assertThat(page.records()).hasSize(1);
        ExternalRecord row = page.records().get(0);
        assertThat(row.entityType()).isEqualTo("ad_report");
        assertThat(row.fields().get(AmazonAdsConnector.F_EXTERNAL_CAMPAIGN_ID)).isEqualTo("C-100");
        verify(reportLifecycleClient).executeLifecycle(eq(context), eq(ReportType.SP_CAMPAIGN), any());
    }

    // ── Req 8.1.5: expired access token on the data endpoint → re-auth ──────────

    @Test
    void spApiExpiredAccessTokenOnDataEndpointRaisesReauth() {
        server.route("/orders/v0/orders", query -> MockApiServer.Response.json(401,
                "{\"errors\":[{\"code\":\"Unauthorized\",\"message\":\"Access token expired\"}]}"));

        ConnectionContext ctx = spApiContext();
        assertThatThrownBy(() -> spApi.pullOrders(ctx, null, PageCursor.start()))
                .isInstanceOf(ReauthRequiredException.class)
                .extracting("connectionId").isEqualTo(connectionId);
    }

    // ── Req 8.1.5: rejected LWA refresh token (invalid_grant) → re-auth ─────────

    @Test
    void lwaInvalidGrantRaisesReauthOnPull() {
        // The token endpoint rejects the refresh token before any data call.
        server.route(LWA_TOKEN_PATH, query -> MockApiServer.Response.json(400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"The refresh token is invalid.\"}"));

        ConnectionContext ctx = spApiContext();
        assertThatThrownBy(() -> spApi.pullOrders(ctx, null, PageCursor.start()))
                .isInstanceOf(ReauthRequiredException.class)
                .satisfies(e -> {
                    ReauthRequiredException reauth = (ReauthRequiredException) e;
                    assertThat(reauth.getConnectionId()).isEqualTo(connectionId);
                    assertThat(reauth.getReason()).contains("invalid_grant");
                });
    }

    @Test
    void adsExpiredAccessTokenOnReportEndpointRaisesReauth() {
        ConnectionContext ctx = adsContext();
        when(reportLifecycleClient.executeLifecycle(eq(ctx), eq(ReportType.SP_CAMPAIGN), any()))
                .thenThrow(new ReauthRequiredException(connectionId, "Amazon Ads rejected the access token"));

        assertThatThrownBy(() -> ads.pullAdReports(ctx, null, PageCursor.start()))
                .isInstanceOf(ReauthRequiredException.class)
                .extracting("connectionId").isEqualTo(connectionId);
    }
}
