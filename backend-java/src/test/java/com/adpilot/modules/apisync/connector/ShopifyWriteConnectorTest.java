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
 * Offline unit tests for {@link ShopifyWriteConnector} covering change-type
 * routing for the independent-site write path (changeType {@code "inventory"} /
 * {@code "fulfillment"}), external entity mapping resolution, endpoint and
 * request-body building, and HTTP error-status mapping. HTTP is never performed:
 * it is either short-circuited before the call, exercised through pure helper
 * methods, or routed through a single-call subclass hook. Mirrors the
 * {@link GoogleAdsWriteConnectorTest} style.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShopifyWriteConnector")
class ShopifyWriteConnectorTest {

    @Mock
    private ExternalEntityMappingMapper externalEntityMappingMapper;

    @Mock
    private PlatformLogSanitizer logSanitizer;

    private ShopifyWriteConnector connector;
    private ObjectMapper objectMapper;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final String SHOP = "test-shop.myshopify.com";
    private static final String EXTERNAL_ID = "987654321";
    private static final String LOCATION_ID = "55555";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        connector = new ShopifyWriteConnector(externalEntityMappingMapper, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));
    }

    private ConnectionContext buildContext() {
        Map<String, String> creds = new HashMap<>();
        creds.put("shopDomain", "https://" + SHOP);
        creds.put("accessToken", "shpat_secret_token");
        creds.put("locationId", LOCATION_ID);
        return new ConnectionContext(CONNECTION_ID, STORE_ID, "shopify", creds);
    }

    private PlatformChange buildChange(String changeType, String subjectType,
                                       String subjectId, String recommendedValue) {
        return new PlatformChange("shopify", STORE_ID, changeType, subjectType,
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
    @DisplayName("platform() returns 'shopify'")
    void platformKey() {
        assertThat(connector.platform()).isEqualTo("shopify");
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
            assertThat(connector.isSupportedChangeType("bid")).isFalse();
            assertThat(connector.isSupportedChangeType(null)).isFalse();
        }

        @Test
        @DisplayName("inventory routes to inventory_levels/set.json")
        void inventoryEndpoint() {
            assertThat(connector.buildInventoryEndpoint())
                    .isEqualTo("/admin/api/2024-01/inventory_levels/set.json");
        }

        @Test
        @DisplayName("fulfillment routes to orders/{id}/fulfillments.json")
        void fulfillmentEndpoint() {
            assertThat(connector.buildFulfillmentEndpoint(EXTERNAL_ID))
                    .isEqualTo("/admin/api/2024-01/orders/987654321/fulfillments.json");
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
                    .isEqualTo(ShopifyWriteConnector.ERROR_UNSUPPORTED_CHANGE_TYPE);
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
                    .isEqualTo(ShopifyWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
        }

        @Test
        @DisplayName("null subjectId returns permanentReject NO_EXTERNAL_MAPPING")
        void nullSubject() {
            PlatformChange change = buildChange("inventory", "product", null, "10");

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.platformErrorCode())
                    .isEqualTo(ShopifyWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
        }
    }

    // =========================================================================
    // Credential pre-checks
    // =========================================================================
    @Nested
    @DisplayName("Credential pre-checks")
    class CredentialChecks {

        @Test
        @DisplayName("blank shopDomain returns permanentReject MISSING_SHOP_DOMAIN")
        void blankShopDomain() {
            mockMappingFound();
            Map<String, String> creds = new HashMap<>();
            creds.put("accessToken", "shpat_secret_token");
            creds.put("locationId", LOCATION_ID);
            ConnectionContext ctx = new ConnectionContext(CONNECTION_ID, STORE_ID, "shopify", creds);
            PlatformChange change = buildChange("inventory", "product",
                    UUID.randomUUID().toString(), "10");

            PlatformWriteResult result = connector.submit(ctx, change);

            assertThat(result.platformErrorCode())
                    .isEqualTo(ShopifyWriteConnector.ERROR_MISSING_SHOP_DOMAIN);
        }

        @Test
        @DisplayName("inventory with missing locationId returns permanentReject MISSING_LOCATION_ID")
        void missingLocationId() {
            mockMappingFound();
            Map<String, String> creds = new HashMap<>();
            creds.put("shopDomain", "https://" + SHOP);
            creds.put("accessToken", "shpat_secret_token");
            ConnectionContext ctx = new ConnectionContext(CONNECTION_ID, STORE_ID, "shopify", creds);
            PlatformChange change = buildChange("inventory", "product",
                    UUID.randomUUID().toString(), "10");

            PlatformWriteResult result = connector.submit(ctx, change);

            assertThat(result.platformErrorCode())
                    .isEqualTo(ShopifyWriteConnector.ERROR_MISSING_LOCATION_ID);
        }
    }

    // =========================================================================
    // Request-body building
    // =========================================================================
    @Nested
    @DisplayName("Request-body building")
    class RequestBody {

        @Test
        @DisplayName("inventory body sets location_id, inventory_item_id and available")
        void inventoryBody() throws Exception {
            String body = connector.buildInventoryBody(EXTERNAL_ID, LOCATION_ID, "42");
            JsonNode root = objectMapper.readTree(body);
            assertThat(root.path("location_id").asLong()).isEqualTo(55555L);
            assertThat(root.path("inventory_item_id").asLong()).isEqualTo(987654321L);
            assertThat(root.path("available").asInt()).isEqualTo(42);
        }

        @Test
        @DisplayName("inventory body tolerates decimal-looking integers")
        void inventoryBodyDecimal() throws Exception {
            String body = connector.buildInventoryBody(EXTERNAL_ID, LOCATION_ID, "10.0");
            assertThat(objectMapper.readTree(body).path("available").asInt()).isEqualTo(10);
        }

        @Test
        @DisplayName("fulfillment body with tracking number attaches tracking_info.number")
        void fulfillmentBodyWithTracking() throws Exception {
            String body = connector.buildFulfillmentBody("1Z999AA10123456784");
            JsonNode fulfillment = objectMapper.readTree(body).path("fulfillment");
            assertThat(fulfillment.path("tracking_info").path("number").asText())
                    .isEqualTo("1Z999AA10123456784");
        }

        @Test
        @DisplayName("fulfillment body without tracking omits tracking_info")
        void fulfillmentBodyWithoutTracking() throws Exception {
            String body = connector.buildFulfillmentBody(null);
            JsonNode fulfillment = objectMapper.readTree(body).path("fulfillment");
            assertThat(fulfillment.has("tracking_info")).isFalse();
        }
    }

    // =========================================================================
    // Single-call submit via subclass hook → 2xx accepted
    // =========================================================================
    @Test
    @DisplayName("successful inventory submit returns accepted with exactly one HTTP call")
    void successfulInventorySubmit() {
        mockMappingFound();
        mockSanitizer();

        int[] calls = {0};
        String[] capturedUrl = {null};
        String[] capturedBody = {null};
        ShopifyWriteConnector spyConnector = new ShopifyWriteConnector(
                externalEntityMappingMapper, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(String method, String url, String accessToken,
                                           ConnectionContext ctx, String requestBody) {
                calls[0]++;
                capturedUrl[0] = url;
                capturedBody[0] = requestBody;
                return "{\"inventory_level\":{\"inventory_item_id\":987654321,\"available\":42}}";
            }
        };

        PlatformChange change = buildChange("inventory", "product",
                UUID.randomUUID().toString(), "42");
        PlatformWriteResult result = spyConnector.submit(buildContext(), change);

        assertThat(calls[0]).isEqualTo(1);
        assertThat(result.accepted()).isTrue();
        assertThat(capturedUrl[0])
                .isEqualTo("https://" + SHOP + "/admin/api/2024-01/inventory_levels/set.json");
        assertThat(capturedBody[0]).contains("\"available\":42");
    }

    @Test
    @DisplayName("successful fulfillment submit returns accepted referencing the fulfillment id")
    void successfulFulfillmentSubmit() {
        mockMappingFound();
        mockSanitizer();

        ShopifyWriteConnector spyConnector = new ShopifyWriteConnector(
                externalEntityMappingMapper, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(String method, String url, String accessToken,
                                           ConnectionContext ctx, String requestBody) {
                return "{\"fulfillment\":{\"id\":111222333,\"status\":\"success\"}}";
            }
        };

        PlatformChange change = buildChange("fulfillment", "order",
                UUID.randomUUID().toString(), "TRACK123");
        PlatformWriteResult result = spyConnector.submit(buildContext(), change);

        assertThat(result.accepted()).isTrue();
        assertThat(result.platformReference()).isEqualTo("111222333");
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
                    .isEqualTo(ShopifyWriteConnector.ERROR_TOKEN_INVALID);
            assertThat(connector.mapErrorResponse(403, "{}").platformErrorCode())
                    .isEqualTo(ShopifyWriteConnector.ERROR_TOKEN_INVALID);
        }

        @Test
        @DisplayName("4xx → permanentReject carrying the platform reason")
        void clientError() {
            String body = "{\"errors\":{\"base\":[\"Inventory item not stocked at location\"]}}";
            PlatformWriteResult r = connector.mapErrorResponse(422, body);
            assertThat(r.accepted()).isFalse();
            assertThat(r.retryable()).isFalse();
            assertThat(r.message()).contains("Inventory item not stocked at location");
        }

        @Test
        @DisplayName("4xx with textual errors surfaces the message")
        void clientErrorTextual() {
            PlatformWriteResult r = connector.mapErrorResponse(404, "{\"errors\":\"Not Found\"}");
            assertThat(r.message()).isEqualTo("Not Found");
        }
    }

    // =========================================================================
    // Helper / status
    // =========================================================================
    @Nested
    @DisplayName("Helpers and status mapping")
    class Helpers {

        @Test
        @DisplayName("normalizeShopDomain strips scheme and trailing slash")
        void normalize() {
            assertThat(ShopifyWriteConnector.normalizeShopDomain("https://x.myshopify.com/"))
                    .isEqualTo("x.myshopify.com");
            assertThat(ShopifyWriteConnector.normalizeShopDomain("http://y.myshopify.com"))
                    .isEqualTo("y.myshopify.com");
            assertThat(ShopifyWriteConnector.normalizeShopDomain(null)).isEmpty();
        }

        @Test
        @DisplayName("status mapping")
        void statusMapping() {
            assertThat(connector.mapPlatformStatus("success")).isEqualTo(SyncState.EFFECTIVE);
            assertThat(connector.mapPlatformStatus("cancelled")).isEqualTo(SyncState.FAILED);
            assertThat(connector.mapPlatformStatus(null)).isEqualTo(SyncState.RECONCILIATION_REQUIRED);
            assertThat(connector.mapPlatformStatus("wat")).isEqualTo(SyncState.RECONCILIATION_REQUIRED);
        }
    }
}
