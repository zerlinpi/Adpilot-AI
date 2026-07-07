package com.adpilot.modules.upload.publish;

import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.entity.ExternalEntityMappingEntity;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.support.PlatformLogSanitizer;
import com.adpilot.modules.listing.entity.ListingContentEntity;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.upload.entity.ProductUploadJobEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Offline unit tests for {@link IndependentSiteProductPublishService} (独立站商品直发).
 *
 * <p>Covers request-body construction for Shopify and WooCommerce product
 * create, 2xx → published with the platform product id captured into
 * {@code external_entity_mappings}, 4xx → failed (rejected) with the platform
 * reason, and missing connection / missing credentials → refused (no publish,
 * no external mapping written). HTTP is never performed: the create call is
 * exercised through a single-call subclass hook, mirroring the
 * {@code GoogleAdsWriteConnectorTest} / {@code ShopifyWriteConnectorTest} style.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IndependentSiteProductPublishService")
class IndependentSiteProductPublishServiceTest {

    @Mock
    private PlatformConnectionMapper platformConnectionMapper;
    @Mock
    private ExternalEntityMappingMapper externalEntityMappingMapper;
    @Mock
    private CryptoUtil cryptoUtil;
    @Mock
    private PlatformLogSanitizer logSanitizer;

    private ObjectMapper objectMapper;
    private IndependentSiteProductPublishService service;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new IndependentSiteProductPublishService(
                platformConnectionMapper, externalEntityMappingMapper, cryptoUtil, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));
    }

    private void mockSanitizer() {
        lenient().when(logSanitizer.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** CryptoUtil is mocked to a pass-through so config values are treated as plaintext. */
    private void mockCryptoPassThrough() {
        lenient().when(cryptoUtil.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ProductEntity product() {
        ProductEntity p = new ProductEntity();
        p.setId(PRODUCT_ID);
        p.setStoreId(STORE_ID);
        p.setSku("SKU-123");
        p.setName("Local Product Name");
        p.setBrand("AcmeBrand");
        p.setPrice(new BigDecimal("19.9900"));
        p.setInventory(7);
        p.setImageUrl("https://cdn.example.com/img.jpg");
        return p;
    }

    private ListingContentEntity listing() {
        ListingContentEntity l = new ListingContentEntity();
        l.setProductId(PRODUCT_ID);
        l.setTitle("Listing Title");
        l.setDescription("A great product description.");
        return l;
    }

    private ProductUploadJobEntity job(String method) {
        ProductUploadJobEntity job = new ProductUploadJobEntity();
        job.setId(UUID.randomUUID());
        job.setStoreId(STORE_ID);
        job.setProductId(PRODUCT_ID);
        job.setUploadMethod(method);
        job.setStatus("approved");
        return job;
    }

    private PlatformConnectionEntity connection(String platform, String config) {
        PlatformConnectionEntity c = new PlatformConnectionEntity();
        c.setId(UUID.randomUUID());
        c.setStoreId(STORE_ID);
        c.setPlatform(platform);
        c.setStatus("connected");
        c.setConfigEncrypted(config);
        return c;
    }

    @SuppressWarnings("unchecked")
    private void mockConnectionFound(PlatformConnectionEntity c) {
        when(platformConnectionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(c));
    }

    @SuppressWarnings("unchecked")
    private void mockNoConnection() {
        when(platformConnectionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    }

    // =========================================================================
    // Method routing
    // =========================================================================
    @Nested
    @DisplayName("Method routing")
    class MethodRouting {

        @Test
        @DisplayName("shopify_api / woocommerce_api / tiktok_shop_api are direct-publish methods; others are not")
        void directPublishMethods() {
            assertThat(IndependentSiteProductPublishService.isDirectPublishMethod("shopify_api")).isTrue();
            assertThat(IndependentSiteProductPublishService.isDirectPublishMethod("woocommerce_api")).isTrue();
            assertThat(IndependentSiteProductPublishService.isDirectPublishMethod("tiktok_shop_api")).isTrue();
            assertThat(IndependentSiteProductPublishService.isDirectPublishMethod("SHOPIFY_API")).isTrue();
            assertThat(IndependentSiteProductPublishService.isDirectPublishMethod("TIKTOK_SHOP_API")).isTrue();
            assertThat(IndependentSiteProductPublishService.isDirectPublishMethod("manual_export")).isFalse();
            assertThat(IndependentSiteProductPublishService.isDirectPublishMethod("amazon_flat_file")).isFalse();
            assertThat(IndependentSiteProductPublishService.isDirectPublishMethod(null)).isFalse();
        }

        @Test
        @DisplayName("platformOf maps upload method to platform key")
        void platformOf() {
            assertThat(IndependentSiteProductPublishService.platformOf("shopify_api")).isEqualTo("shopify");
            assertThat(IndependentSiteProductPublishService.platformOf("woocommerce_api")).isEqualTo("woocommerce");
            assertThat(IndependentSiteProductPublishService.platformOf("tiktok_shop_api")).isEqualTo("tiktok_shop");
            assertThat(IndependentSiteProductPublishService.platformOf("manual_export")).isNull();
        }
    }

    // =========================================================================
    // Request-body construction
    // =========================================================================
    @Nested
    @DisplayName("Request-body construction")
    class RequestBody {

        @Test
        @DisplayName("Shopify body builds product.title/body_html/vendor/variants/images")
        void shopifyBody() throws Exception {
            String body = service.buildShopifyProductBody(product(), listing());
            JsonNode p = objectMapper.readTree(body).path("product");
            assertThat(p.path("title").asText()).isEqualTo("Listing Title");
            assertThat(p.path("body_html").asText()).isEqualTo("A great product description.");
            assertThat(p.path("vendor").asText()).isEqualTo("AcmeBrand");
            JsonNode variant = p.path("variants").get(0);
            assertThat(variant.path("price").asText()).isEqualTo("19.99");
            assertThat(variant.path("sku").asText()).isEqualTo("SKU-123");
            assertThat(variant.path("inventory_quantity").asInt()).isEqualTo(7);
            assertThat(p.path("images").get(0).path("src").asText())
                    .isEqualTo("https://cdn.example.com/img.jpg");
        }

        @Test
        @DisplayName("Shopify body falls back to product name when listing has no title")
        void shopifyBodyTitleFallback() throws Exception {
            String body = service.buildShopifyProductBody(product(), null);
            assertThat(objectMapper.readTree(body).path("product").path("title").asText())
                    .isEqualTo("Local Product Name");
        }

        @Test
        @DisplayName("WooCommerce body builds name/description/regular_price/sku/stock/images")
        void wooBody() throws Exception {
            String body = service.buildWooProductBody(product(), listing());
            JsonNode root = objectMapper.readTree(body);
            assertThat(root.path("name").asText()).isEqualTo("Listing Title");
            assertThat(root.path("description").asText()).isEqualTo("A great product description.");
            assertThat(root.path("regular_price").asText()).isEqualTo("19.99");
            assertThat(root.path("sku").asText()).isEqualTo("SKU-123");
            assertThat(root.path("manage_stock").asBoolean()).isTrue();
            assertThat(root.path("stock_quantity").asInt()).isEqualTo(7);
            assertThat(root.path("images").get(0).path("src").asText())
                    .isEqualTo("https://cdn.example.com/img.jpg");
        }

        @Test
        @DisplayName("Shopify and Woo endpoints carry the pinned API versions")
        void endpoints() {
            assertThat(service.buildShopifyProductEndpoint())
                    .isEqualTo("/admin/api/2024-01/products.json");
            assertThat(service.buildWooProductEndpoint())
                    .isEqualTo("/wp-json/wc/v3/products");
        }

        @Test
        @DisplayName("TikTok body builds title/description/brand_name/skus(price+inventory)/main_images")
        void tiktokBody() throws Exception {
            String body = service.buildTikTokProductBody(product(), listing());
            JsonNode root = objectMapper.readTree(body);
            assertThat(root.path("title").asText()).isEqualTo("Listing Title");
            assertThat(root.path("description").asText()).isEqualTo("A great product description.");
            assertThat(root.path("brand_name").asText()).isEqualTo("AcmeBrand");
            JsonNode sku = root.path("skus").get(0);
            assertThat(sku.path("seller_sku").asText()).isEqualTo("SKU-123");
            assertThat(sku.path("price").path("amount").asText()).isEqualTo("19.99");
            assertThat(sku.path("inventory").get(0).path("quantity").asInt()).isEqualTo(7);
            assertThat(root.path("main_images").get(0).path("url").asText())
                    .isEqualTo("https://cdn.example.com/img.jpg");
        }
    }

    // =========================================================================
    // 2xx → published with external id captured
    // =========================================================================
    @Test
    @DisplayName("Shopify 2xx → published, captures platform product id and persists external mapping")
    void shopifyPublishedCapturesExternalId() {
        mockSanitizer();
        mockCryptoPassThrough();
        mockConnectionFound(connection("shopify",
                "{\"shopDomain\":\"https://test-shop.myshopify.com\",\"accessToken\":\"shpat_tok\"}"));
        when(externalEntityMappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        int[] calls = {0};
        String[] capturedUrl = {null};
        Map<String, String>[] capturedHeaders = new Map[]{null};
        IndependentSiteProductPublishService spy = new IndependentSiteProductPublishService(
                platformConnectionMapper, externalEntityMappingMapper, cryptoUtil, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(HttpMethod method, String url, Map<String, String> headers, String body) {
                calls[0]++;
                capturedUrl[0] = url;
                capturedHeaders[0] = headers;
                return "{\"product\":{\"id\":1234567890,\"title\":\"Listing Title\"}}";
            }
        };

        ProductPublishOutcome outcome = spy.publish(job("shopify_api"), product(), listing());

        assertThat(calls[0]).isEqualTo(1);
        assertThat(outcome.published()).isTrue();
        assertThat(outcome.platformProductId()).isEqualTo("1234567890");
        assertThat(outcome.publishMode()).isEqualTo("direct");
        assertThat(capturedUrl[0])
                .isEqualTo("https://test-shop.myshopify.com/admin/api/2024-01/products.json");
        assertThat(capturedHeaders[0]).containsKey("X-Shopify-Access-Token");

        ArgumentCaptor<ExternalEntityMappingEntity> captor =
                ArgumentCaptor.forClass(ExternalEntityMappingEntity.class);
        verify(externalEntityMappingMapper).insert(captor.capture());
        ExternalEntityMappingEntity mapping = captor.getValue();
        assertThat(mapping.getStoreId()).isEqualTo(STORE_ID);
        assertThat(mapping.getPlatform()).isEqualTo("shopify");
        assertThat(mapping.getInternalEntityType()).isEqualTo("product");
        assertThat(mapping.getInternalEntityId()).isEqualTo(PRODUCT_ID);
        assertThat(mapping.getExternalEntityId()).isEqualTo("1234567890");
    }

    @Test
    @DisplayName("WooCommerce 2xx → published, captures product id and uses Basic auth")
    void wooPublishedCapturesExternalId() {
        mockSanitizer();
        mockCryptoPassThrough();
        mockConnectionFound(connection("woocommerce",
                "{\"siteUrl\":\"https://shop.example.com/\",\"consumerKey\":\"ck_x\",\"consumerSecret\":\"cs_y\"}"));
        when(externalEntityMappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        String[] capturedUrl = {null};
        Map<String, String>[] capturedHeaders = new Map[]{null};
        IndependentSiteProductPublishService spy = new IndependentSiteProductPublishService(
                platformConnectionMapper, externalEntityMappingMapper, cryptoUtil, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(HttpMethod method, String url, Map<String, String> headers, String body) {
                capturedUrl[0] = url;
                capturedHeaders[0] = headers;
                return "{\"id\":555,\"name\":\"Listing Title\"}";
            }
        };

        ProductPublishOutcome outcome = spy.publish(job("woocommerce_api"), product(), listing());

        assertThat(outcome.published()).isTrue();
        assertThat(outcome.platformProductId()).isEqualTo("555");
        assertThat(capturedUrl[0]).isEqualTo("https://shop.example.com/wp-json/wc/v3/products");
        assertThat(capturedHeaders[0].get(HttpHeaders.AUTHORIZATION)).startsWith("Basic ");
        verify(externalEntityMappingMapper).insert(any(ExternalEntityMappingEntity.class));
    }

    // =========================================================================
    // 4xx → failed (rejected) with reason
    // =========================================================================
    @Test
    @DisplayName("Shopify 4xx → rejected with the platform reason; no external mapping written")
    void shopifyRejectedOnClientError() {
        mockSanitizer();
        mockCryptoPassThrough();
        mockConnectionFound(connection("shopify",
                "{\"shopDomain\":\"test-shop.myshopify.com\",\"accessToken\":\"shpat_tok\"}"));

        IndependentSiteProductPublishService spy = new IndependentSiteProductPublishService(
                platformConnectionMapper, externalEntityMappingMapper, cryptoUtil, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(HttpMethod method, String url, Map<String, String> headers, String body) {
                throw new RestClientResponseException(
                        "Unprocessable Entity", 422, "Unprocessable Entity",
                        HttpHeaders.EMPTY,
                        "{\"errors\":{\"title\":[\"can't be blank\"]}}".getBytes(),
                        null);
            }
        };

        ProductPublishOutcome outcome = spy.publish(job("shopify_api"), product(), listing());

        assertThat(outcome.published()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("HTTP_422");
        assertThat(outcome.reason()).contains("title").contains("can't be blank");
        verify(externalEntityMappingMapper, never()).insert(any());
    }

    @Test
    @DisplayName("WooCommerce 4xx → rejected with the platform message; no external mapping written")
    void wooRejectedOnClientError() {
        mockSanitizer();
        mockCryptoPassThrough();
        mockConnectionFound(connection("woocommerce",
                "{\"siteUrl\":\"https://shop.example.com\",\"consumerKey\":\"ck_x\",\"consumerSecret\":\"cs_y\"}"));

        IndependentSiteProductPublishService spy = new IndependentSiteProductPublishService(
                platformConnectionMapper, externalEntityMappingMapper, cryptoUtil, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(HttpMethod method, String url, Map<String, String> headers, String body) {
                throw new RestClientResponseException(
                        "Bad Request", 400, "Bad Request",
                        HttpHeaders.EMPTY,
                        "{\"code\":\"product_invalid_sku\",\"message\":\"Invalid or duplicated SKU.\"}".getBytes(),
                        null);
            }
        };

        ProductPublishOutcome outcome = spy.publish(job("woocommerce_api"), product(), listing());

        assertThat(outcome.published()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("HTTP_400");
        assertThat(outcome.reason()).contains("Invalid or duplicated SKU.");
        verify(externalEntityMappingMapper, never()).insert(any());
    }

    // =========================================================================
    // TikTok Shop direct publish (real signed product-create call)
    // =========================================================================
    private static final String TIKTOK_CONFIG =
            "{\"appKey\":\"ak\",\"appSecret\":\"as\",\"accessToken\":\"tts-tok\","
                    + "\"shopCipher\":\"TTP_cipher\",\"shopId\":\"7493\"}";

    @Test
    @DisplayName("TikTok HTTP 200 code:0 → published, captures product id, signs URL and sends access-token header")
    void tiktokPublishedCapturesExternalId() {
        mockSanitizer();
        mockCryptoPassThrough();
        mockConnectionFound(connection("tiktok_shop", TIKTOK_CONFIG));
        when(externalEntityMappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        int[] calls = {0};
        String[] capturedUrl = {null};
        Map<String, String>[] capturedHeaders = new Map[]{null};
        IndependentSiteProductPublishService spy = new IndependentSiteProductPublishService(
                platformConnectionMapper, externalEntityMappingMapper, cryptoUtil, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(HttpMethod method, String url, Map<String, String> headers, String body) {
                calls[0]++;
                capturedUrl[0] = url;
                capturedHeaders[0] = headers;
                return "{\"code\":0,\"message\":\"Success\",\"data\":{\"product_id\":\"1729592969712207000\"}}";
            }
        };

        ProductPublishOutcome outcome = spy.publish(job("tiktok_shop_api"), product(), listing());

        assertThat(calls[0]).isEqualTo(1);
        assertThat(outcome.published()).isTrue();
        assertThat(outcome.platformProductId()).isEqualTo("1729592969712207000");
        assertThat(outcome.publishMode()).isEqualTo("direct");
        assertThat(capturedUrl[0])
                .startsWith("https://open-api.tiktokglobalshop.com/product/202309/products?")
                .contains("app_key=ak")
                .contains("shop_cipher=TTP_cipher")
                .contains("&sign=");
        assertThat(capturedHeaders[0]).containsKey("x-tts-access-token");

        ArgumentCaptor<ExternalEntityMappingEntity> captor =
                ArgumentCaptor.forClass(ExternalEntityMappingEntity.class);
        verify(externalEntityMappingMapper).insert(captor.capture());
        ExternalEntityMappingEntity mapping = captor.getValue();
        assertThat(mapping.getStoreId()).isEqualTo(STORE_ID);
        assertThat(mapping.getPlatform()).isEqualTo("tiktok_shop");
        assertThat(mapping.getInternalEntityType()).isEqualTo("product");
        assertThat(mapping.getInternalEntityId()).isEqualTo(PRODUCT_ID);
        assertThat(mapping.getExternalEntityId()).isEqualTo("1729592969712207000");
    }

    @Test
    @DisplayName("TikTok HTTP 200 with non-zero business code → HONEST failure, never faked, no external mapping")
    void tiktokBusinessErrorIsFailure() {
        mockSanitizer();
        mockCryptoPassThrough();
        mockConnectionFound(connection("tiktok_shop", TIKTOK_CONFIG));

        IndependentSiteProductPublishService spy = new IndependentSiteProductPublishService(
                platformConnectionMapper, externalEntityMappingMapper, cryptoUtil, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(HttpMethod method, String url, Map<String, String> headers, String body) {
                return "{\"code\":12053001,\"message\":\"category is required\",\"data\":{}}";
            }
        };

        ProductPublishOutcome outcome = spy.publish(job("tiktok_shop_api"), product(), listing());

        assertThat(outcome.published()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("TIKTOK_12053001");
        assertThat(outcome.reason()).contains("category is required");
        verify(externalEntityMappingMapper, never()).insert(any());
    }

    @Test
    @DisplayName("TikTok 4xx → rejected with the platform reason; no external mapping written")
    void tiktokRejectedOnClientError() {
        mockSanitizer();
        mockCryptoPassThrough();
        mockConnectionFound(connection("tiktok_shop", TIKTOK_CONFIG));

        IndependentSiteProductPublishService spy = new IndependentSiteProductPublishService(
                platformConnectionMapper, externalEntityMappingMapper, cryptoUtil, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(HttpMethod method, String url, Map<String, String> headers, String body) {
                throw new RestClientResponseException(
                        "Bad Request", 400, "Bad Request",
                        HttpHeaders.EMPTY,
                        "{\"code\":36004003,\"message\":\"invalid product payload\"}".getBytes(),
                        null);
            }
        };

        ProductPublishOutcome outcome = spy.publish(job("tiktok_shop_api"), product(), listing());

        assertThat(outcome.published()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("HTTP_400");
        assertThat(outcome.reason()).contains("invalid product payload");
        verify(externalEntityMappingMapper, never()).insert(any());
    }

    @Test
    @DisplayName("no connected tiktok_shop connection → refused, no HTTP, no external mapping")
    void tiktokRefusedWhenNoConnection() {
        mockNoConnection();

        int[] calls = {0};
        IndependentSiteProductPublishService spy = new IndependentSiteProductPublishService(
                platformConnectionMapper, externalEntityMappingMapper, cryptoUtil, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(HttpMethod method, String url, Map<String, String> headers, String body) {
                calls[0]++;
                return "{}";
            }
        };

        ProductPublishOutcome outcome = spy.publish(job("tiktok_shop_api"), product(), listing());

        assertThat(outcome.published()).isFalse();
        assertThat(outcome.errorCode())
                .isEqualTo(IndependentSiteProductPublishService.ERROR_NO_CONNECTED_CONNECTION);
        assertThat(calls[0]).isZero();
        verify(externalEntityMappingMapper, never()).insert(any());
    }

    @Test
    @DisplayName("TikTok connection missing credentials → refused before any HTTP")
    void tiktokRefusedWhenMissingCredentials() {
        mockCryptoPassThrough();
        mockConnectionFound(connection("tiktok_shop",
                "{\"appKey\":\"ak\",\"shopCipher\":\"TTP_cipher\"}"));

        int[] calls = {0};
        IndependentSiteProductPublishService spy = new IndependentSiteProductPublishService(
                platformConnectionMapper, externalEntityMappingMapper, cryptoUtil, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(HttpMethod method, String url, Map<String, String> headers, String body) {
                calls[0]++;
                return "{}";
            }
        };

        ProductPublishOutcome outcome = spy.publish(job("tiktok_shop_api"), product(), listing());

        assertThat(outcome.published()).isFalse();
        assertThat(outcome.errorCode())
                .isEqualTo(IndependentSiteProductPublishService.ERROR_MISSING_TIKTOK_CREDENTIALS);
        assertThat(calls[0]).isZero();
        verify(externalEntityMappingMapper, never()).insert(any());
    }

    @Test
    @DisplayName("TikTok connection missing shop identifier → refused before any HTTP")
    void tiktokRefusedWhenMissingShop() {
        mockCryptoPassThrough();
        mockConnectionFound(connection("tiktok_shop",
                "{\"appKey\":\"ak\",\"appSecret\":\"as\",\"accessToken\":\"tts-tok\"}"));

        int[] calls = {0};
        IndependentSiteProductPublishService spy = new IndependentSiteProductPublishService(
                platformConnectionMapper, externalEntityMappingMapper, cryptoUtil, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(HttpMethod method, String url, Map<String, String> headers, String body) {
                calls[0]++;
                return "{}";
            }
        };

        ProductPublishOutcome outcome = spy.publish(job("tiktok_shop_api"), product(), listing());

        assertThat(outcome.published()).isFalse();
        assertThat(outcome.errorCode())
                .isEqualTo(IndependentSiteProductPublishService.ERROR_MISSING_TIKTOK_SHOP);
        assertThat(calls[0]).isZero();
        verify(externalEntityMappingMapper, never()).insert(any());
    }

    // =========================================================================
    // Missing connection / missing creds → refused (no publish)
    // =========================================================================
    @Test
    @DisplayName("no connected connection → refused, no HTTP, no external mapping")
    void refusedWhenNoConnection() {
        mockNoConnection();

        int[] calls = {0};
        IndependentSiteProductPublishService spy = new IndependentSiteProductPublishService(
                platformConnectionMapper, externalEntityMappingMapper, cryptoUtil, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(HttpMethod method, String url, Map<String, String> headers, String body) {
                calls[0]++;
                return "{}";
            }
        };

        ProductPublishOutcome outcome = spy.publish(job("shopify_api"), product(), listing());

        assertThat(outcome.published()).isFalse();
        assertThat(outcome.errorCode())
                .isEqualTo(IndependentSiteProductPublishService.ERROR_NO_CONNECTED_CONNECTION);
        assertThat(calls[0]).isZero();
        verify(externalEntityMappingMapper, never()).insert(any());
    }

    @Test
    @DisplayName("Shopify connection missing accessToken → refused before any HTTP")
    void refusedWhenMissingShopifyToken() {
        mockCryptoPassThrough();
        mockConnectionFound(connection("shopify",
                "{\"shopDomain\":\"test-shop.myshopify.com\"}"));

        int[] calls = {0};
        IndependentSiteProductPublishService spy = new IndependentSiteProductPublishService(
                platformConnectionMapper, externalEntityMappingMapper, cryptoUtil, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(HttpMethod method, String url, Map<String, String> headers, String body) {
                calls[0]++;
                return "{}";
            }
        };

        ProductPublishOutcome outcome = spy.publish(job("shopify_api"), product(), listing());

        assertThat(outcome.published()).isFalse();
        assertThat(outcome.errorCode())
                .isEqualTo(IndependentSiteProductPublishService.ERROR_MISSING_ACCESS_TOKEN);
        assertThat(calls[0]).isZero();
        verify(externalEntityMappingMapper, never()).insert(any());
    }

    @Test
    @DisplayName("WooCommerce connection missing consumer credentials → refused before any HTTP")
    void refusedWhenMissingWooCreds() {
        mockCryptoPassThrough();
        mockConnectionFound(connection("woocommerce",
                "{\"siteUrl\":\"https://shop.example.com\"}"));

        ProductPublishOutcome outcome = service.publish(job("woocommerce_api"), product(), listing());

        assertThat(outcome.published()).isFalse();
        assertThat(outcome.errorCode())
                .isEqualTo(IndependentSiteProductPublishService.ERROR_MISSING_WOO_CREDENTIALS);
        verify(externalEntityMappingMapper, never()).insert(any());
    }

    // =========================================================================
    // Idempotent re-publish updates the existing mapping
    // =========================================================================
    @Test
    @DisplayName("re-publish updates an existing external mapping in place")
    void rePublishUpdatesMapping() {
        mockSanitizer();
        mockCryptoPassThrough();
        mockConnectionFound(connection("shopify",
                "{\"shopDomain\":\"test-shop.myshopify.com\",\"accessToken\":\"shpat_tok\"}"));
        ExternalEntityMappingEntity existing = ExternalEntityMappingEntity.builder()
                .id(UUID.randomUUID()).storeId(STORE_ID).platform("shopify")
                .internalEntityType("product").internalEntityId(PRODUCT_ID)
                .externalEntityId("old-id").build();
        when(externalEntityMappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        IndependentSiteProductPublishService spy = new IndependentSiteProductPublishService(
                platformConnectionMapper, externalEntityMappingMapper, cryptoUtil, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(HttpMethod method, String url, Map<String, String> headers, String body) {
                return "{\"product\":{\"id\":999}}";
            }
        };

        ProductPublishOutcome outcome = spy.publish(job("shopify_api"), product(), listing());

        assertThat(outcome.published()).isTrue();
        verify(externalEntityMappingMapper, times(1)).updateById(any(ExternalEntityMappingEntity.class));
        verify(externalEntityMappingMapper, never()).insert(any());
        assertThat(existing.getExternalEntityId()).isEqualTo("999");
    }

    // =========================================================================
    // Helpers
    // =========================================================================
    @Test
    @DisplayName("price normalization strips trailing zeros to a plain decimal")
    void priceNormalization() {
        assertThat(IndependentSiteProductPublishService.normalizePrice(new BigDecimal("19.9900")))
                .isEqualTo("19.99");
        assertThat(IndependentSiteProductPublishService.normalizePrice(new BigDecimal("20.0000")))
                .isEqualTo("20");
        assertThat(IndependentSiteProductPublishService.normalizePrice(null)).isEqualTo("0");
    }

    @Test
    @DisplayName("domain/url normalization strips scheme/trailing slash")
    void normalization() {
        assertThat(IndependentSiteProductPublishService.normalizeShopDomain("https://x.myshopify.com/"))
                .isEqualTo("x.myshopify.com");
        assertThat(IndependentSiteProductPublishService.normalizeSiteUrl("https://shop.example.com/"))
                .isEqualTo("https://shop.example.com");
    }
}
