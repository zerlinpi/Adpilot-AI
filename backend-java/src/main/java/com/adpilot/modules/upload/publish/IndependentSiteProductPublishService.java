package com.adpilot.modules.upload.publish;

import com.adpilot.common.config.HttpClientFactory;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.connector.TikTokShopApiSigner;
import com.adpilot.modules.apisync.entity.ExternalEntityMappingEntity;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.support.PlatformLogSanitizer;
import com.adpilot.modules.listing.entity.ListingContentEntity;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.upload.entity.ProductUploadJobEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Direct product publish ("独立站商品直发") to an independent-site platform —
 * Shopify or WooCommerce — using the store's stored, connected credentials.
 *
 * <p>This is the real direct-publish path that replaces the previous silent
 * downgrade of {@code shopify_api}/{@code woocommerce_api} upload jobs to
 * {@code manual_export}. It mirrors the connector style of
 * {@link com.adpilot.modules.apisync.connector.ShopifyWriteConnector} and
 * {@link com.adpilot.modules.apisync.connector.WooCommerceWriteConnector}:
 * it resolves the connected {@link PlatformConnectionEntity} via
 * {@link PlatformConnectionMapper}, decrypts the same credential keys
 * (Shopify: {@code shopDomain} + {@code accessToken}; WooCommerce:
 * {@code siteUrl} + {@code consumerKey} + {@code consumerSecret}), performs
 * exactly one create HTTP call, and persists the platform product id into
 * {@code external_entity_mappings} on success.</p>
 *
 * <h2>Honesty contract</h2>
 * <p>A REAL HTTP call is made to the platform; success is never faked.</p>
 * <ul>
 *   <li>No connected connection / missing credentials → {@link ProductPublishOutcome#refused}
 *       (the caller leaves the job failed; it is never exported or marked published).</li>
 *   <li>Platform non-2xx → {@link ProductPublishOutcome#rejected} carrying the
 *       platform's own readable reason.</li>
 *   <li>2xx → {@link ProductPublishOutcome#published} with the captured platform
 *       product id and {@code publish_mode = direct}.</li>
 *   <li>Transport/credential failures are surfaced as exceptions (callers mark
 *       the job failed with the message as the reason).</li>
 * </ul>
 *
 * <p>Credentials are sent only via the platform's own auth header
 * ({@code X-Shopify-Access-Token} / HTTP Basic) and are never logged; all logged
 * request/response text passes through {@link PlatformLogSanitizer}.</p>
 *
 * <p><strong>VERIFICATION NOTE:</strong> Shopify create targets Admin REST API
 * <strong>{@value #SHOPIFY_API_VERSION}</strong>
 * ({@code POST /admin/api/{ver}/products.json}) and WooCommerce create targets
 * REST API <strong>{@value #WOO_API_VERSION}</strong>
 * ({@code POST /wp-json/wc/{ver}/products}). The version paths, resource shapes,
 * and request-body field names ({@code product.title}/{@code body_html}/
 * {@code variants}/{@code images} for Shopify; {@code name}/{@code description}/
 * {@code regular_price}/{@code sku}/{@code images} for WooCommerce) are
 * version/plugin-specific and cannot be validated offline. Verify against live
 * credentials and adjust if the platform changes them; this mirrors the
 * {@link com.adpilot.modules.apisync.connector.GoogleAdsWriteConnector}
 * verification note.</p>
 */
@Slf4j
@Service
public class IndependentSiteProductPublishService {

    /** Shopify Admin REST API version targeted by the product-create call. */
    static final String SHOPIFY_API_VERSION = "2024-01";
    /** WooCommerce REST API version targeted by the product-create call. */
    static final String WOO_API_VERSION = "v3";

    /** Platform keys (match {@code platform_connections.platform} values). */
    static final String PLATFORM_SHOPIFY = "shopify";
    static final String PLATFORM_WOOCOMMERCE = "woocommerce";
    static final String PLATFORM_TIKTOK_SHOP = "tiktok_shop";

    /** Upload-method values that route to this direct-publish path. */
    public static final String METHOD_SHOPIFY_API = "shopify_api";
    public static final String METHOD_WOOCOMMERCE_API = "woocommerce_api";
    public static final String METHOD_TIKTOK_API = "tiktok_shop_api";

    /** The connection status treated as a valid active connection (mirrors OperationWriteBackImpl). */
    static final String STATUS_CONNECTED = ConnectionStatus.CONNECTED;

    /** Internal/external entity type recorded in external_entity_mappings. */
    static final String ENTITY_TYPE_PRODUCT = "product";

    // Error codes (carried on a refused/rejected outcome).
    static final String ERROR_UNSUPPORTED_METHOD = "UNSUPPORTED_UPLOAD_METHOD";
    static final String ERROR_NO_CONNECTED_CONNECTION = "NO_CONNECTED_CONNECTION";
    static final String ERROR_MISSING_SHOP_DOMAIN = "MISSING_SHOP_DOMAIN";
    static final String ERROR_MISSING_ACCESS_TOKEN = "MISSING_ACCESS_TOKEN";
    static final String ERROR_MISSING_SITE_URL = "MISSING_SITE_URL";
    static final String ERROR_MISSING_WOO_CREDENTIALS = "MISSING_WOO_CREDENTIALS";
    static final String ERROR_MISSING_TIKTOK_CREDENTIALS = "MISSING_TIKTOK_CREDENTIALS";
    static final String ERROR_MISSING_TIKTOK_SHOP = "MISSING_TIKTOK_SHOP";
    static final String ERROR_TIKTOK_EMPTY_RESPONSE = "EMPTY_RESPONSE";
    static final String ERROR_TIKTOK_MALFORMED_RESPONSE = "MALFORMED_RESPONSE";

    private final PlatformConnectionMapper platformConnectionMapper;
    private final ExternalEntityMappingMapper externalEntityMappingMapper;
    private final CryptoUtil cryptoUtil;
    private final ObjectMapper objectMapper;
    private final PlatformLogSanitizer logSanitizer;
    private final RestClient http;

    public IndependentSiteProductPublishService(PlatformConnectionMapper platformConnectionMapper,
                                                ExternalEntityMappingMapper externalEntityMappingMapper,
                                                CryptoUtil cryptoUtil,
                                                ObjectMapper objectMapper,
                                                PlatformLogSanitizer logSanitizer,
                                                HttpClientFactory httpClientFactory) {
        this.platformConnectionMapper = platformConnectionMapper;
        this.externalEntityMappingMapper = externalEntityMappingMapper;
        this.cryptoUtil = cryptoUtil;
        this.objectMapper = objectMapper;
        this.logSanitizer = logSanitizer;
        // A direct product-create publish can take longer than an interactive call,
        // so use the longer (still bounded) read timeout.
        this.http = httpClientFactory.longReadRestClientBuilder().build();
    }

    /** Whether the given upload method routes to this direct-publish path. */
    public static boolean isDirectPublishMethod(String uploadMethod) {
        return METHOD_SHOPIFY_API.equalsIgnoreCase(uploadMethod)
                || METHOD_WOOCOMMERCE_API.equalsIgnoreCase(uploadMethod)
                || METHOD_TIKTOK_API.equalsIgnoreCase(uploadMethod);
    }

    /** Map an upload method to the corresponding {@code platform_connections.platform} key. */
    static String platformOf(String uploadMethod) {
        if (METHOD_SHOPIFY_API.equalsIgnoreCase(uploadMethod)) {
            return PLATFORM_SHOPIFY;
        }
        if (METHOD_WOOCOMMERCE_API.equalsIgnoreCase(uploadMethod)) {
            return PLATFORM_WOOCOMMERCE;
        }
        if (METHOD_TIKTOK_API.equalsIgnoreCase(uploadMethod)) {
            return PLATFORM_TIKTOK_SHOP;
        }
        return null;
    }

    /**
     * Publish a product directly to the store's connected Shopify/WooCommerce
     * platform. Performs exactly one create HTTP call.
     *
     * @param job     the upload job (carries store id + upload method)
     * @param product the local product to publish (sku/price/brand/image)
     * @param listing the listing content (title/description); may be {@code null}
     * @return the {@link ProductPublishOutcome}: published / refused / rejected
     */
    public ProductPublishOutcome publish(ProductUploadJobEntity job,
                                         ProductEntity product,
                                         ListingContentEntity listing) {
        String method = job.getUploadMethod();
        String platform = platformOf(method);
        if (platform == null) {
            return ProductPublishOutcome.refused(ERROR_UNSUPPORTED_METHOD,
                    "Upload method is not a direct-publish method: " + method);
        }

        // 1. Resolve the store's connected platform connection (no fake / no downgrade).
        PlatformConnectionEntity connection = resolveConnectedConnection(job.getStoreId(), platform);
        if (connection == null) {
            return ProductPublishOutcome.refused(ERROR_NO_CONNECTED_CONNECTION,
                    "店铺没有已连接的 " + platform + " 连接，无法直发商品。请先在“数据连接”中连接该平台。");
        }

        Map<String, String> creds = decryptConfig(connection.getConfigEncrypted());

        if (PLATFORM_SHOPIFY.equals(platform)) {
            return publishToShopify(job, product, listing, connection, creds);
        }
        if (PLATFORM_TIKTOK_SHOP.equals(platform)) {
            return publishToTikTok(job, product, listing, connection, creds);
        }
        return publishToWooCommerce(job, product, listing, connection, creds);
    }

    // -------------------------------------------------------------------------
    // Shopify
    // -------------------------------------------------------------------------

    private ProductPublishOutcome publishToShopify(ProductUploadJobEntity job,
                                                   ProductEntity product,
                                                   ListingContentEntity listing,
                                                   PlatformConnectionEntity connection,
                                                   Map<String, String> creds) {
        String shop = normalizeShopDomain(creds.get("shopDomain"));
        if (shop.isBlank()) {
            return ProductPublishOutcome.refused(ERROR_MISSING_SHOP_DOMAIN,
                    "Shopify 连接缺少有效的 shopDomain，无法直发商品。");
        }
        String accessToken = trimToNull(creds.get("accessToken"));
        if (accessToken == null) {
            return ProductPublishOutcome.refused(ERROR_MISSING_ACCESS_TOKEN,
                    "Shopify 连接缺少 accessToken，无法直发商品。");
        }

        String endpoint = buildShopifyProductEndpoint();
        String requestBody = buildShopifyProductBody(product, listing);
        String url = "https://" + shop + endpoint;

        long start = System.currentTimeMillis();
        log.info("Shopify product publish: POST {} (job={}, storeId={})",
                logSanitizer.sanitize(endpoint), job.getId(), job.getStoreId());
        try {
            String responseBody = doExecuteHttp(HttpMethod.POST, url,
                    Map.of("X-Shopify-Access-Token", accessToken), requestBody);
            String productId = extractShopifyProductId(responseBody);
            persistMapping(job.getStoreId(), PLATFORM_SHOPIFY, product, productId);
            log.info("Shopify product publish success: job={}, platformProductId={}, latencyMs={}",
                    job.getId(), productId, System.currentTimeMillis() - start);
            return ProductPublishOutcome.published(productId, ProductPublishOutcome.PUBLISH_MODE_DIRECT,
                    "Published to Shopify");
        } catch (RestClientResponseException e) {
            String body = logSanitizer.sanitize(e.getResponseBodyAsString());
            int status = e.getStatusCode().value();
            log.warn("Shopify product publish rejected: job={}, HTTP {}, body={}", job.getId(), status, body);
            return ProductPublishOutcome.rejected("HTTP_" + status,
                    "Shopify 拒绝了商品发布 (HTTP " + status + "): " + extractShopifyError(e.getResponseBodyAsString()));
        }
    }

    String buildShopifyProductEndpoint() {
        return "/admin/api/" + SHOPIFY_API_VERSION + "/products.json";
    }

    /**
     * Build the Shopify product-create body:
     * {@code {"product":{"title","body_html","vendor","variants":[{price,sku}],"images":[{src}]}}}.
     */
    String buildShopifyProductBody(ProductEntity product, ListingContentEntity listing) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode productNode = objectMapper.createObjectNode();
        productNode.put("title", resolveTitle(product, listing));
        String bodyHtml = resolveDescription(listing);
        if (bodyHtml != null) {
            productNode.put("body_html", bodyHtml);
        }
        if (product != null && product.getBrand() != null && !product.getBrand().isBlank()) {
            productNode.put("vendor", product.getBrand());
        }

        ArrayNode variants = objectMapper.createArrayNode();
        ObjectNode variant = objectMapper.createObjectNode();
        if (product != null) {
            if (product.getPrice() != null) {
                variant.put("price", normalizePrice(product.getPrice()));
            }
            if (product.getSku() != null && !product.getSku().isBlank()) {
                variant.put("sku", product.getSku());
            }
            if (product.getInventory() != null) {
                variant.put("inventory_quantity", product.getInventory());
            }
        }
        variants.add(variant);
        productNode.set("variants", variants);

        String imageUrl = product != null ? trimToNull(product.getImageUrl()) : null;
        if (imageUrl != null) {
            ArrayNode images = objectMapper.createArrayNode();
            ObjectNode image = objectMapper.createObjectNode();
            image.put("src", imageUrl);
            images.add(image);
            productNode.set("images", images);
        }

        root.set("product", productNode);
        return writeJson(root);
    }

    /** Extract the created product id from a Shopify {@code {"product":{"id":...}}} response. */
    String extractShopifyProductId(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode id = objectMapper.readTree(responseBody).path("product").path("id");
            return (!id.isMissingNode() && !id.isNull()) ? id.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractShopifyError(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return "no detail";
        }
        try {
            JsonNode errors = objectMapper.readTree(errorBody).path("errors");
            if (errors.isMissingNode() || errors.isNull()) {
                return errorBody;
            }
            if (errors.isTextual()) {
                return errors.asText();
            }
            if (errors.isObject() && errors.fields().hasNext()) {
                var entry = errors.fields().next();
                JsonNode val = entry.getValue();
                if (val.isArray() && !val.isEmpty()) {
                    return entry.getKey() + ": " + val.get(0).asText();
                }
                return entry.getKey() + ": " + val.asText();
            }
            return errors.toString();
        } catch (Exception e) {
            return errorBody;
        }
    }

    // -------------------------------------------------------------------------
    // WooCommerce
    // -------------------------------------------------------------------------

    private ProductPublishOutcome publishToWooCommerce(ProductUploadJobEntity job,
                                                       ProductEntity product,
                                                       ListingContentEntity listing,
                                                       PlatformConnectionEntity connection,
                                                       Map<String, String> creds) {
        String site = normalizeSiteUrl(creds.get("siteUrl"));
        if (site.isBlank()) {
            return ProductPublishOutcome.refused(ERROR_MISSING_SITE_URL,
                    "WooCommerce 连接缺少有效的 siteUrl，无法直发商品。");
        }
        String consumerKey = trimToNull(creds.get("consumerKey"));
        String consumerSecret = trimToNull(creds.get("consumerSecret"));
        if (consumerKey == null || consumerSecret == null) {
            return ProductPublishOutcome.refused(ERROR_MISSING_WOO_CREDENTIALS,
                    "WooCommerce 连接缺少 consumerKey/consumerSecret，无法直发商品。");
        }

        String endpoint = buildWooProductEndpoint();
        String requestBody = buildWooProductBody(product, listing);
        String url = site + endpoint;

        long start = System.currentTimeMillis();
        log.info("WooCommerce product publish: POST {} (job={}, storeId={})",
                logSanitizer.sanitize(endpoint), job.getId(), job.getStoreId());
        try {
            String responseBody = doExecuteHttp(HttpMethod.POST, url,
                    Map.of(HttpHeaders.AUTHORIZATION, basicAuth(consumerKey, consumerSecret)), requestBody);
            String productId = extractWooProductId(responseBody);
            persistMapping(job.getStoreId(), PLATFORM_WOOCOMMERCE, product, productId);
            log.info("WooCommerce product publish success: job={}, platformProductId={}, latencyMs={}",
                    job.getId(), productId, System.currentTimeMillis() - start);
            return ProductPublishOutcome.published(productId, ProductPublishOutcome.PUBLISH_MODE_DIRECT,
                    "Published to WooCommerce");
        } catch (RestClientResponseException e) {
            String body = logSanitizer.sanitize(e.getResponseBodyAsString());
            int status = e.getStatusCode().value();
            log.warn("WooCommerce product publish rejected: job={}, HTTP {}, body={}", job.getId(), status, body);
            return ProductPublishOutcome.rejected("HTTP_" + status,
                    "WooCommerce 拒绝了商品发布 (HTTP " + status + "): " + extractWooError(e.getResponseBodyAsString()));
        }
    }

    String buildWooProductEndpoint() {
        return "/wp-json/wc/" + WOO_API_VERSION + "/products";
    }

    /**
     * Build the WooCommerce product-create body:
     * {@code {"name","description","regular_price","sku","manage_stock","stock_quantity","images":[{src}]}}.
     */
    String buildWooProductBody(ProductEntity product, ListingContentEntity listing) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("name", resolveTitle(product, listing));
        String description = resolveDescription(listing);
        if (description != null) {
            root.put("description", description);
        }
        if (product != null) {
            if (product.getPrice() != null) {
                root.put("regular_price", normalizePrice(product.getPrice()));
            }
            if (product.getSku() != null && !product.getSku().isBlank()) {
                root.put("sku", product.getSku());
            }
            if (product.getInventory() != null) {
                root.put("manage_stock", true);
                root.put("stock_quantity", product.getInventory());
            }
            String imageUrl = trimToNull(product.getImageUrl());
            if (imageUrl != null) {
                ArrayNode images = objectMapper.createArrayNode();
                ObjectNode image = objectMapper.createObjectNode();
                image.put("src", imageUrl);
                images.add(image);
                root.set("images", images);
            }
        }
        return writeJson(root);
    }

    /** Extract the created product id from a WooCommerce {@code {"id":...}} response. */
    String extractWooProductId(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode id = objectMapper.readTree(responseBody).path("id");
            return (!id.isMissingNode() && !id.isNull()) ? id.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractWooError(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return "no detail";
        }
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            if (root.has("message")) {
                return root.get("message").asText(errorBody);
            }
            return errorBody;
        } catch (Exception e) {
            return errorBody;
        }
    }

    private static String basicAuth(String key, String secret) {
        String creds = key + ":" + secret;
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------------------------
    // TikTok Shop
    // -------------------------------------------------------------------------

    /**
     * Publish a product to the store's connected TikTok Shop via the real,
     * signed TikTok Shop Open API product-create endpoint
     * ({@code POST /product/202309/products}). Mirrors the Shopify/WooCommerce
     * branches and reuses {@link TikTokShopApiSigner} for the canonical endpoint,
     * URL building, and HMAC-SHA256 signature (no forked signing logic).
     *
     * <p>Honesty contract: missing credentials or shop identifier → refused
     * before any HTTP call; TikTok returns HTTP 200 carrying a business
     * {@code code} (a non-zero {@code code} is a real failure, surfaced verbatim,
     * never treated as success); 4xx/5xx → rejected with the platform reason.</p>
     *
     * <p><strong>VERIFICATION NOTE:</strong> the product-create body field names
     * ({@code title}/{@code description}/{@code brand_name}/{@code skus[].seller_sku}/
     * {@code skus[].price.amount}/{@code skus[].inventory[].quantity}/
     * {@code main_images[].url}) and the {@code data.product_id} response shape are
     * TikTok Shop API {@value TikTokShopApiSigner#API_VERSION}-specific and cannot
     * be validated offline; verify against live credentials and adjust if TikTok
     * changes them. This mirrors the Shopify/WooCommerce verification notes.</p>
     */
    private ProductPublishOutcome publishToTikTok(ProductUploadJobEntity job,
                                                  ProductEntity product,
                                                  ListingContentEntity listing,
                                                  PlatformConnectionEntity connection,
                                                  Map<String, String> creds) {
        String appKey = trimToNull(creds.get("appKey"));
        String appSecret = trimToNull(creds.get("appSecret"));
        String accessToken = trimToNull(creds.get("accessToken"));
        if (appKey == null || appSecret == null || accessToken == null) {
            return ProductPublishOutcome.refused(ERROR_MISSING_TIKTOK_CREDENTIALS,
                    "TikTok Shop 连接缺少 appKey/appSecret/accessToken，无法直发商品。");
        }
        String shopCipher = firstCredential(creds, "shopCipher", "shop_cipher");
        String shopId = firstCredential(creds, "shopId", "shop_id");
        if (shopCipher == null && shopId == null) {
            return ProductPublishOutcome.refused(ERROR_MISSING_TIKTOK_SHOP,
                    "TikTok Shop 连接缺少店铺标识 (shopCipher 或 shopId)，无法直发商品。");
        }

        String path = TikTokShopApiSigner.productCreatePath();
        String requestBody = buildTikTokProductBody(product, listing);
        String url = TikTokShopApiSigner.buildSignedUrl(appKey, appSecret, path, shopCipher, shopId, requestBody);

        long start = System.currentTimeMillis();
        log.info("TikTok Shop product publish: POST {} (job={}, storeId={})",
                logSanitizer.sanitize(path), job.getId(), job.getStoreId());
        try {
            String responseBody = doExecuteHttp(HttpMethod.POST, url,
                    Map.of("x-tts-access-token", accessToken), requestBody);
            return mapTikTokBusinessResponse(job, product, responseBody, start);
        } catch (RestClientResponseException e) {
            String body = logSanitizer.sanitize(e.getResponseBodyAsString());
            int status = e.getStatusCode().value();
            log.warn("TikTok Shop product publish rejected: job={}, HTTP {}, body={}", job.getId(), status, body);
            return ProductPublishOutcome.rejected("HTTP_" + status,
                    "TikTok Shop 拒绝了商品发布 (HTTP " + status + "): " + extractTikTokError(e.getResponseBodyAsString()));
        }
    }

    /**
     * Build the TikTok Shop product-create body from the local product/listing.
     * {@code {"title","description","brand_name","skus":[{seller_sku,price:{amount},
     * inventory:[{quantity}]}],"main_images":[{url}]}}.
     */
    String buildTikTokProductBody(ProductEntity product, ListingContentEntity listing) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("title", resolveTitle(product, listing));
        String description = resolveDescription(listing);
        if (description != null) {
            root.put("description", description);
        }
        if (product != null && product.getBrand() != null && !product.getBrand().isBlank()) {
            root.put("brand_name", product.getBrand());
        }

        ArrayNode skus = objectMapper.createArrayNode();
        ObjectNode sku = objectMapper.createObjectNode();
        if (product != null) {
            if (product.getSku() != null && !product.getSku().isBlank()) {
                sku.put("seller_sku", product.getSku());
            }
            if (product.getPrice() != null) {
                ObjectNode price = objectMapper.createObjectNode();
                price.put("amount", normalizePrice(product.getPrice()));
                sku.set("price", price);
            }
            if (product.getInventory() != null) {
                ArrayNode inventory = objectMapper.createArrayNode();
                ObjectNode level = objectMapper.createObjectNode();
                level.put("quantity", product.getInventory());
                inventory.add(level);
                sku.set("inventory", inventory);
            }
        }
        skus.add(sku);
        root.set("skus", skus);

        String imageUrl = product != null ? trimToNull(product.getImageUrl()) : null;
        if (imageUrl != null) {
            ArrayNode images = objectMapper.createArrayNode();
            ObjectNode image = objectMapper.createObjectNode();
            image.put("url", imageUrl);
            images.add(image);
            root.set("main_images", images);
        }
        return writeJson(root);
    }

    /**
     * Map a TikTok HTTP-200 business response. TikTok wraps results as
     * {@code {"code":0,"message":"Success","data":{...}}}; a {@code code} of 0
     * means success (the created {@code data.product_id} is captured and the
     * external mapping persisted), any other code is a genuine failure surfaced
     * verbatim (honesty contract — never treated as success, never persisted).
     */
    private ProductPublishOutcome mapTikTokBusinessResponse(ProductUploadJobEntity job,
                                                            ProductEntity product,
                                                            String responseBody,
                                                            long start) {
        if (responseBody == null || responseBody.isBlank()) {
            return ProductPublishOutcome.rejected(ERROR_TIKTOK_EMPTY_RESPONSE,
                    "TikTok Shop 返回了空响应体，无法确认商品已发布。");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            return ProductPublishOutcome.rejected(ERROR_TIKTOK_MALFORMED_RESPONSE,
                    "TikTok Shop 返回了无法解析的响应体。");
        }
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            String message = root.path("message").asText("");
            log.warn("TikTok Shop product publish rejected (business code): job={}, code={}, message={}",
                    job.getId(), code, logSanitizer.sanitize(message));
            return ProductPublishOutcome.rejected("TIKTOK_" + code,
                    message == null || message.isBlank()
                            ? "TikTok Shop 拒绝了商品发布 (code " + code + ")"
                            : "TikTok Shop 拒绝了商品发布: " + message);
        }
        String productId = extractTikTokProductId(root);
        persistMapping(job.getStoreId(), PLATFORM_TIKTOK_SHOP, product, productId);
        log.info("TikTok Shop product publish success: job={}, platformProductId={}, latencyMs={}",
                job.getId(), productId, System.currentTimeMillis() - start);
        return ProductPublishOutcome.published(productId, ProductPublishOutcome.PUBLISH_MODE_DIRECT,
                "Published to TikTok Shop");
    }

    /** Extract the created product id from a TikTok {@code {"data":{"product_id":...}}} response. */
    String extractTikTokProductId(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode data = root.path("data");
        for (String key : new String[]{"product_id", "id"}) {
            JsonNode node = data.path(key);
            if (!node.isMissingNode() && !node.isNull() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        JsonNode nested = data.path("product").path("id");
        if (!nested.isMissingNode() && !nested.isNull() && !nested.asText().isBlank()) {
            return nested.asText();
        }
        return null;
    }

    private String extractTikTokError(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return "no detail";
        }
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            JsonNode message = root.path("message");
            if (!message.isMissingNode() && !message.isNull() && !message.asText().isBlank()) {
                return message.asText();
            }
            return errorBody;
        } catch (Exception e) {
            return errorBody;
        }
    }

    /** Read the first non-blank credential among the given keys. */
    private static String firstCredential(Map<String, String> creds, String... keys) {
        for (String key : keys) {
            String value = trimToNull(creds.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // HTTP execution
    // -------------------------------------------------------------------------

    /**
     * Performs the single create HTTP call. Package-private/protected so
     * instrumented subclasses in tests can verify the single-call contract and
     * simulate platform responses offline. Throws {@link RestClientResponseException}
     * on non-2xx (mapped to a rejected outcome by the caller) and other exceptions
     * on transport/credential failures (surfaced to the caller).
     */
    protected String doExecuteHttp(HttpMethod method, String url, Map<String, String> headers, String body) {
        RestClient.RequestBodySpec spec = http.method(method).uri(url);
        headers.forEach(spec::header);
        spec.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        spec.header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        spec.body(body);
        return spec.retrieve().body(String.class);
    }

    // -------------------------------------------------------------------------
    // Persistence: external_entity_mappings (mirrors the write connectors' anchor)
    // -------------------------------------------------------------------------

    /**
     * Persist the platform product id into {@code external_entity_mappings},
     * keyed on {@code (store_id, platform, internal_entity_type=product,
     * internal_entity_id=productId)}. Updates an existing mapping in place
     * (idempotent re-publish) or inserts a new one.
     */
    void persistMapping(UUID storeId, String platform, ProductEntity product, String platformProductId) {
        if (storeId == null || product == null || product.getId() == null
                || platformProductId == null || platformProductId.isBlank()) {
            return;
        }
        ExternalEntityMappingEntity existing = externalEntityMappingMapper.selectOne(
                new LambdaQueryWrapper<ExternalEntityMappingEntity>()
                        .eq(ExternalEntityMappingEntity::getStoreId, storeId)
                        .eq(ExternalEntityMappingEntity::getPlatform, platform)
                        .eq(ExternalEntityMappingEntity::getInternalEntityType, ENTITY_TYPE_PRODUCT)
                        .eq(ExternalEntityMappingEntity::getInternalEntityId, product.getId())
                        .last("LIMIT 1"));
        if (existing != null) {
            existing.setExternalEntityType(ENTITY_TYPE_PRODUCT);
            existing.setExternalEntityId(platformProductId);
            existing.setLastSyncedAt(LocalDateTime.now());
            externalEntityMappingMapper.updateById(existing);
            return;
        }
        ExternalEntityMappingEntity mapping = ExternalEntityMappingEntity.builder()
                .storeId(storeId)
                .platform(platform)
                .internalEntityType(ENTITY_TYPE_PRODUCT)
                .internalEntityId(product.getId())
                .externalEntityType(ENTITY_TYPE_PRODUCT)
                .externalEntityId(platformProductId)
                .externalData("{}")
                .lastSyncedAt(LocalDateTime.now())
                .build();
        externalEntityMappingMapper.insert(mapping);
    }

    // -------------------------------------------------------------------------
    // Connection resolution + credential decryption (mirrors OperationWriteBackImpl)
    // -------------------------------------------------------------------------

    PlatformConnectionEntity resolveConnectedConnection(UUID storeId, String platform) {
        if (storeId == null) {
            return null;
        }
        return platformConnectionMapper.selectList(
                        new LambdaQueryWrapper<PlatformConnectionEntity>()
                                .eq(PlatformConnectionEntity::getStoreId, storeId)
                                .eq(PlatformConnectionEntity::getPlatform, platform)
                                .eq(PlatformConnectionEntity::getStatus, STATUS_CONNECTED)
                                .orderByDesc(PlatformConnectionEntity::getUpdatedAt))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private Map<String, String> decryptConfig(String stored) {
        if (stored == null || stored.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> encrypted = objectMapper.readValue(
                    stored, new TypeReference<LinkedHashMap<String, String>>() {});
            Map<String, String> plain = new LinkedHashMap<>();
            encrypted.forEach((k, v) -> plain.put(k, v == null ? null : cryptoUtil.decrypt(v)));
            return plain;
        } catch (Exception e) {
            log.warn("Failed to read platform config for direct publish: {}", e.getMessage());
            return Map.of();
        }
    }

    // -------------------------------------------------------------------------
    // Field helpers
    // -------------------------------------------------------------------------

    private static String resolveTitle(ProductEntity product, ListingContentEntity listing) {
        if (listing != null && listing.getTitle() != null && !listing.getTitle().isBlank()) {
            return listing.getTitle();
        }
        if (product != null && product.getName() != null && !product.getName().isBlank()) {
            return product.getName();
        }
        return "Untitled product";
    }

    private static String resolveDescription(ListingContentEntity listing) {
        if (listing != null && listing.getDescription() != null && !listing.getDescription().isBlank()) {
            return listing.getDescription();
        }
        return null;
    }

    /** Render a price as a plain decimal string (no scientific notation / trailing noise). */
    static String normalizePrice(BigDecimal price) {
        if (price == null) {
            return "0";
        }
        return price.stripTrailingZeros().toPlainString();
    }

    static String normalizeShopDomain(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        s = s.replaceFirst("^https?://", "");
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    static String normalizeSiteUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim();
        return s.isEmpty() ? null : s;
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize publish request body", e);
        }
    }

    /** Exposed for diagnostics/tests. */
    List<String> supportedMethods() {
        return List.of(METHOD_SHOPIFY_API, METHOD_WOOCOMMERCE_API, METHOD_TIKTOK_API);
    }
}
