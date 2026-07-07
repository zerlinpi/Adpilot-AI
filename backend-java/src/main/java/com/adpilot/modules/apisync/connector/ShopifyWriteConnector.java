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
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

/**
 * Concrete {@link PlatformWriteConnector} implementation for the Shopify Admin
 * REST API, making independent-site inventory-update and fulfillment-mark
 * write-back go live for connected Shopify stores.
 *
 * <p>Registered as a Spring bean with {@code platform() == "shopify"}. The
 * {@code OutboxWorker} discovers it via the injected
 * {@code List<PlatformWriteConnector>} and routes Operations whose store
 * connects through Shopify to this connector. Registering this {@code @Component}
 * also flips {@code WriteCapabilityService.isWriteCapable} to {@code true} for
 * connected Shopify stores (the {@code @ConditionalOnMissingBean} fallback in
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
 *       set the inventory level via
 *       {@code POST /admin/api/{ver}/inventory_levels/set.json} with
 *       {@code inventory_item_id} + {@code location_id} + {@code available}.</li>
 *   <li>{@link #CHANGE_FULFILLMENT fulfillment} ({@code subjectType="order"}) —
 *       create a fulfillment via
 *       {@code POST /admin/api/{ver}/orders/{orderId}/fulfillments.json},
 *       carrying tracking info when the change provides a tracking number.</li>
 * </ul>
 *
 * <h2>Honesty contract (Req 13.1.3 / 4.5)</h2>
 * <p>This connector makes a REAL HTTP call to the shop's own admin endpoint using
 * the stored {@code accessToken}. It never fakes success. On rejection it returns
 * {@link PlatformWriteResult#rejected}/{@link PlatformWriteResult#permanentReject}/
 * {@link PlatformWriteResult#retryable} carrying Shopify's own reason; exceptions
 * are reserved for transport/credential failures (callers treat those as a
 * rejection with the message as the reason). The access token is sent only via the
 * {@code X-Shopify-Access-Token} header and is never logged.
 *
 * <p><strong>VERIFICATION NOTE:</strong> This targets Shopify Admin REST API
 * <strong>{@value #API_VERSION}</strong>. The version path, the
 * {@code inventory_levels/set.json} and {@code orders/{id}/fulfillments.json}
 * endpoint shapes, and their request-body field names are version-specific and
 * cannot be validated offline. In particular, the inventory set call requires the
 * variant's {@code inventory_item_id} and a {@code location_id}: this connector
 * uses the resolved external entity id as the {@code inventory_item_id} and the
 * {@code locationId} credential as the {@code location_id}. Shopify has also moved
 * fulfillment creation toward the FulfillmentOrder API in newer versions. Verify
 * all of this against live credentials and adjust if Shopify changes them; this
 * mirrors the {@link GoogleAdsWriteConnector} verification note.</p>
 */
@Slf4j
@Component
public class ShopifyWriteConnector implements PlatformWriteConnector {

    static final String PLATFORM = "shopify";

    /** Shopify Admin REST API version targeted by this connector. */
    static final String API_VERSION = "2024-01";

    // Change types routed by this connector — these are the Operation fields the
    // independent-site write path produces (field -> changeType, see OutboxWorker).
    static final String CHANGE_INVENTORY = "inventory";
    static final String CHANGE_FULFILLMENT = "fulfillment";

    // Error codes
    static final String ERROR_NO_EXTERNAL_MAPPING = "NO_EXTERNAL_MAPPING";
    static final String ERROR_TOKEN_INVALID = "TOKEN_INVALID";
    static final String ERROR_UNSUPPORTED_CHANGE_TYPE = "UNSUPPORTED_CHANGE_TYPE";
    static final String ERROR_MISSING_SHOP_DOMAIN = "MISSING_SHOP_DOMAIN";
    static final String ERROR_MISSING_LOCATION_ID = "MISSING_LOCATION_ID";

    /** Default backoff for rate-limit / server errors (seconds). */
    static final long DEFAULT_SERVER_ERROR_BACKOFF_SECONDS = 30L;

    private final ExternalEntityMappingMapper externalEntityMappingMapper;
    private final ObjectMapper objectMapper;
    private final PlatformLogSanitizer logSanitizer;
    private final RestClient http;

