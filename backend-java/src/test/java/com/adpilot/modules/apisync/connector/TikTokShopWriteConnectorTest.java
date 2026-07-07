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
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Offline unit tests for {@link TikTokShopWriteConnector} covering change-type
 * routing (product / inventory / fulfillment), external entity mapping
 * resolution, endpoint and request-body building, request signing, the TikTok
 * HTTP-200 business-code honesty contract, and HTTP error-status mapping. HTTP is
 * never performed: it is either short-circuited before the call, exercised
 * through pure helper methods, or routed through a single-call subclass hook.
 * Mirrors the {@link ShopifyWriteConnectorTest} / {@link GoogleAdsWriteConnectorTest}
 * style and NEVER touches the network.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TikTokShopWriteConnector")
class TikTokShopWriteConnectorTest {

    @Mock
    private ExternalEntityMappingMapper externalEntityMappingMapper;

    @Mock
    private PlatformLogSanitizer logSanitizer;

    private TikTokShopWriteConnector connector;
    private ObjectMapper objectMapper;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final String EXTERNAL_ID = "1729592969712207000";
    private static final String SHOP_CIPHER = "TTP_xxxxcipher";
    private static final String WAREHOUSE_ID = "7068517275539719942";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        connector = new TikTokShopWriteConnector(externalEntityMappingMapper, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));
    }

    private ConnectionContext buildContext() {
        Map<String, String> creds = new HashMap<>();
        creds.put("appKey", "test-app-key");
        creds.put("appSecret", "test-app-secret");
        creds.put("accessToken", "tts-access-token-secret");
        creds.put("shopCipher", SHOP_CIPHER);
        creds.put("shopId", "7493");
        creds.put("warehouseId", WAREHOUSE_ID);
        return new ConnectionContext(CONNECTION_ID, STORE_ID, "tiktok_shop", creds);
    }

    private PlatformChange buildChange(String changeType, String subjectType,
                                       String subjectId, String recommendedValue) {
        return new PlatformChange("tiktok_shop", STORE_ID, changeType, subjectType,
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
    @DisplayName("platform() returns 'tiktok_shop'")
    void platformKey() {
        assertThat(connector.platform()).isEqualTo("tiktok_shop");
    }

    // =========================================================================
    // changeType routing
    // =========================================================================
    @Nested
    @DisplayName("Change-type routing")
    class ChangeTypeRouting {

        @Test
        @DisplayName("product/inventory/fulfillment are supported; unknown is not")
        void supportedTypes() {
            assertThat(connector.isSupportedChangeType("product")).isTrue();
            assertThat(connector.isSupportedChangeType("inventory")).isTrue();
            assertThat(connector.isSupportedChangeType("fulfillment")).isTrue();
            assertThat(connector.isSupportedChangeType("bid")).isFalse();
            assertThat(connector.isSupportedChangeType(null)).isFalse();
        }

        @Test
        @DisplayName("product create routes to /product/202309/products")
        void productEndpoint() {
            assertThat(connector.buildProductCreatePath())
                    .isEqualTo("/product/202309/products");
        }

        @Test
        @DisplayName("inventory routes to /product/202309/products/{id}/inventory/update")
        void inventoryEndpoint() {
            assertThat(connector.buildInventoryPath(EXTERNAL_ID))
                    .isEqualTo("/product/202309/products/" + EXTERNAL_ID + "/inventory/update");
        }

        @Test
        @DisplayName("fulfillment routes to /fulfillment/202309/orders/{id}/packages")
        void fulfillmentEndpoint() {
            assertThat(connector.buildFulfillmentPath(EXTERNAL_ID))
                    .isEqualTo("/fulfillment/202309/orders/" + EXTERNAL_ID + "/packages");
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
                    .isEqualTo(TikTokShopWriteConnector.ERROR_UNSUPPORTED_CHANGE_TYPE);
        }
    }

    // =========================================================================
    // External entity mapping resolution
    // =========================================================================
    @Nested
    @DisplayName("External mapping resolution")
    class MappingResolution {

        @Test
        @DisplayName("inventory with missing mapping returns permanentReject NO_EXTERNAL_MAPPING")
        void missingMapping() {
            when(externalEntityMappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            PlatformChange change = buildChange("inventory", "product",
                    UUID.randomUUID().toString(), "10");

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.accepted()).isFalse();
            assertThat(result.platformErrorCode())
                    .isEqualTo(TikTokShopWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
        }

        @Test
        @DisplayName("fulfillment with null subjectId returns permanentReject NO_EXTERNAL_MAPPING")
        void nullSubject() {
            PlatformChange change = buildChange("fulfillment", "order", null, "TRACK1");

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.platformErrorCode())
                    .isEqualTo(TikTokShopWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
        }

        @Test
        @DisplayName("product create does NOT require an existing external mapping")
        void productCreateNoMappingNeeded() {
            // No mapping stub: a product create with no external id should still proceed to the
            // (mocked) HTTP call rather than being rejected as NO_EXTERNAL_MAPPING.
            mockSanitizer();
            int[] calls = {0};
            TikTokShopWriteConnector spy = new TikTokShopWriteConnector(
                    externalEntityMappingMapper, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
                @Override
                protected String doExecuteHttp(String method, String url, String accessToken,
                                               ConnectionContext ctx, String requestBody) {
                    calls[0]++;
                    return "{\"code\":0,\"message\":\"Success\",\"data\":{\"product_id\":\"999\"}}";
                }
            };
            PlatformChange change = buildChange("product", "product", null, "{\"title\":\"New\"}");

            PlatformWriteResult result = spy.submit(buildContext(), change);

            assertThat(calls[0]).isEqualTo(1);
            assertThat(result.accepted()).isTrue();
            assertThat(result.platformReference()).isEqualTo("999");
        }
    }

    // =========================================================================
    // Credential pre-checks — honesty: no HTTP when credentials are missing
    // =========================================================================
    @Nested
    @DisplayName("Credential pre-checks (no HTTP, no fake success)")
    class CredentialChecks {

        @Test
        @DisplayName("missing accessToken returns MISSING_CREDENTIALS and performs NO HTTP")
        void missingCredentials() {
            mockMappingFound();
            int[] calls = {0};
            TikTokShopWriteConnector spy = new TikTokShopWriteConnector(
                    externalEntityMappingMapper, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
                @Override
                protected String doExecuteHttp(String method, String url, String accessToken,
                                               ConnectionContext ctx, String requestBody) {
                    calls[0]++;
                    return "{\"code\":0}";
                }
            };
            Map<String, String> creds = new HashMap<>();
            creds.put("appKey", "k");
            creds.put("appSecret", "s");
            creds.put("shopCipher", SHOP_CIPHER);
            ConnectionContext ctx = new ConnectionContext(CONNECTION_ID, STORE_ID, "tiktok_shop", creds);
            PlatformChange change = buildChange("inventory", "product",
                    UUID.randomUUID().toString(), "10");

            PlatformWriteResult result = spy.submit(ctx, change);

            assertThat(calls[0]).isZero();
            assertThat(result.accepted()).isFalse();
            assertThat(result.platformErrorCode())
                    .isEqualTo(TikTokShopWriteConnector.ERROR_MISSING_CREDENTIALS);
        }

        @Test
        @DisplayName("missing shop identifier (no cipher/id) returns MISSING_SHOP and performs NO HTTP")
        void missingShop() {
            mockMappingFound();
            int[] calls = {0};
            TikTokShopWriteConnector spy = new TikTokShopWriteConnector(
                    externalEntityMappingMapper, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
                @Override
                protected String doExecuteHttp(String method, String url, String accessToken,
                                               ConnectionContext ctx, String requestBody) {
                    calls[0]++;
                    return "{\"code\":0}";
                }
            };
            Map<String, String> creds = new HashMap<>();
            creds.put("appKey", "k");
            creds.put("appSecret", "s");
            creds.put("accessToken", "t");
            ConnectionContext ctx = new ConnectionContext(CONNECTION_ID, STORE_ID, "tiktok_shop", creds);
            PlatformChange change = buildChange("inventory", "product",
                    UUID.randomUUID().toString(), "10");

            PlatformWriteResult result = spy.submit(ctx, change);

            assertThat(calls[0]).isZero();
            assertThat(result.platformErrorCode())
                    .isEqualTo(TikTokShopWriteConnector.ERROR_MISSING_SHOP);
        }
    }

    // =========================================================================
    // Request-body building
    // =========================================================================
    @Nested
    @DisplayName("Request-body building")
    class RequestBody {

        @Test
        @DisplayName("inventory body sets sku id, warehouse_id and quantity")
        void inventoryBody() throws Exception {
            String body = connector.buildInventoryBody(EXTERNAL_ID, WAREHOUSE_ID, "42");
            JsonNode sku = objectMapper.readTree(body).path("skus").get(0);
            assertThat(sku.path("id").asText()).isEqualTo(EXTERNAL_ID);
            JsonNode level = sku.path("inventory").get(0);
            assertThat(level.path("warehouse_id").asText()).isEqualTo(WAREHOUSE_ID);
            assertThat(level.path("quantity").asInt()).isEqualTo(42);
        }

        @Test
        @DisplayName("inventory body omits warehouse_id when none configured and tolerates decimals")
        void inventoryBodyNoWarehouse() throws Exception {
            String body = connector.buildInventoryBody(EXTERNAL_ID, null, "10.0");
            JsonNode level = objectMapper.readTree(body).path("skus").get(0).path("inventory").get(0);
            assertThat(level.has("warehouse_id")).isFalse();
            assertThat(level.path("quantity").asInt()).isEqualTo(10);
        }

        @Test
        @DisplayName("product body wraps a plain value as a title")
        void productBodyTitle() throws Exception {
            String body = connector.buildProductBody("My Product");
            assertThat(objectMapper.readTree(body).path("title").asText()).isEqualTo("My Product");
        }

        @Test
        @DisplayName("product body forwards a JSON-object payload verbatim")
        void productBodyJson() throws Exception {
            String body = connector.buildProductBody("{\"title\":\"P\",\"category_id\":\"123\"}");
            JsonNode root = objectMapper.readTree(body);
            assertThat(root.path("title").asText()).isEqualTo("P");
            assertThat(root.path("category_id").asText()).isEqualTo("123");
        }

        @Test
        @DisplayName("fulfillment body sets tracking_number and optional shipping_provider_id")
        void fulfillmentBody() throws Exception {
            String body = connector.buildFulfillmentBody("1Z999AA10123456784", "7208502187");
            JsonNode root = objectMapper.readTree(body);
            assertThat(root.path("tracking_number").asText()).isEqualTo("1Z999AA10123456784");
            assertThat(root.path("shipping_provider_id").asText()).isEqualTo("7208502187");
        }

        @Test
        @DisplayName("fulfillment with blank tracking number is rejected as an invalid request (no fake success)")
        void fulfillmentMissingTracking() {
            mockMappingFound();
            PlatformChange change = buildChange("fulfillment", "order",
                    UUID.randomUUID().toString(), null);

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.accepted()).isFalse();
            assertThat(result.platformErrorCode())
                    .isEqualTo(TikTokShopWriteConnector.ERROR_INVALID_REQUEST);
        }
    }

    // =========================================================================
    // Signing
    // =========================================================================
    @Nested
    @DisplayName("Request signing")
    class Signing {

        @Test
        @DisplayName("sign is a stable lowercase hex digest and excludes sign/access_token params")
        void signStable() {
            TreeMap<String, String> params = new TreeMap<>();
            params.put("app_key", "test-app-key");
            params.put("timestamp", "1700000000");
            params.put("shop_cipher", SHOP_CIPHER);
            String body = "{\"a\":1}";
            String first = connector.sign("test-app-secret", "/product/202309/products", params, body);
            String second = connector.sign("test-app-secret", "/product/202309/products", params, body);

            assertThat(first).isEqualTo(second);
            assertThat(first).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("signed URL carries app_key, timestamp, shop params and sign")
        void signedUrlShape() {
            String url = connector.buildSignedUrl("test-app-key", "test-app-secret",
                    "/product/202309/products", SHOP_CIPHER, "7493", "{\"a\":1}");
            assertThat(url).startsWith(TikTokShopWriteConnector.BASE + "/product/202309/products?");
            assertThat(url).contains("app_key=test-app-key");
            assertThat(url).contains("shop_cipher=" + SHOP_CIPHER);
            assertThat(url).contains("shop_id=7493");
            assertThat(url).contains("&sign=");
        }
    }

    // =========================================================================
    // Single-call submit via subclass hook
    // =========================================================================
    @Test
    @DisplayName("successful inventory submit (code:0) returns accepted with exactly one HTTP call")
    void successfulInventorySubmit() {
        mockMappingFound();
        mockSanitizer();

        int[] calls = {0};
        String[] capturedUrl = {null};
        String[] capturedBody = {null};
        TikTokShopWriteConnector spy = new TikTokShopWriteConnector(
                externalEntityMappingMapper, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(String method, String url, String accessToken,
                                           ConnectionContext ctx, String requestBody) {
                calls[0]++;
                capturedUrl[0] = url;
                capturedBody[0] = requestBody;
                return "{\"code\":0,\"message\":\"Success\",\"data\":{}}";
            }
        };

        PlatformChange change = buildChange("inventory", "product",
                UUID.randomUUID().toString(), "42");
        PlatformWriteResult result = spy.submit(buildContext(), change);

        assertThat(calls[0]).isEqualTo(1);
        assertThat(result.accepted()).isTrue();
        assertThat(capturedUrl[0])
                .startsWith(TikTokShopWriteConnector.BASE
                        + "/product/202309/products/" + EXTERNAL_ID + "/inventory/update?");
        assertThat(capturedBody[0]).contains("\"quantity\":42");
    }

    @Test
    @DisplayName("successful fulfillment submit returns accepted referencing the package id")
    void successfulFulfillmentSubmit() {
        mockMappingFound();
        mockSanitizer();

        TikTokShopWriteConnector spy = new TikTokShopWriteConnector(
                externalEntityMappingMapper, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(String method, String url, String accessToken,
                                           ConnectionContext ctx, String requestBody) {
                return "{\"code\":0,\"message\":\"Success\",\"data\":{\"package_id\":\"577\"}}";
            }
        };

        PlatformChange change = buildChange("fulfillment", "order",
                UUID.randomUUID().toString(), "TRACK123");
        PlatformWriteResult result = spy.submit(buildContext(), change);

        assertThat(result.accepted()).isTrue();
        assertThat(result.platformReference()).isEqualTo("577");
    }

    @Test
    @DisplayName("HTTP 200 with non-zero business code is an HONEST failure, never fake success")
    void businessErrorIsFailure() {
        mockMappingFound();
        mockSanitizer();

        TikTokShopWriteConnector spy = new TikTokShopWriteConnector(
                externalEntityMappingMapper, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(String method, String url, String accessToken,
                                           ConnectionContext ctx, String requestBody) {
                return "{\"code\":12053001,\"message\":\"product not found\",\"data\":{}}";
            }
        };

        PlatformChange change = buildChange("inventory", "product",
                UUID.randomUUID().toString(), "5");
        PlatformWriteResult result = spy.submit(buildContext(), change);

        assertThat(result.accepted()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.platformErrorCode()).isEqualTo("TIKTOK_12053001");
        assertThat(result.message()).isEqualTo("product not found");
    }

    @Test
    @DisplayName("network/transport exception → retryable, never fake success")
    void networkExceptionRetryable() {
        mockMappingFound();
        mockSanitizer();

        TikTokShopWriteConnector spy = new TikTokShopWriteConnector(
                externalEntityMappingMapper, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(String method, String url, String accessToken,
                                           ConnectionContext ctx, String requestBody) {
                throw new org.springframework.web.client.ResourceAccessException("connection refused");
            }
        };

        PlatformChange change = buildChange("inventory", "product",
                UUID.randomUUID().toString(), "5");
        PlatformWriteResult result = spy.submit(buildContext(), change);

        assertThat(result.accepted()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.retryAfterSeconds()).isPositive();
    }

    // =========================================================================
    // HTTP error response classification
    // =========================================================================
    @Nested
    @DisplayName("Response classification (429/5xx/401/4xx)")
    class ResponseClassification {

        @Test
        @DisplayName("429 → retryable")
        void rateLimited() {
            PlatformWriteResult r = connector.mapErrorResponse(429, "{}");
            assertThat(r.accepted()).isFalse();
            assertThat(r.retryable()).isTrue();
            assertThat(r.retryAfterSeconds()).isPositive();
        }

        @Test
        @DisplayName("5xx → retryable")
        void serverErrors() {
            assertThat(connector.mapErrorResponse(500, "{}").retryable()).isTrue();
            assertThat(connector.mapErrorResponse(503, "{}").retryable()).isTrue();
        }

        @Test
        @DisplayName("401/403 → permanentReject TOKEN_INVALID")
        void unauthorized() {
            assertThat(connector.mapErrorResponse(401, "{}").platformErrorCode())
                    .isEqualTo(TikTokShopWriteConnector.ERROR_TOKEN_INVALID);
            assertThat(connector.mapErrorResponse(403, "{}").platformErrorCode())
                    .isEqualTo(TikTokShopWriteConnector.ERROR_TOKEN_INVALID);
        }

        @Test
        @DisplayName("4xx → permanentReject carrying the platform reason")
        void clientError() {
            String body = "{\"code\":36004003,\"message\":\"invalid sku id\"}";
            PlatformWriteResult r = connector.mapErrorResponse(400, body);
            assertThat(r.accepted()).isFalse();
            assertThat(r.retryable()).isFalse();
            assertThat(r.platformErrorCode()).isEqualTo("TIKTOK_36004003");
            assertThat(r.message()).isEqualTo("invalid sku id");
        }
    }

    // =========================================================================
    // Status mapping
    // =========================================================================
    @Nested
    @DisplayName("mapPlatformStatus")
    class StatusMapping {

        @Test
        @DisplayName("active/shipped statuses map to EFFECTIVE")
        void effective() {
            assertThat(connector.mapPlatformStatus("success")).isEqualTo(SyncState.EFFECTIVE);
            assertThat(connector.mapPlatformStatus("shipped")).isEqualTo(SyncState.EFFECTIVE);
        }

        @Test
        @DisplayName("failure statuses map to FAILED")
        void failed() {
            assertThat(connector.mapPlatformStatus("rejected")).isEqualTo(SyncState.FAILED);
            assertThat(connector.mapPlatformStatus("cancelled")).isEqualTo(SyncState.FAILED);
        }

        @Test
        @DisplayName("null/unknown status maps to RECONCILIATION_REQUIRED")
        void unknown() {
            assertThat(connector.mapPlatformStatus(null)).isEqualTo(SyncState.RECONCILIATION_REQUIRED);
            assertThat(connector.mapPlatformStatus("wat")).isEqualTo(SyncState.RECONCILIATION_REQUIRED);
        }
    }
}
