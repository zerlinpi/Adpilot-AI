package com.adpilot.modules.apisync.connector;

import com.adpilot.common.config.HttpClientFactory;
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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Concrete {@link PlatformWriteConnector} implementation for the TikTok Shop
 * Open (Partner) API v202309, making TikTok Shop product direct-write, inventory
 * updates, and order shipment write-back go live for connected TikTok shops.
 *
 * <p>Registered as a Spring bean with {@code platform() == "tiktok_shop"} (the
 * existing platform key declared in {@link PlatformConnector#SUPPORTED} and used
 * by {@link TikTokConnector} on the read side). The {@code OutboxWorker}
 * discovers it via the injected {@code List<PlatformWriteConnector>} and routes
 * Operations whose store connects through TikTok Shop to this connector.
 * Registering this {@code @Component} also flips
 * {@code WriteCapabilityService.isWriteCapable} to {@code true} for connected
 * TikTok shops (the {@code @ConditionalOnMissingBean} fallback in
 * {@code PlatformWriteConnectorConfig} is suppressed once a real bean exists), so
 * the UI can stop showing "暂不支持" for those stores.
 *
 * <h2>Change-type routing</h2>
 * <p>The Operation {@code field} (falling back to {@code entityType}) becomes the
 * {@link PlatformChange#changeType()} (see {@code OutboxWorker.buildChange}). The
 * three supported TikTok Shop write kinds are:
 * <ul>
 *   <li>{@link #CHANGE_PRODUCT product} ({@code subjectType="product"}) — create
 *       a product via {@code POST /product/202309/products}. The change's
 *       recommended value carries the product payload (a JSON object is sent
 *       verbatim; any other value is wrapped as {@code {"title": value}}).</li>
 *   <li>{@link #CHANGE_INVENTORY inventory} ({@code subjectType="product"}) —
 *       update stock via
 *       {@code POST /product/202309/products/{productId}/inventory/update} with
 *       {@code skus[].inventory[].quantity}.</li>
 *   <li>{@link #CHANGE_FULFILLMENT fulfillment} ({@code subjectType="order"}) —
 *       mark an order shipped via
 *       {@code POST /fulfillment/202309/orders/{orderId}/packages} carrying the
 *       {@code tracking_number} (and optional {@code shipping_provider_id}).</li>
 * </ul>
 *
 * <h2>Honesty contract (Req 13.1.3 / 4.5)</h2>
 * <p>This connector makes a REAL, signed HTTP call to TikTok's Open API using the
 * stored {@code appKey}/{@code appSecret}/{@code accessToken}. It NEVER fakes
 * success:
 * <ul>
 *   <li>missing credentials / shop identifier / external mapping &rarr; an honest
 *       {@code permanentReject} is returned BEFORE any HTTP call (no network, no
 *       fake success);</li>
 *   <li>TikTok returns HTTP 200 with a business {@code code} in the body — a
 *       non-zero {@code code} is a FAILURE and is surfaced as a rejection carrying
 *       TikTok's own {@code code}/{@code message}, never treated as success;</li>
 *   <li>4xx/5xx and transport failures are mapped to retryable / permanent
 *       rejections carrying the platform reason.</li>
 * </ul>
 * The access token is sent only via the {@code x-tts-access-token} header and is
 * never logged; all logged request/response text is run through
 * {@link PlatformLogSanitizer}.
 *
 * <p><strong>VERIFICATION NOTE:</strong> This targets TikTok Shop Open API
 * <strong>{@value #API_VERSION}</strong>. The version path, the
 * {@code products}, {@code products/{id}/inventory/update} and
 * {@code orders/{id}/packages} endpoint shapes, their request-body field names,
 * the HMAC-SHA256 request signing, and the shop-scoped {@code shop_cipher}
 * requirement are version-specific and cannot be validated offline. In
 * particular the inventory call needs the variant SKU id and a warehouse id:
 * this connector uses the resolved external entity id as the SKU id and an
 * optional {@code warehouseId} credential as the warehouse id. Verify all of
 * this against live credentials and adjust if TikTok changes them; this mirrors
 * the {@link GoogleAdsWriteConnector} / {@link ShopifyWriteConnector}
 * verification notes.</p>
 */
@Slf4j
@Component
public class TikTokShopWriteConnector implements PlatformWriteConnector {

    static final String PLATFORM = "tiktok_shop";

    /** TikTok Shop Open API version targeted by this connector (shared signer). */
    static final String API_VERSION = TikTokShopApiSigner.API_VERSION;

    /** TikTok Shop Open API base URL (shared signer; matches {@link TikTokConnector}). */
    static final String BASE = TikTokShopApiSigner.BASE;

    // Change types routed by this connector.
    static final String CHANGE_PRODUCT = "product";
    static final String CHANGE_INVENTORY = "inventory";
    static final String CHANGE_FULFILLMENT = "fulfillment";

    // Error codes (mirrors the ERROR_* style of the other connectors).
    static final String ERROR_NO_EXTERNAL_MAPPING = "NO_EXTERNAL_MAPPING";
    static final String ERROR_TOKEN_INVALID = "TOKEN_INVALID";
    static final String ERROR_UNSUPPORTED_CHANGE_TYPE = "UNSUPPORTED_CHANGE_TYPE";
    static final String ERROR_MISSING_CREDENTIALS = "MISSING_CREDENTIALS";
    static final String ERROR_MISSING_SHOP = "MISSING_SHOP";
    static final String ERROR_INVALID_REQUEST = "INVALID_REQUEST";

    /** Default backoff for rate-limit / server errors (seconds). */
    static final long DEFAULT_SERVER_ERROR_BACKOFF_SECONDS = 30L;

    private final ExternalEntityMappingMapper externalEntityMappingMapper;
    private final ObjectMapper objectMapper;
    private final PlatformLogSanitizer logSanitizer;
    private final RestClient http;

    public TikTokShopWriteConnector(ExternalEntityMappingMapper externalEntityMappingMapper,
                                    ObjectMapper objectMapper,
                                    PlatformLogSanitizer logSanitizer,
                                    HttpClientFactory httpClientFactory) {
        this.externalEntityMappingMapper = externalEntityMappingMapper;
        this.objectMapper = objectMapper;
        this.logSanitizer = logSanitizer;
        this.http = httpClientFactory.timeoutRestClientBuilder().build();
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public PlatformWriteResult submit(ConnectionContext ctx, PlatformChange change) {
        long startTime = System.currentTimeMillis();
        String changeType = change.changeType();

        // 1. Validate change type — no HTTP for unsupported kinds.
        if (!isSupportedChangeType(changeType)) {
            log.warn("Unsupported TikTok Shop change type: {}", changeType);
            return PlatformWriteResult.permanentReject(ERROR_UNSUPPORTED_CHANGE_TYPE,
                    "Unsupported change type: " + changeType);
        }

        // 2. Resolve external entity id. Product CREATE has no external id yet;
        //    inventory / fulfillment require an existing mapping.
        String externalEntityId = resolveExternalEntityId(change);
        boolean requiresExternalId = !CHANGE_PRODUCT.equals(changeType);
        if (requiresExternalId && externalEntityId == null) {
            log.warn("No external mapping found for store={} subjectType={} subjectId={}",
                    change.storeId(), change.subjectType(), change.subjectId());
            return PlatformWriteResult.permanentReject(ERROR_NO_EXTERNAL_MAPPING,
                    "No external entity mapping for " + change.subjectType() + "/" + change.subjectId());
        }

        // 3. Credential pre-checks — missing credentials means an honest failure, no HTTP.
        String appKey = trimToNull(ctx.credential("appKey"));
        String appSecret = trimToNull(ctx.credential("appSecret"));
        String accessToken = trimToNull(ctx.credential("accessToken"));
        if (appKey == null || appSecret == null || accessToken == null) {
            log.warn("Missing TikTok Shop credentials for connection {} (appKey/appSecret/accessToken required)",
                    ctx.connectionId());
            return PlatformWriteResult.permanentReject(ERROR_MISSING_CREDENTIALS,
                    "Connection is missing required TikTok Shop credentials (appKey/appSecret/accessToken)");
        }
        String shopCipher = resolveShopCipher(ctx);
        String shopId = resolveShopId(ctx);
        if (shopCipher == null && shopId == null) {
            log.warn("Missing TikTok Shop identifier (shopCipher/shopId) for connection {}", ctx.connectionId());
            return PlatformWriteResult.permanentReject(ERROR_MISSING_SHOP,
                    "Connection is missing a TikTok Shop identifier (shopCipher or shopId)");
        }

        // 4. Build endpoint path + body.
        String path;
        String requestBody;
        try {
            switch (changeType) {
                case CHANGE_PRODUCT -> {
                    path = buildProductCreatePath();
                    requestBody = buildProductBody(change.recommendedValue());
                }
                case CHANGE_INVENTORY -> {
                    path = buildInventoryPath(externalEntityId);
                    requestBody = buildInventoryBody(externalEntityId, ctx.credential("warehouseId"),
                            change.recommendedValue());
                }
                default -> {
                    path = buildFulfillmentPath(externalEntityId);
                    requestBody = buildFulfillmentBody(change.recommendedValue(),
                            ctx.credential("shippingProviderId"));
                }
            }
        } catch (RuntimeException e) {
            return PlatformWriteResult.permanentReject(ERROR_INVALID_REQUEST,
                    "Failed to build TikTok Shop request: " + e.getMessage());
        }

        // 5. Sign and execute exactly one HTTP call.
        try {
            String signedUrl = buildSignedUrl(appKey, appSecret, path, shopCipher, shopId, requestBody);
            log.info("TikTok Shop API call: POST {} (changeType={}, storeId={}, operationSource={})",
                    logSanitizer.sanitize(path, ctx), changeType, change.storeId(), change.sourceId());

            String responseBody = doExecuteHttp("POST", signedUrl, accessToken, ctx, requestBody);
            long latencyMs = System.currentTimeMillis() - startTime;

            // TikTok returns HTTP 200 with a business code; a non-zero code is a real failure.
            PlatformWriteResult bodyResult = mapBusinessResponse(changeType, responseBody, externalEntityId, ctx);
            log.info("TikTok Shop API response: changeType={}, externalEntityId={}, latencyMs={}, accepted={}",
                    changeType, externalEntityId, latencyMs, bodyResult.accepted());
            return bodyResult;

        } catch (RestClientResponseException e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            int status = e.getStatusCode().value();
            String errorBody = logSanitizer.sanitize(e.getResponseBodyAsString(), ctx);
            log.warn("TikTok Shop API error: HTTP {} for changeType={}, externalEntityId={}, latencyMs={}, body={}",
                    status, changeType, externalEntityId, latencyMs, errorBody);
            return mapErrorResponse(status, errorBody);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            String sanitizedMessage = logSanitizer.sanitize(e.getMessage(), ctx);
            log.error("TikTok Shop API unexpected error: changeType={}, externalEntityId={}, latencyMs={}, error={}",
                    changeType, externalEntityId, latencyMs, sanitizedMessage);
            // Transport failure → retryable with backoff.
            return PlatformWriteResult.retryable(
                    "Unexpected error: " + sanitizedMessage, DEFAULT_SERVER_ERROR_BACKOFF_SECONDS);
        }
    }

    // -------------------------------------------------------------------------
    // Change-type routing (package-private for offline tests)
    // -------------------------------------------------------------------------

    boolean isSupportedChangeType(String changeType) {
        if (changeType == null) return false;
        return switch (changeType) {
            case CHANGE_PRODUCT, CHANGE_INVENTORY, CHANGE_FULFILLMENT -> true;
            default -> false;
        };
    }

    /** {@code POST /product/{ver}/products} (shared signer). */
    String buildProductCreatePath() {
        return TikTokShopApiSigner.productCreatePath();
    }

    /** {@code POST /product/{ver}/products/{id}/inventory/update}. */
    String buildInventoryPath(String externalProductId) {
        return "/product/" + API_VERSION + "/products/" + externalProductId + "/inventory/update";
    }

    /** {@code POST /fulfillment/{ver}/orders/{id}/packages}. */
    String buildFulfillmentPath(String externalOrderId) {
        return "/fulfillment/" + API_VERSION + "/orders/" + externalOrderId + "/packages";
    }

    /**
     * Build the product create body. When the recommended value is a JSON object
     * (a full product payload supplied by the caller) it is sent verbatim;
     * otherwise the value is treated as the product title.
     */
    String buildProductBody(String recommendedValue) {
        try {
            String value = trimToNull(recommendedValue);
            if (value != null && (value.startsWith("{"))) {
                JsonNode parsed = objectMapper.readTree(value);
                if (parsed.isObject()) {
                    return objectMapper.writeValueAsString(parsed);
                }
            }
            ObjectNode root = objectMapper.createObjectNode();
            root.put("title", value == null ? "" : value);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build product body: " + e.getMessage(), e);
        }
    }

    /**
     * Build the inventory update body:
     * {@code {"skus":[{"id":<skuId>,"inventory":[{"warehouse_id":..,"quantity":N}]}]}}.
     * The warehouse id is included only when a {@code warehouseId} credential is present.
     */
    String buildInventoryBody(String skuId, String warehouseId, String quantity) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode skus = objectMapper.createArrayNode();
            ObjectNode sku = objectMapper.createObjectNode();
            sku.put("id", skuId);
            ArrayNode inventory = objectMapper.createArrayNode();
            ObjectNode level = objectMapper.createObjectNode();
            String warehouse = trimToNull(warehouseId);
            if (warehouse != null) {
                level.put("warehouse_id", warehouse);
            }
            level.put("quantity", parseInt(quantity));
            inventory.add(level);
            sku.set("inventory", inventory);
            skus.add(sku);
            root.set("skus", skus);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build inventory body: " + e.getMessage(), e);
        }
    }

    /**
     * Build the fulfillment (mark-shipped) body:
     * {@code {"tracking_number":..,"shipping_provider_id":..}}. The tracking
     * number is required by TikTok to mark an order shipped; a missing/blank
     * tracking number is a permanent rejection (built as an invalid request).
     */
    String buildFulfillmentBody(String trackingNumber, String shippingProviderId) {
        String tracking = trimToNull(trackingNumber);
        if (tracking == null) {
            throw new IllegalStateException("tracking number is required to mark a TikTok Shop order shipped");
        }
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("tracking_number", tracking);
            String provider = trimToNull(shippingProviderId);
            if (provider != null) {
                root.put("shipping_provider_id", provider);
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build fulfillment body: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // HTTP execution
    // -------------------------------------------------------------------------

    /**
     * Performs the single HTTP call. Package-private/protected so instrumented
     * subclasses in tests can verify the single-call contract offline.
     */
    protected String doExecuteHttp(String method, String url, String accessToken,
                                   ConnectionContext ctx, String requestBody) {
        RestClient.RequestBodySpec spec =
                http.method(org.springframework.http.HttpMethod.valueOf(method)).uri(url);
        spec.header("x-tts-access-token", accessToken);
        spec.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        spec.header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        spec.body(requestBody);
        return spec.retrieve().body(String.class);
    }

    /**
     * Build the fully-signed request URL: common params ({@code app_key},
     * {@code timestamp}, optional {@code shop_cipher}/{@code shop_id}) plus the
     * HMAC-SHA256 {@code sign}. Package-private so offline tests can assert the
     * shape without performing a network call.
     */
    String buildSignedUrl(String appKey, String appSecret, String path,
                          String shopCipher, String shopId, String body) {
        return TikTokShopApiSigner.buildSignedUrl(appKey, appSecret, path, shopCipher, shopId, body);
    }

    // -------------------------------------------------------------------------
    // Response mapping
    // -------------------------------------------------------------------------

    /**
     * Map a TikTok HTTP-200 business response. TikTok wraps results as
     * {@code {"code":0,"message":"Success","data":{...}}}; a {@code code} of 0
     * means success, any other code is a genuine failure surfaced verbatim
     * (honesty contract — never treated as success).
     */
    PlatformWriteResult mapBusinessResponse(String changeType, String responseBody,
                                            String externalEntityId, ConnectionContext ctx) {
        if (responseBody == null || responseBody.isBlank()) {
            // No body to confirm success → force reconciliation rather than fake success.
            return PlatformWriteResult.permanentReject("EMPTY_RESPONSE",
                    "TikTok Shop returned an empty response body");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            return PlatformWriteResult.permanentReject("MALFORMED_RESPONSE",
                    "TikTok Shop returned a malformed response body");
        }
        int code = root.path("code").asInt(-1);
        String message = root.path("message").asText("");
        if (code == 0) {
            String reference = extractReference(changeType, root, externalEntityId);
            return PlatformWriteResult.accepted(reference,
                    "Successfully submitted " + changeType + " change to TikTok Shop");
        }
        String sanitized = logSanitizer.sanitize(message, ctx);
        return PlatformWriteResult.permanentReject("TIKTOK_" + code,
                sanitized == null || sanitized.isBlank()
                        ? "TikTok Shop rejected the change (code " + code + ")"
                        : sanitized);
    }

    /**
     * Map an HTTP error status to a structured {@link PlatformWriteResult}:
     * <ul>
     *   <li>429 → retryable with backoff</li>
     *   <li>5xx → retryable with backoff</li>
     *   <li>401/403 → permanentReject TOKEN_INVALID</li>
     *   <li>other 4xx → permanentReject with the platform reason</li>
     * </ul>
     */
    PlatformWriteResult mapErrorResponse(int status, String errorBody) {
        if (status == 429) {
            return PlatformWriteResult.retryable("Rate limited (HTTP 429)", DEFAULT_SERVER_ERROR_BACKOFF_SECONDS);
        }
        if (status >= 500) {
            return PlatformWriteResult.retryable(
                    "Server error (HTTP " + status + ")", DEFAULT_SERVER_ERROR_BACKOFF_SECONDS);
        }
        if (status == 401 || status == 403) {
            return PlatformWriteResult.permanentReject(ERROR_TOKEN_INVALID,
                    "Access token rejected (HTTP " + status + "); re-authorization may be required");
        }
        String code = extractErrorCode(errorBody);
        String reason = extractErrorMessage(errorBody);
        return PlatformWriteResult.permanentReject(
                code != null ? code : "HTTP_" + status,
                reason != null ? reason : "Request rejected (HTTP " + status + ")");
    }

    // -------------------------------------------------------------------------
    // Status mapping (Req 55.4)
    // -------------------------------------------------------------------------

    /**
     * Map a TikTok Shop product/order status to a single {@link SyncState}.
     * TikTok writes are synchronous, so an accepted submission is effective;
     * explicit statuses cover what a status read may surface. Unknown statuses
     * defer to the SPI default (reconciliation).
     */
    @Override
    public SyncState mapPlatformStatus(String platformStatus) {
        if (platformStatus == null || platformStatus.isBlank()) {
            return SyncState.RECONCILIATION_REQUIRED;
        }
        return switch (platformStatus.trim().toLowerCase()) {
            case "success", "activate", "active", "live", "shipped", "fulfilled", "completed", "complete" ->
                    SyncState.EFFECTIVE;
            case "pending", "submitted", "processing", "awaiting_shipment" ->
                    SyncState.SUBMITTED;
            case "failed", "failure", "error", "rejected", "cancelled", "canceled", "freeze", "frozen" ->
                    SyncState.FAILED;
            default -> PlatformWriteConnector.super.mapPlatformStatus(platformStatus);
        };
    }

    // -------------------------------------------------------------------------
    // External entity resolution (mirrors GoogleAdsWriteConnector / ShopifyWriteConnector)
    // -------------------------------------------------------------------------

    private String resolveExternalEntityId(PlatformChange change) {
        if (change.subjectId() == null || change.subjectId().isBlank()) {
            return null;
        }
        UUID storeId = change.storeId();
        String subjectType = change.subjectType();
        String subjectId = change.subjectId();

        UUID internalEntityId;
        try {
            internalEntityId = UUID.fromString(subjectId);
        } catch (IllegalArgumentException e) {
            // subjectId might already be an external id in some flows.
            return resolveByExternalId(storeId, subjectType, subjectId);
        }

        LambdaQueryWrapper<ExternalEntityMappingEntity> query = new LambdaQueryWrapper<>();
        query.eq(ExternalEntityMappingEntity::getStoreId, storeId)
             .eq(ExternalEntityMappingEntity::getPlatform, PLATFORM)
             .eq(ExternalEntityMappingEntity::getInternalEntityType, subjectType)
             .eq(ExternalEntityMappingEntity::getInternalEntityId, internalEntityId)
             .last("LIMIT 1");

        ExternalEntityMappingEntity mapping = externalEntityMappingMapper.selectOne(query);
        return mapping != null ? mapping.getExternalEntityId() : null;
    }

    private String resolveByExternalId(UUID storeId, String subjectType, String subjectId) {
        LambdaQueryWrapper<ExternalEntityMappingEntity> query = new LambdaQueryWrapper<>();
        query.eq(ExternalEntityMappingEntity::getStoreId, storeId)
             .eq(ExternalEntityMappingEntity::getPlatform, PLATFORM)
             .eq(ExternalEntityMappingEntity::getExternalEntityType, subjectType)
             .eq(ExternalEntityMappingEntity::getExternalEntityId, subjectId)
             .last("LIMIT 1");

        ExternalEntityMappingEntity mapping = externalEntityMappingMapper.selectOne(query);
        return mapping != null ? mapping.getExternalEntityId() : subjectId;
    }

    // -------------------------------------------------------------------------
    // Signing (mirrors TikTokConnector's documented HMAC-SHA256 algorithm)
    // -------------------------------------------------------------------------

    /**
     * TikTok Open API signature: HMAC-SHA256 with key = appSecret over
     * {@code appSecret + path + (each sorted key+value, excluding sign &
     * access_token) + body + appSecret}, hex-encoded. Package-private for tests.
     */
    String sign(String appSecret, String path, TreeMap<String, String> params, String body) {
        return TikTokShopApiSigner.sign(appSecret, path, params, body);
    }

    private String query(TreeMap<String, String> params) {
        return TikTokShopApiSigner.query(params);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Read the shop cipher from either {@code shopCipher} or {@code shop_cipher}. */
    private String resolveShopCipher(ConnectionContext ctx) {
        String cipher = trimToNull(ctx.credential("shopCipher"));
        if (cipher == null) {
            cipher = trimToNull(ctx.credential("shop_cipher"));
        }
        return cipher;
    }

    /** Read the shop id from either {@code shopId} or {@code shop_id}. */
    private String resolveShopId(ConnectionContext ctx) {
        String id = trimToNull(ctx.credential("shopId"));
        if (id == null) {
            id = trimToNull(ctx.credential("shop_id"));
        }
        return id;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim();
        return s.isEmpty() ? null : s;
    }

    private static int parseInt(String value) {
        if (value == null || value.isBlank()) {
            throw new NumberFormatException("inventory quantity is required");
        }
        String s = value.trim();
        int dot = s.indexOf('.');
        if (dot >= 0) {
            s = s.substring(0, dot);
        }
        return Integer.parseInt(s);
    }

    /** Extract a platform reference from a successful response, falling back to the external id. */
    private String extractReference(String changeType, JsonNode root, String externalEntityId) {
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return externalEntityId;
        }
        // Product create → data.product_id; fulfillment → data.package_id; else fall back.
        for (String key : new String[]{"product_id", "package_id", "id"}) {
            JsonNode node = data.path(key);
            if (!node.isMissingNode() && !node.isNull() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return externalEntityId;
    }

    /** Extract a TikTok error code from an HTTP error body. */
    private String extractErrorCode(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            JsonNode code = root.path("code");
            if (!code.isMissingNode() && !code.isNull()) {
                return "TIKTOK_" + code.asText();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Extract a human-readable error message from a TikTok error response body. */
    private String extractErrorMessage(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            JsonNode message = root.path("message");
            if (!message.isMissingNode() && !message.isNull() && !message.asText().isBlank()) {
                return message.asText();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