    public ShopifyWriteConnector(ExternalEntityMappingMapper externalEntityMappingMapper,
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
            log.warn("Unsupported Shopify change type: {}", changeType);
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

        // 3. Resolve the shop domain + access token from stored credentials
        String shop = normalizeShopDomain(ctx.credential("shopDomain"));
        if (shop.isBlank()) {
            log.warn("Missing/blank shopDomain for connection {}", ctx.connectionId());
            return PlatformWriteResult.permanentReject(ERROR_MISSING_SHOP_DOMAIN,
                    "Connection is missing a valid Shopify shopDomain");
        }
        String accessToken = ctx.credential("accessToken");

        // 4. Build endpoint + body; the inventory set call also needs a location id
        String endpoint;
        String requestBody;
        try {
            if (CHANGE_INVENTORY.equals(changeType)) {
                String locationId = trimToNull(ctx.credential("locationId"));
                if (locationId == null) {
                    log.warn("Missing/blank locationId for connection {} (required for inventory set)",
                            ctx.connectionId());
                    return PlatformWriteResult.permanentReject(ERROR_MISSING_LOCATION_ID,
                            "Connection is missing a Shopify locationId required to set inventory levels");
                }
                endpoint = buildInventoryEndpoint();
                requestBody = buildInventoryBody(externalEntityId, locationId, change.recommendedValue());
            } else {
                endpoint = buildFulfillmentEndpoint(externalEntityId);
                requestBody = buildFulfillmentBody(change.recommendedValue());
            }
        } catch (RuntimeException e) {
            // A malformed value (e.g. a non-numeric inventory quantity) is a permanent rejection.
            return PlatformWriteResult.permanentReject("INVALID_REQUEST",
                    "Failed to build Shopify request: " + e.getMessage());
        }

        // 5. Execute exactly one HTTP call
        try {
            String url = "https://" + shop + endpoint;
            log.info("Shopify API call: POST {} (changeType={}, storeId={}, operationSource={})",
                    logSanitizer.sanitize(endpoint, ctx), changeType, change.storeId(), change.sourceId());

            String responseBody = doExecuteHttp("POST", url, accessToken, ctx, requestBody);
            long latencyMs = System.currentTimeMillis() - startTime;

            String reference = extractReference(changeType, responseBody, externalEntityId);
            log.info("Shopify API success: changeType={}, externalEntityId={}, latencyMs={}, reference={}",
                    changeType, externalEntityId, latencyMs, reference);

            return PlatformWriteResult.accepted(reference,
                    "Successfully submitted " + changeType + " change to Shopify");

        } catch (RestClientResponseException e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            int status = e.getStatusCode().value();
            String errorBody = logSanitizer.sanitize(e.getResponseBodyAsString(), ctx);
            log.warn("Shopify API error: HTTP {} for changeType={}, externalEntityId={}, latencyMs={}, body={}",
                    status, changeType, externalEntityId, latencyMs, errorBody);
            return mapErrorResponse(status, errorBody);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            String sanitizedMessage = logSanitizer.sanitize(e.getMessage(), ctx);
            log.error("Shopify API unexpected error: changeType={}, externalEntityId={}, latencyMs={}, error={}",
                    changeType, externalEntityId, latencyMs, sanitizedMessage);
            // Transport failure → retryable with backoff.
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

    /** {@code POST /admin/api/{ver}/inventory_levels/set.json}. */
    String buildInventoryEndpoint() {
        return "/admin/api/" + API_VERSION + "/inventory_levels/set.json";
    }

    /** {@code POST /admin/api/{ver}/orders/{orderId}/fulfillments.json}. */
    String buildFulfillmentEndpoint(String externalOrderId) {
        return "/admin/api/" + API_VERSION + "/orders/" + externalOrderId + "/fulfillments.json";
    }

    /**
     * Build the inventory-set body: {@code {"location_id":..,"inventory_item_id":..,"available":..}}.
     * The available quantity is parsed from the change's recommended value.
     */
    String buildInventoryBody(String inventoryItemId, String locationId, String quantity) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("location_id", parseLong(locationId));
            root.put("inventory_item_id", parseLong(inventoryItemId));
            root.put("available", parseInt(quantity));
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build inventory body: " + e.getMessage(), e);
        }
    }

