package com.adpilot.modules.apisync.connector;

import com.adpilot.common.config.HttpClientFactory;
import com.adpilot.modules.apisync.entity.ExternalEntityMappingEntity;
import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformWriteResult;
import com.adpilot.modules.apisync.support.PlatformLogSanitizer;
import com.adpilot.modules.advertising.operation.SyncState;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Concrete {@link PlatformWriteConnector} implementation for the Google Ads API.
 *
 * <p>Registered as a Spring bean with {@code platform() == "google_ads"}. The
 * {@code OutboxWorker} and {@code StatusPoller} discover it via the injected
 * {@code List<PlatformWriteConnector>} and route Operations with
 * {@code platform = "google_ads"} to this connector. Discovery is purely by the
 * {@code @Component} annotation; no explicit bean registration in
 * {@link PlatformWriteConnectorConfig} is required (that config only supplies a
 * fallback empty list when no connector bean exists, suppressed once a real bean
 * is present).
 *
 * <p>Responsibilities (mirroring {@link AmazonAdsWriteConnector}):
 * <ul>
 *   <li>Translate a {@link PlatformChange} into the correct Google Ads
 *       {@code :mutate} call by {@code changeType}: keyword/criterion bid
 *       ({@code bid} → {@code adGroupCriteria:mutate}), campaign budget
 *       ({@code budget} → {@code campaignBudgets:mutate}), campaign state
 *       ({@code state} → {@code campaigns:mutate}).</li>
 *   <li>Resolve the internal entity's external id from
 *       {@code external_entity_mappings} (reject {@code NO_EXTERNAL_MAPPING} when
 *       absent), exactly like {@link AmazonAdsWriteConnector}.</li>
 *   <li>Obtain a fresh OAuth access token via {@link GoogleAdsTokenClient}.</li>
 *   <li>Perform exactly one HTTP call per submit.</li>
 *   <li>Map results: 2xx &rarr; accepted, 429 &rarr; retryable, 5xx &rarr;
 *       retryable, 4xx &rarr; permanentReject, 401 &rarr; TOKEN_INVALID.</li>
 *   <li>Sanitize all logged request/response text via
 *       {@link PlatformLogSanitizer} (never log secrets).</li>
 * </ul>
 *
 * <p><strong>VERIFICATION NOTE:</strong> This targets Google Ads API
 * <strong>{@value #API_VERSION}</strong> over REST. The version path, mutate
 * endpoint names, {@code updateMask} field names, and the micros convention for
 * monetary values are version-specific and cannot be validated offline. Verify
 * against live credentials and adjust if Google changes them; this mirrors the
 * {@link TikTokConnector} verification note.</p>
 */
@Slf4j
@Component
public class GoogleAdsWriteConnector implements PlatformWriteConnector {

    static final String PLATFORM = "google_ads";

    /** Google Ads REST API version targeted by this connector. */
    static final String API_VERSION = "v17";

    static final String BASE = "https://googleads.googleapis.com";

    // Change types routed by this connector
    static final String CHANGE_BID = "bid";
    static final String CHANGE_BUDGET = "budget";
    static final String CHANGE_STATE = "state";

    // Error codes
    static final String ERROR_NO_EXTERNAL_MAPPING = "NO_EXTERNAL_MAPPING";
    static final String ERROR_TOKEN_INVALID = "TOKEN_INVALID";
    static final String ERROR_UNSUPPORTED_CHANGE_TYPE = "UNSUPPORTED_CHANGE_TYPE";
    static final String ERROR_INVALID_CUSTOMER_ID = "INVALID_CUSTOMER_ID";

    // Default backoff for server errors (seconds)
    static final long DEFAULT_SERVER_ERROR_BACKOFF_SECONDS = 30L;

    /** Micros multiplier: Google Ads represents money/bids as integer micros. */
    private static final BigDecimal MICROS = BigDecimal.valueOf(1_000_000L);

    private final GoogleAdsTokenClient tokenClient;
    private final ExternalEntityMappingMapper externalEntityMappingMapper;
    private final ObjectMapper objectMapper;
    private final PlatformLogSanitizer logSanitizer;
    private final RestClient http;

    public GoogleAdsWriteConnector(GoogleAdsTokenClient tokenClient,
                                   ExternalEntityMappingMapper externalEntityMappingMapper,
                                   ObjectMapper objectMapper,
                                   PlatformLogSanitizer logSanitizer,
                                   HttpClientFactory httpClientFactory) {
        this.tokenClient = tokenClient;
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
            log.warn("Unsupported change type: {}", changeType);
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

        // 3. Resolve the Google Ads customer id
        String customerId = GoogleAdsConnector.normalizeCustomerId(ctx.credential("customerId"));
        if (customerId.isBlank()) {
            log.warn("Missing/blank customerId for connection {}", ctx.connectionId());
            return PlatformWriteResult.permanentReject(ERROR_INVALID_CUSTOMER_ID,
                    "Connection is missing a valid Google Ads customerId");
        }

        // 4. Obtain a fresh OAuth access token
        String accessToken;
        try {
            accessToken = tokenClient.fetchAccessToken(ctx);
        } catch (ReauthRequiredException e) {
            log.warn("Google OAuth re-auth required for connection {}: {}",
                    ctx.connectionId(), e.getReason());
            return PlatformWriteResult.permanentReject(ERROR_TOKEN_INVALID,
                    "Connection token is invalid/expired; re-authorization required");
        }

        // 5. Build and execute exactly one HTTP call
        try {
            String endpoint = buildEndpoint(changeType, customerId);
            String requestBody = buildRequestBody(changeType, customerId, externalEntityId, change);
            String url = BASE + endpoint;

            log.info("Google Ads API call: POST {} (changeType={}, storeId={}, operationSource={})",
                    logSanitizer.sanitize(endpoint, ctx), changeType, change.storeId(), change.sourceId());

            String responseBody = doExecuteHttp(url, accessToken, ctx, requestBody);
            long latencyMs = System.currentTimeMillis() - startTime;

            String resourceName = extractResourceName(responseBody);

            log.info("Google Ads API success: changeType={}, externalEntityId={}, latencyMs={}, resourceName={}",
                    changeType, externalEntityId, latencyMs, resourceName);

            // 2xx → accepted; carry the platform resource name as the reference
            return PlatformWriteResult.acceptedAmazon(
                    resourceName != null ? resourceName : externalEntityId,
                    externalEntityId,
                    "Successfully submitted " + changeType + " change");

        } catch (RestClientResponseException e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            int status = e.getStatusCode().value();
            String errorBody = logSanitizer.sanitize(e.getResponseBodyAsString(), ctx);

            log.warn("Google Ads API error: HTTP {} for changeType={}, externalEntityId={}, latencyMs={}, body={}",
                    status, changeType, externalEntityId, latencyMs, errorBody);

            return mapErrorResponse(status, errorBody);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            String sanitizedMessage = logSanitizer.sanitize(e.getMessage(), ctx);
            log.error("Google Ads API unexpected error: changeType={}, externalEntityId={}, latencyMs={}, error={}",
                    changeType, externalEntityId, latencyMs, sanitizedMessage);

            // Treat unexpected errors as retryable with backoff
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
            case CHANGE_BID, CHANGE_BUDGET, CHANGE_STATE -> true;
            default -> false;
        };
    }

    /**
     * Build the {@code :mutate} endpoint path for the given change type.
     * Routes:
     * <ul>
     *   <li>bid → POST /vXX/customers/{customerId}/adGroupCriteria:mutate</li>
     *   <li>budget → POST /vXX/customers/{customerId}/campaignBudgets:mutate</li>
     *   <li>state → POST /vXX/customers/{customerId}/campaigns:mutate</li>
     * </ul>
     */
    String buildEndpoint(String changeType, String customerId) {
        String prefix = "/" + API_VERSION + "/customers/" + customerId + "/";
        return switch (changeType) {
            case CHANGE_BID -> prefix + "adGroupCriteria:mutate";
            case CHANGE_BUDGET -> prefix + "campaignBudgets:mutate";
            case CHANGE_STATE -> prefix + "campaigns:mutate";
            default -> throw new IllegalArgumentException("Unsupported change type: " + changeType);
        };
    }

    /**
     * Build the JSON {@code mutate} request body for the given change type, using
     * a single update operation with the appropriate {@code updateMask}.
     */
    String buildRequestBody(String changeType, String customerId, String externalEntityId,
                            PlatformChange change) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode operation = objectMapper.createObjectNode();
            ObjectNode update = objectMapper.createObjectNode();

            switch (changeType) {
                case CHANGE_BID -> {
                    update.put("resourceName",
                            "customers/" + customerId + "/adGroupCriteria/" + externalEntityId);
                    update.put("cpcBidMicros", toMicros(change.recommendedValue()));
                    operation.put("updateMask", "cpcBidMicros");
                }
                case CHANGE_BUDGET -> {
                    update.put("resourceName",
                            "customers/" + customerId + "/campaignBudgets/" + externalEntityId);
                    update.put("amountMicros", toMicros(change.recommendedValue()));
                    operation.put("updateMask", "amountMicros");
                }
                case CHANGE_STATE -> {
                    update.put("resourceName",
                            "customers/" + customerId + "/campaigns/" + externalEntityId);
                    update.put("status", normalizeCampaignStatus(change.recommendedValue()));
                    operation.put("updateMask", "status");
                }
                default -> throw new IllegalArgumentException("Unsupported change type: " + changeType);
            }

            operation.set("update", update);
            root.putArray("operations").add(operation);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build request body for changeType=" + changeType, e);
        }
    }

    // -------------------------------------------------------------------------
    // HTTP execution
    // -------------------------------------------------------------------------

    /**
     * Performs the single HTTP call. Package-private/protected so instrumented
     * subclasses in tests can verify the single-call contract offline.
     */
    protected String doExecuteHttp(String url, String accessToken, ConnectionContext ctx, String requestBody) {
        RestClient.RequestBodySpec spec = http.post().uri(url);
        spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        spec.header("developer-token", ctx.credential("developerToken"));
        String loginCustomerId = resolveLoginCustomerId(ctx);
        if (loginCustomerId != null) {
            spec.header("login-customer-id", loginCustomerId);
        }
        spec.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        spec.header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        spec.body(requestBody);
        return spec.retrieve().body(String.class);
    }

    private String resolveLoginCustomerId(ConnectionContext ctx) {
        String raw = ctx.credential("loginCustomerId");
        if (raw == null || raw.isBlank()) {
            raw = ctx.credential("managerCustomerId");
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = GoogleAdsConnector.normalizeCustomerId(raw);
        return normalized.isBlank() ? null : normalized;
    }

    // -------------------------------------------------------------------------
    // Error response mapping
    // -------------------------------------------------------------------------

    /**
     * Map HTTP error status to a structured {@link PlatformWriteResult}:
     * <ul>
     *   <li>429 → retryable with backoff</li>
     *   <li>500/502/503 (and other 5xx) → retryable with backoff</li>
     *   <li>401 → permanentReject TOKEN_INVALID</li>
     *   <li>other 4xx → permanentReject with the platform error code</li>
     * </ul>
     */
    PlatformWriteResult mapErrorResponse(int status, String errorBody) {
        return switch (status) {
            case 429 -> PlatformWriteResult.retryable(
                    "Rate limited (HTTP 429)", DEFAULT_SERVER_ERROR_BACKOFF_SECONDS);
            case 500, 502, 503 -> PlatformWriteResult.retryable(
                    "Server error (HTTP " + status + ")", DEFAULT_SERVER_ERROR_BACKOFF_SECONDS);
            case 401 -> PlatformWriteResult.permanentReject(ERROR_TOKEN_INVALID,
                    "Access token rejected (HTTP 401); re-authorization may be required");
            default -> {
                if (status >= 500) {
                    yield PlatformWriteResult.retryable(
                            "Server error (HTTP " + status + ")", DEFAULT_SERVER_ERROR_BACKOFF_SECONDS);
                }
                String errorCode = extractErrorCode(errorBody);
                String reason = extractErrorMessage(errorBody);
                yield PlatformWriteResult.permanentReject(
                        errorCode != null ? errorCode : "HTTP_" + status,
                        reason != null ? reason : "Request rejected (HTTP " + status + ")");
            }
        };
    }

    // -------------------------------------------------------------------------
    // Status mapping (Req 55.4)
    // -------------------------------------------------------------------------

    /**
     * Map a Google Ads campaign/entity status (or an internal lifecycle status)
     * to a single {@link SyncState}. Google Ads mutate calls are synchronous, so
     * an accepted submission is effective; the explicit statuses cover the
     * entity-level states a status read may surface. Unknown statuses fall back to
     * the default mapping (which forces reconciliation).
     */
    @Override
    public SyncState mapPlatformStatus(String platformStatus) {
        if (platformStatus == null || platformStatus.isBlank()) {
            return SyncState.RECONCILIATION_REQUIRED;
        }
        return switch (platformStatus.trim().toUpperCase()) {
            case "ENABLED", "ACTIVE", "APPLIED", "EFFECTIVE", "SUCCESS", "SUCCEEDED" ->
                    SyncState.EFFECTIVE;
            case "PAUSED" ->
                    SyncState.EFFECTIVE;
            case "REMOVED", "DISABLED" ->
                    SyncState.EFFECTIVE;
            case "PENDING", "SUBMITTED", "QUEUED", "ACCEPTED" ->
                    SyncState.SUBMITTED;
            case "FAILED", "FAILURE", "ERROR", "REJECTED", "INVALID" ->
                    SyncState.FAILED;
            case "CANCELLED", "CANCELED" ->
                    SyncState.CANCELLED;
            // Defer anything unrecognized to the SPI default (reconciliation).
            default -> PlatformWriteConnector.super.mapPlatformStatus(platformStatus);
        };
    }

    // -------------------------------------------------------------------------
    // External entity resolution (mirrors AmazonAdsWriteConnector)
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

    /** Normalize a campaign state value to a Google Ads campaign status enum. */
    static String normalizeCampaignStatus(String value) {
        if (value == null || value.isBlank()) {
            return "PAUSED";
        }
        return switch (value.trim().toLowerCase()) {
            case "enabled", "enable", "active", "on", "resume", "resumed" -> "ENABLED";
            case "paused", "pause", "off" -> "PAUSED";
            case "removed", "remove", "archived", "delete", "deleted" -> "REMOVED";
            default -> value.trim().toUpperCase();
        };
    }

    /** Convert a decimal currency/bid value to Google Ads integer micros. */
    static long toMicros(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return new BigDecimal(value.trim())
                    .multiply(MICROS)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return 0L;
        }
    }

    /** Extract the mutated resource name from a successful mutate response. */
    private String extractResourceName(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode results = root.path("results");
            if (results.isArray() && !results.isEmpty()) {
                JsonNode first = results.get(0);
                if (first.has("resourceName")) {
                    return first.get("resourceName").asText(null);
                }
            }
            if (root.has("resourceName")) {
                return root.get("resourceName").asText(null);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Extract a structured error code from a Google Ads error response body. */
    private String extractErrorCode(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            JsonNode error = root.path("error");
            if (error.has("status")) {
                return error.get("status").asText(null);
            }
            // Google Ads detailed errors: error.details[].errors[].errorCode
            JsonNode details = error.path("details");
            if (details.isArray() && !details.isEmpty()) {
                JsonNode errors = details.get(0).path("errors");
                if (errors.isArray() && !errors.isEmpty()) {
                    JsonNode errorCode = errors.get(0).path("errorCode");
                    if (errorCode.isObject() && errorCode.fields().hasNext()) {
                        return errorCode.fields().next().getValue().asText(null);
                    }
                }
            }
            if (error.has("code")) {
                return error.get("code").asText(null);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Extract a human-readable error message from a Google Ads error response body. */
    private String extractErrorMessage(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            JsonNode error = root.path("error");
            if (error.has("message")) {
                return error.get("message").asText(null);
            }
            JsonNode details = error.path("details");
            if (details.isArray() && !details.isEmpty()) {
                JsonNode errors = details.get(0).path("errors");
                if (errors.isArray() && !errors.isEmpty() && errors.get(0).has("message")) {
                    return errors.get(0).get("message").asText(null);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
