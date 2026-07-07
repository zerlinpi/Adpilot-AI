package com.adpilot.modules.apisync.connector;

import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.apisync.entity.ExternalEntityMappingEntity;
import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformWriteResult;
import com.adpilot.modules.apisync.support.PlatformLogSanitizer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Offline unit tests for {@link WooCommerceWriteConnector} covering change-type
 * routing for the independent-site write path (changeType {@code "inventory"} /
 * {@code "fulfillment"}), external entity mapping resolution, endpoint and
 * request-body building, and HTTP error-status mapping. HTTP is never performed:
 * it is either short-circuited before the call, exercised through pure helper
 * methods, or routed through a single-call subclass hook. Mirrors the
 * {@link GoogleAdsWriteConnectorTest} style.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WooCommerceWriteConnector")
class WooCommerceWriteConnectorTest {

    @Mock
    private ExternalEntityMappingMapper externalEntityMappingMapper;

    @Mock
    private PlatformLogSanitizer logSanitizer;

    private WooCommerceWriteConnector connector;
    private ObjectMapper objectMapper;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final String SITE = "https://store.example.com";
    private static final String EXTERNAL_ID = "4321";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        connector = new WooCommerceWriteConnector(externalEntityMappingMapper, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));
    }

    private ConnectionContext buildContext() {
        Map<String, String> creds = new HashMap<>();
        creds.put("siteUrl", SITE + "/");
        creds.put("consumerKey", "ck_secret_key");
        creds.put("consumerSecret", "cs_secret_secret");
        return new ConnectionContext(CONNECTION_ID, STORE_ID, "woocommerce", creds);
    }

    private PlatformChange buildChange(String changeType, String subjectType,
                                       String subjectId, String recommendedValue) {
        return new PlatformChange("woocommerce", STORE_ID, changeType, subjectType,
                subjectId, null, recommendedValue, "MANUAL",
                UUID.randomUUID().toString(), null);
    }

    private void mockMappingFound() {
        ExternalEntityMappingEntity mapping = new ExternalEntityMappingEntity();
        mapping.setExternalEntityId(EXTERNAL_ID);
        when(externalEntityMappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mapping);
    }

    private void mockSanitizer() {
        lenient().when(logSanitizer.sanitize(anyString(), any(ConnectionContext.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // =========================================================================
    // Platform registration
    // =========================================================================
    @Test
    @DisplayName("platform() returns 'woocommerce'")
    void platformKey() {
        assertThat(connector.platform()).isEqualTo("woocommerce");
    }

    // =========================================================================
    // changeType routing
    // =========================================================================
    @Nested
    @DisplayName("Change-type routing")
    class ChangeTypeRouting {

        @Test
        @DisplayName("inventory/fulfillment are supported; unknown is not")
        void supportedTypes() {
            assertThat(connector.isSupportedChangeType("inventory")).isTrue();
            assertThat(connector.isSupportedChangeType("fulfillment")).isTrue();
            assertThat(connector.isSupportedChangeType("budget")).isFalse();
            assertThat(connector.isSupportedChangeType(null)).isFalse();
        }

        @Test
        @DisplayName("inventory routes to products/{id}")
        void productEndpoint() {
            assertThat(connector.buildProductEndpoint(EXTERNAL_ID))
                    .isEqualTo("/wp-json/wc/v3/products/4321");
        }

        @Test
        @DisplayName("fulfillment routes to orders/{id}")
        void orderEndpoint() {
            assertThat(connector.buildOrderEndpoint(EXTERNAL_ID))
                    .isEqualTo("/wp-json/wc/v3/orders/4321");
        }

        @Test
        @DisplayName("unsupported change type returns permanentReject without any HTTP work")
        void unsupportedTypeRejected() {
            PlatformChange change = buildChange("bid", "keyword",
                    UUID.randomUUID().toString(), "5.0");

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.accepted()).isFalse();
            assertThat(result.retryable()).isFalse();
            assertThat(result.platformErrorCode())
                    .isEqualTo(WooCommerceWriteConnector.ERROR_UNSUPPORTED_CHANGE_TYPE);
        }
    }

    // =========================================================================
    // External entity mapping resolution
    // =========================================================================
    @Nested
    @DisplayName("External mapping resolution")
    class MappingResolution {

        @Test
        @DisplayName("missing mapping returns permanentReject NO_EXTERNAL_MAPPING")
        void missingMapping() {
            when(externalEntityMappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            PlatformChange change = buildChange("inventory", "product",
                    UUID.randomUUID().toString(), "10");

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.accepted()).isFalse();
            assertThat(result.platformErrorCode())
                    .isEqualTo(WooCommerceWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
        }

        @Test
        @DisplayName("null subjectId returns permanentReject NO_EXTERNAL_MAPPING")
        void nullSubject() {
            PlatformChange change = buildChange("inventory", "product", null, "10");

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.platformErrorCode())
                    .isEqualTo(WooCommerceWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
        }
    }

    // =========================================================================
    // Credential pre-checks
    // =========================================================================
    @Test
    @DisplayName("blank siteUrl returns permanentReject MISSING_SITE_URL")
    void blankSiteUrl() {
        mockMappingFound();
        Map<String, String> creds = new HashMap<>();
        creds.put("consumerKey", "ck_secret_key");
        creds.put("consumerSecret", "cs_secret_secret");
        ConnectionContext ctx = new ConnectionContext(CONNECTION_ID, STORE_ID, "woocommerce", creds);
        PlatformChange change = buildChange("inventory", "product",
                UUID.randomUUID().toString(), "10");

        PlatformWriteResult result = connector.submit(ctx, change);

        assertThat(result.platformErrorCode())
                .isEqualTo(WooCommerceWriteConnector.ERROR_MISSING_SITE_URL);
    }

    // =========================================================================
    // Request-body building
    // =========================================================================
    @Nested
    @DisplayName("Request-body building")
    class RequestBody {

        @Test
        @DisplayName("inventory body sets manage_stock and stock_quantity")
        void inventoryBody() throws Exception {
            String body = connector.buildInventoryBody("42");
            JsonNode root = objectMapper.readTree(body);
            assertThat(root.path("manage_stock").asBoolean()).isTrue();
            assertThat(root.path("stock_quantity").asInt()).isEqualTo(42);
        }

        @Test
        @DisplayName("fulfillment body sets status=completed and stores tracking in meta_data")
        void fulfillmentBodyWithTracking() throws Exception {
            String body = connector.buildFulfillmentBody("1Z999AA10123456784");
            JsonNode root = objectMapper.readTree(body);
            assertThat(root.path("status").asText()).isEqualTo("completed");
            JsonNode meta = root.path("meta_data");
            assertThat(meta.isArray()).isTrue();
            assertThat(meta.get(0).path("key").asText()).isEqualTo("_tracking_number");
            assertThat(meta.get(0).path("value").asText()).isEqualTo("1Z999AA10123456784");
        }

        @Test
        @DisplayName("fulfillment body without tracking omits meta_data")
        void fulfillmentBodyWithoutTracking() throws Exception {
            String body = connector.buildFulfillmentBody(null);
            JsonNode root = objectMapper.readTree(body);
            assertThat(root.path("status").asText()).isEqualTo("completed");
            assertThat(root.has("meta_data")).isFalse();
        }
    }

    // =========================================================================
    // Single-call submit via subclass hook → 2xx accepted
    // =========================================================================
    @Test
    @DisplayName("successful inventory submit returns accepted with exactly one PUT call")
    void successfulInventorySubmit() {
        mockMappingFound();
        mockSanitizer();

        int[] calls = {0};
        String[] capturedUrl = {null};
        String[] capturedMethod = {null};
        String[] capturedBody = {null};
        WooCommerceWriteConnector spyConnector = new WooCommerceWriteConnector(
                externalEntityMappingMapper, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(String method, String url,
                                           ConnectionContext ctx, String requestBody) {
                calls[0]++;
                capturedMethod[0] = method;
                capturedUrl[0] = url;
                capturedBody[0] = requestBody;
                return "{\"id\":4321,\"stock_quantity\":42}";
            }
        };

        PlatformChange change = buildChange("inventory", "product",
                UUID.randomUUID().toString(), "42");
        PlatformWriteResult result = spyConnector.submit(buildContext(), change);

        assertThat(calls[0]).isEqualTo(1);
        assertThat(capturedMethod[0]).isEqualTo("PUT");
        assertThat(result.accepted()).isTrue();
        assertThat(result.platformReference()).isEqualTo("4321");
        assertThat(capturedUrl[0]).isEqualTo(SITE + "/wp-json/wc/v3/products/4321");
        assertThat(capturedBody[0]).contains("\"stock_quantity\":42");
    }

    @Test
    @DisplayName("successful fulfillment submit transitions order and returns accepted")
    void successfulFulfillmentSubmit() {
        mockMappingFound();
        mockSanitizer();

        String[] capturedUrl = {null};
        String[] capturedBody = {null};
        WooCommerceWriteConnector spyConnector = new WooCommerceWriteConnector(
                externalEntityMappingMapper, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(String method, String url,
                                           ConnectionContext ctx, String requestBody) {
                capturedUrl[0] = url;
                capturedBody[0] = requestBody;
                return "{\"id\":4321,\"status\":\"completed\"}";
            }
        };

        PlatformChange change = buildChange("fulfillment", "order",
                UUID.randomUUID().toString(), "TRACK999");
        PlatformWriteResult result = spyConnector.submit(buildContext(), change);

        assertThat(result.accepted()).isTrue();
        assertThat(capturedUrl[0]).isEqualTo(SITE + "/wp-json/wc/v3/orders/4321");
        assertThat(capturedBody[0]).contains("\"status\":\"completed\"");
        assertThat(capturedBody[0]).contains("TRACK999");
    }

    // =========================================================================
    // Response classification
    // =========================================================================
    @Nested
    @DisplayName("Response classification (429/5xx/401/4xx)")
    class ResponseClassification {

        @Test
        @DisplayName("429 → retryable")
        void rateLimited() {
            PlatformWriteResult r = connector.mapErrorResponse(429, "{}");
            assertThat(r.retryable()).isTrue();
            assertThat(r.retryAfterSeconds()).isPositive();
        }

        @Test
        @DisplayName("5xx → retryable")
        void serverErrors() {
            assertThat(connector.mapErrorResponse(500, "{}").retryable()).isTrue();
            assertThat(connector.mapErrorResponse(502, "{}").retryable()).isTrue();
        }

        @Test
        @DisplayName("401/403 → permanentReject TOKEN_INVALID")
        void unauthorized() {
            assertThat(connector.mapErrorResponse(401, "{}").platformErrorCode())
                    .isEqualTo(WooCommerceWriteConnector.ERROR_TOKEN_INVALID);
            assertThat(connector.mapErrorResponse(403, "{}").platformErrorCode())
                    .isEqualTo(WooCommerceWriteConnector.ERROR_TOKEN_INVALID);
        }

        @Test
        @DisplayName("4xx → permanentReject carrying the WooCommerce code and message")
        void clientError() {
            String body = "{\"code\":\"woocommerce_rest_product_invalid_id\","
                    + "\"message\":\"Invalid ID.\",\"data\":{\"status\":400}}";
            PlatformWriteResult r = connector.mapErrorResponse(400, body);
            assertThat(r.accepted()).isFalse();
            assertThat(r.retryable()).isFalse();
            assertThat(r.platformErrorCode()).isEqualTo("woocommerce_rest_product_invalid_id");
            assertThat(r.message()).isEqualTo("Invalid ID.");
        }
    }

    // =========================================================================
    // Helper / status
    // =========================================================================
    @Nested
    @DisplayName("Helpers and status mapping")
    class Helpers {

        @Test
        @DisplayName("normalizeSiteUrl strips trailing slashes and preserves scheme")
        void normalize() {
            assertThat(WooCommerceWriteConnector.normalizeSiteUrl("https://s.com/")).isEqualTo("https://s.com");
            assertThat(WooCommerceWriteConnector.normalizeSiteUrl("https://s.com///")).isEqualTo("https://s.com");
            assertThat(WooCommerceWriteConnector.normalizeSiteUrl(null)).isEmpty();
        }

        @Test
        @DisplayName("status mapping")
        void statusMapping() {
            assertThat(connector.mapPlatformStatus("completed")).isEqualTo(SyncState.EFFECTIVE);
            assertThat(connector.mapPlatformStatus("failed")).isEqualTo(SyncState.FAILED);
            assertThat(connector.mapPlatformStatus(null)).isEqualTo(SyncState.RECONCILIATION_REQUIRED);
            assertThat(connector.mapPlatformStatus("wat")).isEqualTo(SyncState.RECONCILIATION_REQUIRED);
        }
    }
}