    /**
     * Build the fulfillment body. When the change carries a tracking number it is
     * attached via {@code tracking_info.number}; otherwise a fulfillment with no
     * tracking is created. {@code notify_customer} is left to the platform default.
     */
    String buildFulfillmentBody(String trackingNumber) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode fulfillment = objectMapper.createObjectNode();
            String tracking = trimToNull(trackingNumber);
            if (tracking != null) {
                ObjectNode trackingInfo = objectMapper.createObjectNode();
                trackingInfo.put("number", tracking);
                fulfillment.set("tracking_info", trackingInfo);
            }
            root.set("fulfillment", fulfillment);
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
        RestClient.RequestBodySpec spec = http.method(org.springframework.http.HttpMethod.valueOf(method)).uri(url);
        spec.header("X-Shopify-Access-Token", accessToken);
        spec.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        spec.header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        spec.body(requestBody);
        return spec.retrieve().body(String.class);
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
                    "Access token rejected (HTTP " + status + "); re-authorization may be required");
        }
        String reason = extractErrorMessage(errorBody);
        return PlatformWriteResult.permanentReject("HTTP_" + status,
                reason != null ? reason : "Request rejected (HTTP " + status + ")");
    }

    // -------------------------------------------------------------------------
    // Status mapping (Req 55.4)
    // -------------------------------------------------------------------------

    /**
     * Map a Shopify fulfillment/inventory status to a single {@link SyncState}.
     * Shopify inventory and fulfillment writes are synchronous, so an accepted
     * submission is effective; explicit statuses cover what a status read may
     * surface. Unknown statuses defer to the SPI default (reconciliation).
     */
    @Override
    public SyncState mapPlatformStatus(String platformStatus) {
        if (platformStatus == null || platformStatus.isBlank()) {
            return SyncState.RECONCILIATION_REQUIRED;
        }
        return switch (platformStatus.trim().toLowerCase()) {
            case "success", "open", "fulfilled", "complete", "completed", "active" -> SyncState.EFFECTIVE;
            case "pending", "submitted" -> SyncState.SUBMITTED;
            case "failure", "error", "cancelled", "canceled" -> SyncState.FAILED;
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

    /** Strip scheme + trailing slash from a stored shop domain. */
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim();
        return s.isEmpty() ? null : s;
    }

    private static long parseLong(String value) {
        return Long.parseLong(value.trim());
    }

    private static int parseInt(String value) {
        if (value == null || value.isBlank()) {
            throw new NumberFormatException("inventory quantity is required");
        }
        // Tolerate decimal-looking integers (e.g. "10.0") by truncating the fraction.
        String s = value.trim();
        int dot = s.indexOf('.');
        if (dot >= 0) {
            s = s.substring(0, dot);
        }
        return Integer.parseInt(s);
    }

    /** Extract a platform reference from a successful response, falling back to the external id. */
    private String extractReference(String changeType, String responseBody, String externalEntityId) {
        if (responseBody == null || responseBody.isBlank()) {
            return externalEntityId;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (CHANGE_FULFILLMENT.equals(changeType)) {
                JsonNode id = root.path("fulfillment").path("id");
                if (!id.isMissingNode() && !id.isNull()) {
                    return id.asText();
                }
            } else {
                JsonNode level = root.path("inventory_level");
                JsonNode itemId = level.path("inventory_item_id");
                if (!itemId.isMissingNode() && !itemId.isNull()) {
                    return itemId.asText();
                }
            }
            return externalEntityId;
        } catch (Exception e) {
            return externalEntityId;
        }
    }

    /** Extract a human-readable error message from a Shopify error response body. */
    private String extractErrorMessage(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            JsonNode errors = root.path("errors");
            if (errors.isTextual()) {
                return errors.asText();
            }
            if (errors.isObject() && errors.fields().hasNext()) {
                // {"errors":{"base":["message"]}} or {"errors":{"field":"message"}}
                var entry = errors.fields().next();
                JsonNode val = entry.getValue();
                if (val.isArray() && !val.isEmpty()) {
                    return entry.getKey() + ": " + val.get(0).asText();
                }
                return entry.getKey() + ": " + val.asText();
            }
            if (root.has("error")) {
                return root.get("error").asText(null);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
