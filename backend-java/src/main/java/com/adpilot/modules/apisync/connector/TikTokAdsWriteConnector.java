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

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Integration <strong>skeleton</strong> {@link PlatformWriteConnector} for the
 * TikTok Ads (TikTok Marketing API v1.3) write path.
 *
 * <p>This is the write-side scaffold for TikTok advertising (campaign budget and
 * on/off state), modelled after {@link GoogleAdsWriteConnector}. It is wired the
 * same way: registered as a Spring bean with {@code platform() == "tiktok_ads"},
 * discovered by the {@code OutboxWorker} via the injected
 * {@code List<PlatformWriteConnector>}, and it makes a REAL, single HTTP call per
 * submit to the TikTok Marketing API.
 *
 * <h2>Honesty contract</h2>
 * <p>This connector NEVER fakes success. It performs a real signed HTTP call and:
 * <ul>
 *   <li>returns an honest {@code permanentReject} BEFORE any HTTP call when the
 *       change type is unsupported, the external mapping is missing, or the
 *       credentials / advertiser id are absent;</li>
 *   <li>surfaces the TikTok business {@code code}/{@code message} on an HTTP-200
 *       envelope — a non-zero {@code code} is a real failure, not a success;</li>
 *   <li>maps 4xx/5xx and transport failures to retryable / permanent rejections
 *       carrying the platform reason.</li>
 * </ul>
 *
 * <p>Because no {@code tiktok_ads} {@link com.adpilot.modules.apisync.entity.PlatformConnectionEntity}
 * can be created yet (the key is not in the connection wizard's allow-list), this
 * bean does not flip any existing store to write-capable; it provides the honest,
 * ready-to-extend write path for when TikTok Ads connections are onboarded.
 *
 * <p><strong>VERIFICATION NOTE:</strong> This targets the TikTok Marketing API at
 * <strong>{@value #BASE}</strong> version <strong>{@value #API_VERSION}</strong>.
 * The endpoint paths ({@code campaign/update/}, {@code campaign/status/update/}),
 * the {@code Access-Token} header, the {@code advertiser_id} requirement, and the
 * request-body field names are version-specific and cannot be validated offline.
 * Verify against live credentials and adjust if TikTok changes them; this mirrors
 * the {@link GoogleAdsWriteConnector} verification note.</p>
 */
@Slf4j
@Component
public class TikTokAdsWriteConnector implements PlatformWriteConnector {

    static final String PLATFORM = "tiktok_ads";

    /** TikTok Marketing API base URL. */
    static final String BASE = "https://business-api.tiktok.com";

    /** TikTok Marketing API version targeted by this connector. */
    static final String API_VERSION = "v1.3";

    // Change types routed by this connector (mirrors GoogleAdsWriteConnector).
    static final String CHANGE_BUDGET = "budget";
    static final String CHANGE_STATE = "state";

    // Error codes.
    static final String ERROR_NO_EXTERNAL_MAPPING = "NO_EXTERNAL_MAPPING";
    static final String ERROR_TOKEN_INVALID = "TOKEN_INVALID";
    static final String ERROR_UNSUPPORTED_CHANGE_TYPE = "UNSUPPORTED_CHANGE_TYPE";
    static final String ERROR_MISSING_CREDENTIALS = "MISSING_CREDENTIALS";
    static final String ERROR_MISSING_ADVERTISER_ID = "MISSING_ADVERTISER_ID";
    static final String ERROR_INVALID_REQUEST = "INVALID_REQUEST";

    /** Default backoff for rate-limit / server errors (seconds). */
    static final long DEFAULT_SERVER_ERROR_BACKOFF_SECONDS = 30L;

    private final ExternalEntityMappingMapper externalEntityMappingMapper;
    private final ObjectMapper objectMapper;
    private final PlatformLogSanitizer logSanitizer;
    private final RestClient http;

    public TikTokAdsWriteConnector(ExternalEntityMappingMapper externalEntityMappingMapper,
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
            log.warn("Unsupported TikTok Ads change type: {}", changeType);
            return PlatformWriteResult.permanentReject(ERROR_UNSUPPORTED_CHANGE_TYPE,
                    "Unsupported change type: " + changeType);
        }

        // 2. Resolve external entity id from external_entity_mappings.
        String externalEntityId = resolveExternalEntityId(change);
        if (externalEntityId == null) {
            log.warn("No external mapping found for store={} subjectType={} subjectId={}",
                    change.storeId(), change.subjectType(), change.subjectId());
            return PlatformWriteResult.permanentReject(ERROR_NO_EXTERNAL_MAPPING,
                    "No external entity mapping for " + change.subjectType() + "/" + change.subjectId());
        }

        // 3. Credential pre-checks — missing credentials means an honest failure, no HTTP.
        String accessToken = trimToNull(ctx.credential("accessToken"));
        if (accessToken == null) {
            log.warn("Missing TikTok Ads accessToken for connection {}", ctx.connectionId());
            return PlatformWriteResult.permanentReject(ERROR_MISSING_CREDENTIALS,
                    "Connection is missing the required TikTok Ads accessToken");
        }
        String advertiserId = resolveAdvertiserId(ctx);
        if (advertiserId == null) {
            log.warn("Missing TikTok Ads advertiserId for connection {}", ctx.connectionId());
            return PlatformWriteResult.permanentReject(ERROR_MISSING_ADVERTISER_ID,
                    "Connection is missing the required TikTok Ads advertiserId");
        }

        // 4. Build endpoint + body.
        String endpoint;
        String requestBody;
        try {
            endpoint = buildEndpoint(changeType);
            requestBody = buildRequestBody(changeType, advertiserId, externalEntityId, change);
        } catch (RuntimeException e) {
            return PlatformWriteResult.permanentReject(ERROR_INVALID_REQUEST,
                    "Failed to build TikTok Ads request: " + e.getMessage());
        }

        // 5. Execute exactly one HTTP call.
        try {
            String url = BASE + endpoint;
            log.info("TikTok Ads API call: POST {} (changeType={}, storeId={}, operationSource={})",
                    logSanitizer.sanitize(endpoint, ctx), changeType, change.storeId(), change.sourceId());

            String responseBody = doExecuteHttp("POST", url, accessToken, ctx, requestBody);
            long latencyMs = System.currentTimeMillis() - startTime;

            PlatformWriteResult bodyResult = mapBusinessResponse(responseBody, externalEntityId, ctx);
            log.info("TikTok Ads API response: changeType={}, externalEntityId={}, latencyMs={}, accepted={}",
                    changeType, externalEntityId, latencyMs, bodyResult.accepted());
            return bodyResult;

        } catch (RestClientResponseException e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            int status = e.getStatusCode().value();
            String errorBody = logSanitizer.sanitize(e.getResponseBodyAsString(), ctx);
            log.warn("TikTok Ads API error: HTTP {} for changeType={}, externalEntityId={}, latencyMs={}, body={}",
                    status, changeType, externalEntityId, latencyMs, errorBody);
            return mapErrorResponse(status, errorBody);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            String sanitizedMessage = logSanitizer.sanitize(e.getMessage(), ctx);
            log.error("TikTok Ads API unexpected error: changeType={}, externalEntityId={}, latencyMs={}, error={}",
                    changeType, externalEntityId, latencyMs, sanitizedMessage);
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
            case CHANGE_BUDGET, CHANGE_STATE -> true;
            default -> false;
        };
    }

    /**
     * Build the endpoint path for the given change type:
     * <ul>
     *   <li>budget → {@code POST /open_api/{ver}/campaign/update/}</li>
     *   <li>state → {@code POST /open_api/{ver}/campaign/status/update/}</li>
     * </ul>
     */
    String buildEndpoint(String changeType) {
        String prefix = "/open_api/" + API_VERSION + "/";
        return switch (changeType) {
            case CHANGE_BUDGET -> prefix + "campaign/update/";
            case CHANGE_STATE -> prefix + "campaign/status/update/";
            default -> throw new IllegalArgumentException("Unsupported change type: " + changeType);
        };
    }

    /** Build the JSON request body for the given change type. */
    String buildRequestBody(String changeType, String advertiserId, String externalEntityId,
                            PlatformChange change) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("advertiser_id", advertiserId);
            switch (changeType) {
                case CHANGE_BUDGET -> {
                    root.put("campaign_id", externalEntityId);
                    root.put("budget", parseBudget(change.recommendedValue()));
                }
                case CHANGE_STATE -> {
                    ArrayNode ids = objectMapper.createArrayNode();
                    ids.add(externalEntityId);
                    root.set("campaign_ids", ids);
                    root.put("operation_status", normalizeOperationStatus(change.recommendedValue()));
                }
                default -> throw new IllegalArgumentException("Unsupported change type: " + changeType);
            }
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
    protected String doExecuteHttp(String method, String url, String accessToken,
                                   ConnectionContext ctx, String requestBody) {
        RestClient.RequestBodySpec spec =
                http.method(org.springframework.http.HttpMethod.valueOf(method)).uri(url);
        spec.header("Access-Token", accessToken);
        spec.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        spec.header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        spec.body(requestBody);
        return spec.retrieve().body(String.class);
    }

    // -------------------------------------------------------------------------
    // Response mapping
    // -------------------------------------------------------------------------

    /**
     * Map a TikTok Marketing API HTTP-200 envelope. TikTok wraps results as
     * {@code {"code":0,"message":"OK","data":{...}}}; a {@code code} of 0 means
     * success, any other code is a genuine failure surfaced verbatim (honesty
     * contract — never treated as success).
     */
    PlatformWriteResult mapBusinessResponse(String responseBody, String externalEntityId, ConnectionContext ctx) {
        if (responseBody == null || responseBody.isBlank()) {
            return PlatformWriteResult.permanentReject("EMPTY_RESPONSE",
                    "TikTok Ads returned an empty response body");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            return PlatformWriteResult.permanentReject("MALFORMED_RESPONSE",
                    "TikTok Ads returned a malformed response body");
        }
        int code = root.path("code").asInt(-1);
        String message = root.path("message").asText("");
        if (code == 0) {
            return PlatformWriteResult.accepted(externalEntityId,
                    "Successfully submitted change to TikTok Ads");
        }
        String sanitized = logSanitizer.sanitize(message, ctx);
        return PlatformWriteResult.permanentReject("TIKTOK_" + code,
                sanitized == null || sanitized.isBlank()
                        ? "TikTok Ads rejected the change (code " + code + ")"
                        : sanitized);
    }

    /**
     * Map an HTTP error status to a structured {@link PlatformWriteResult}:
     * 429/5xx → retryable, 401/403 → TOKEN_INVALID, other 4xx → permanentReject.
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

    @Override
    public SyncState mapPlatformStatus(String platformStatus) {
        if (platformStatus == null || platformStatus.isBlank()) {
            return SyncState.RECONCILIATION_REQUIRED;
        }
        return switch (platformStatus.trim().toUpperCase()) {
            case "ENABLE", "ENABLED", "ACTIVE", "DISABLE", "DISABLED", "PAUSED", "SUCCESS" ->
                    SyncState.EFFECTIVE;
            case "PENDING", "SUBMITTED", "PROCESSING" ->
                    SyncState.SUBMITTED;
            case "FAILED", "FAILURE", "ERROR", "REJECTED" ->
                    SyncState.FAILED;
            case "DELETE", "DELETED", "CANCELLED", "CANCELED" ->
                    SyncState.CANCELLED;
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

    /** Read the advertiser id from either {@code advertiserId} or {@code advertiser_id}. */
    private String resolveAdvertiserId(ConnectionContext ctx) {
        String id = trimToNull(ctx.credential("advertiserId"));
        if (id == null) {
            id = trimToNull(ctx.credential("advertiser_id"));
        }
        return id;
    }

    /** Normalize a campaign state value to a TikTok operation status (ENABLE / DISABLE / DELETE). */
    static String normalizeOperationStatus(String value) {
        if (value == null || value.isBlank()) {
            return "DISABLE";
        }
        return switch (value.trim().toLowerCase()) {
            case "enable", "enabled", "active", "on", "resume", "resumed" -> "ENABLE";
            case "disable", "disabled", "pause", "paused", "off" -> "DISABLE";
            case "delete", "deleted", "remove", "removed", "archived" -> "DELETE";
            default -> value.trim().toUpperCase();
        };
    }

    /** Parse a budget value into a number; an invalid value is a permanent rejection. */
    static double parseBudget(String value) {
        if (value == null || value.isBlank()) {
            throw new NumberFormatException("budget is required");
        }
        return new BigDecimal(value.trim()).doubleValue();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim();
        return s.isEmpty() ? null : s;
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
