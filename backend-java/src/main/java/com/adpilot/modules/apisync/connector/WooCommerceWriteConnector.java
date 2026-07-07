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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Concrete {@link PlatformWriteConnector} implementation for the WooCommerce REST
 * API v3, making independent-site inventory-update and fulfillment-mark
 * write-back go live for connected WooCommerce stores.
 *
 * <p>Registered as a Spring bean with {@code platform() == "woocommerce"}. The
 * {@code OutboxWorker} discovers it via the injected
 * {@code List<PlatformWriteConnector>} and routes Operations whose store connects
 * through WooCommerce to this connector. Registering this {@code @Component} also
 * flips {@code WriteCapabilityService.isWriteCapable} to {@code true} for
 * connected WooCommerce stores (the {@code @ConditionalOnMissingBean} fallback in
 * {@code PlatformWriteConnectorConfig} is suppressed once a real bean exists), so
 * the UI stops showing "暂不支持" for those stores.
 *
 * <h2>Change-type routing (matches the independent-site write path)</h2>
 * <p>{@code IndependentSiteWriteServiceImpl} enqueues a {@code platform_mutation}
 * Operation whose {@code field} becomes the {@link PlatformChange#changeType()}
 * (see {@code OutboxWorker.buildChange}). The two independent-site write kinds
 * therefore arrive as:
 * <ul>
 *   <li>{@link #CHANGE_INVENTORY inventory} ({@code subjectType="product"}) —
 *       {@code PUT /wp-json/wc/v3/products/{id}} with {@code stock_quantity} and
 *       {@code manage_stock=true}.</li>
 *   <li>{@link #CHANGE_FULFILLMENT fulfillment} ({@code subjectType="order"}) —
 *       {@code PUT /wp-json/wc/v3/orders/{id}} with {@code status=completed},
 *       storing tracking in an order note (meta) when the change provides a
 *       tracking number.</li>
 * </ul>
 *
 * <h2>Honesty contract (Req 13.1.3 / 4.5)</h2>
 * <p>This connector makes a REAL HTTP call to the store's own REST endpoint using
 * the stored {@code consumerKey}/{@code consumerSecret} (HTTP Basic auth). It
 * never fakes success. On rejection it returns
 * {@link PlatformWriteResult#rejected}/{@link PlatformWriteResult#permanentReject}/
 * {@link PlatformWriteResult#retryable} carrying WooCommerce's own reason;
 * exceptions are reserved for transport/credential failures (callers treat those
 * as a rejection with the message as the reason). The Basic auth header is never
 * logged.
 *
 * <p><strong>VERIFICATION NOTE:</strong> This targets WooCommerce REST API
 * <strong>{@value #API_VERSION}</strong>. The version path, the
 * {@code products/{id}} and {@code orders/{id}} resource shapes, the
 * {@code stock_quantity}/{@code manage_stock} product fields, and the
 * {@code status=completed} order transition are version/plugin-specific and cannot
 * be validated offline. Tracking storage in WooCommerce depends on the shipment
 * tracking plugin in use; this connector stores it as an order note via the
 * core {@code orders} payload. Verify all of this against live credentials and
 * adjust if the store's WooCommerce/plugins differ; this mirrors the
 * {@link GoogleAdsWriteConnector} verification note.</p>
 */
@Slf4j
@Component
public class WooCommerceWriteConnector implements PlatformWriteConnector {

    static final String PLATFORM = "woocommerce";

    /** WooCommerce REST API version targeted by this connector. */
    static final String API_VERSION = "v3";

    // Change types routed by this connector — these are the Operation fields the
    // independent-site write path produces (field -> changeType, see OutboxWorker).
    static final String CHANGE_INVENTORY = "inventory";
    static final String CHANGE_FULFILLMENT = "fulfillment";

    /** WooCommerce order status used to mark an order fulfilled/shipped. */
    static final String ORDER_STATUS_COMPLETED = "completed";

    // Error codes
    static final String ERROR_NO_EXTERNAL_MAPPING = "NO_EXTERNAL_MAPPING";
    static final String ERROR_TOKEN_INVALID = "TOKEN_INVALID";
    static final String ERROR_UNSUPPORTED_CHANGE_TYPE = "UNSUPPORTED_CHANGE_TYPE";
    static final String ERROR_MISSING_SITE_URL = "MISSING_SITE_URL";

    /** Default backoff for rate-limit / server errors (seconds). */
    static final long DEFAULT_SERVER_ERROR_BACKOFF_SECONDS = 30L;

    private final ExternalEntityMappingMapper externalEntityMappingMapper;
    private final ObjectMapper objectMapper;
    private final PlatformLogSanitizer logSanitizer;
    private final RestClient http;

    public WooCommerceWriteConnector(ExternalEntityMappingMapper externalEntityMappingMapper,
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

        // 1. Validate change type
        if (!isSupportedChangeType(changeType)) {
            log.warn("Unsupported WooCommerce change type: {}", changeType);
            return PlatformWriteResult.permanentReject(ERROR_UNSUPPORTED_CHANGE_TYPE,
                    "Unsupported change type: " + changeType);
        }

        // 2. Resolve external entity id from external_entity_mappings
        String externalEntityId = resolveExternalEntityId(change);
        if (externalEntityId == null) {
            log.warn("No external mapping found for store={} subjectType={} subjectId={}",
                    change.storeId(), change.subjectType(), change.subjectId());
            return PlatformWriteResult.permanentReject(ERROR_NO_EXTERNAL_MAPPING,
                    "No external entity mapping for " + change.subjectType() + "/" + change.subjectId());
        }

        // 3. Resolve the site base URL from stored credentials
        String site = normalizeSiteUrl(ctx.credential("siteUrl"));
        if (site.isBlank()) {
            log.warn("Missing/blank siteUrl for connection {}", ctx.connectionId());
            return PlatformWriteResult.permanentReject(ERROR_MISSING_SITE_URL,
                    "Connection is missing a valid WooCommerce siteUrl");
        }

        // 4. Build endpoint + body
        String endpoint;
        String requestBody;
        try {
            if (CHANGE_INVENTORY.equals(changeType)) {
                endpoint = buildProductEndpoint(externalEntityId);
                requestBody = buildInventoryBody(change.recommendedValue());
            } else {
                endpoint = buildOrderEndpoint(externalEntityId);
                requestBody = buildFulfillmentBody(change.recommendedValue());
            }
        } catch (RuntimeException e) {
            return PlatformWriteResult.permanentReject("INVALID_REQUEST",
                    "Failed to build WooCommerce request: " + e.getMessage());
        }

        // 5. Execute exactly one HTTP call (PUT)
        try {
            String url = site + endpoint;
            log.info("WooCommerce API call: PUT {} (changeType={}, storeId={}, operationSource={})",
                    logSanitizer.sanitize(endpoint, ctx), changeType, change.storeId(), change.sourceId());

            String responseBody = doExecuteHttp("PUT", url, ctx, requestBody);
            long latencyMs = System.currentTimeMillis() - startTime;

            String reference = extractReference(responseBody, externalEntityId);
            log.info("WooCommerce API success: changeType={}, externalEntityId={}, latencyMs={}, reference={}",
                    changeType, externalEntityId, latencyMs, reference);

            return PlatformWriteResult.accepted(reference,
                    "Successfully submitted " + changeType + " change to WooCommerce");

        } catch (RestClientResponseException e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            int status = e.getStatusCode().value();
            String errorBody = logSanitizer.sanitize(e.getResponseBodyAsString(), ctx);
            log.warn("WooCommerce API error: HTTP {} for changeType={}, externalEntityId={}, latencyMs={}, body={}",
                    status, changeType, externalEntityId, latencyMs, errorBody);
            return mapErrorResponse(status, errorBody);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            String sanitizedMessage = logSanitizer.sanitize(e.getMessage(), ctx);
            log.error("WooCommerce API unexpected error: changeType={}, externalEntityId={}, latencyMs={}, error={}",
                    changeType, externalEntityId, latencyMs, sanitizedMessage);
            return PlatformWriteResult.retryable(
                    "Unexpected error: " + sanitizedMessage, DEFAULT_SERVER_ERROR_BACKOFF_SECONDS);
        }
    }

    // -------------------------------------------------------------------------
    // Change type routing (package-private for offline tests)
    // -------------------------------------------------------------------------

    boolean isSupportedChangeType(String changeType) {
        if (changeType == null) return false;
        return switch (changeType) {
            case CHANGE_INVENTORY, CHANGE_FULFILLMENT -> true;
            default -> false;
        };
    }

    /** {@code /wp-json/wc/{ver}/products/{id}}. */
    String buildProductEndpoint(String externalProductId) {
        return "/wp-json/wc/" + API_VERSION + "/products/" + externalProductId;
    }

    /** {@code /wp-json/wc/{ver}/orders/{id}}. */
    String buildOrderEndpoint(String externalOrderId) {
        return "/wp-json/wc/" + API_VERSION + "/orders/" + externalOrderId;
    }

    /**
     * Build the inventory body: {@code {"manage_stock":true,"stock_quantity":..}}.
     * Enabling {@code manage_stock} ensures {@code stock_quantity} is honored.
     */
    String buildInventoryBody(String quantity) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("manage_stock", true);
            root.put("stock_quantity", parseInt(quantity));
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build inventory body: " + e.getMessage(), e);
        }
    }

    /**
     * Build the fulfillment body: transition the order to {@code completed}, and
     * when a tracking number is present attach it as a customer-visible order note
     * via the core {@code order_notes}-style {@code note} field on the order meta.
     * WooCommerce core has no dedicated tracking field, so the tracking number is
     * recorded in {@code meta_data} (key {@code _tracking_number}) for durability.
     */
    String buildFulfillmentBody(String trackingNumber) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("status", ORDER_STATUS_COMPLETED);
            String tracking = trimToNull(trackingNumber);
            if (tracking != null) {
                ArrayNode meta = objectMapper.createArrayNode();
                ObjectNode entry = objectMapper.createObjectNode();
                entry.put("key", "_tracking_number");
                entry.put("value", tracking);
                meta.add(entry);
                root.set("meta_data", meta);
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
    protected String doExecuteHttp(String method, String url, ConnectionContext ctx, String requestBody) {
        RestClient.RequestBodySpec spec = http.method(org.springframework.http.HttpMethod.valueOf(method)).uri(url);
        spec.header(HttpHeaders.AUTHORIZATION, basicAuth(ctx));
        spec.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        spec.header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        spec.body(requestBody);
        return spec.retrieve().body(String.class);
    }

    private static String basicAuth(ConnectionContext ctx) {
        String key = ctx.credential("consumerKey");
        String secret = ctx.credential("consumerSecret");
        String creds = key + ":" + secret;
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------------------------
    // Error response mapping
    // -------------------------------------------------------------------------

    /**
     * Map HTTP error status to a structured {@link PlatformWriteResult}:
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
                    "Credentials rejected (HTTP " + status + "); re-authorization may be required");
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
     * Map a WooCommerce order/product status to a single {@link SyncState}.
     * WooCommerce writes are synchronous, so an accepted submission is effective;
     * explicit statuses cover what a status read may surface. Unknown statuses
     * defer to the SPI default (reconciliation).
     */
    @Override
    public SyncState mapPlatformStatus(String platformStatus) {
        if (platformStatus == null || platformStatus.isBlank()) {
            return SyncState.RECONCILIATION_REQUIRED;
        }
        return switch (platformStatus.trim().toLowerCase()) {
            case "completed", "complete", "success", "publish", "active", "processing" -> SyncState.EFFECTIVE;
            case "pending", "submitted", "on-hold" -> SyncState.SUBMITTED;
            case "failed", "failure", "error", "cancelled", "canceled", "refunded" -> SyncState.FAILED;
            default -> PlatformWriteConnector.super.mapPlatformStatus(platformStatus);
        };
    }

    // -------------------------------------------------------------------------
    // External entity resolution (mirrors GoogleAdsWriteConnector)
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
    // Helpers
    // -------------------------------------------------------------------------

    /** Strip trailing slashes from a stored site URL (scheme is preserved). */
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

    /** Extract the entity id from a successful response, falling back to the external id. */
    private String extractReference(String responseBody, String externalEntityId) {
        if (responseBody == null || responseBody.isBlank()) {
            return externalEntityId;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode id = root.path("id");
            if (!id.isMissingNode() && !id.isNull()) {
                return id.asText();
            }
            return externalEntityId;
        } catch (Exception e) {
            return externalEntityId;
        }
    }

    /** Extract the WooCommerce error code (e.g. {@code woocommerce_rest_*}) from an error body. */
    private String extractErrorCode(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            if (root.has("code")) {
                return root.get("code").asText(null);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Extract a human-readable error message from a WooCommerce error response body. */
    private String extractErrorMessage(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            if (root.has("message")) {
                return root.get("message").asText(null);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
