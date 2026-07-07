package com.adpilot.modules.apisync.connector;

import com.adpilot.common.config.HttpClientFactory;
import com.adpilot.modules.apisync.entity.ExternalEntityMappingEntity;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformVerifyResult;
import com.adpilot.modules.apisync.model.PlatformWriteResult;
import com.adpilot.modules.apisync.model.SubmissionMetadata;
import com.adpilot.modules.apisync.oauth.AmazonAdsProperties;
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
 * Concrete {@link PlatformWriteConnector} implementation for the Amazon Ads API.
 *
 * <p>Registered as a Spring bean with {@code platform() == "amazon_ads"}. The
 * {@code OutboxWorker} and {@code StatusPoller} discover it via the injected
 * {@code List<PlatformWriteConnector>} and route Operations with
 * {@code platform = "amazon_ads"} to this connector.
 *
 * <p>Responsibilities (Requirements 1, 14.2, 15, 16):
 * <ul>
 *   <li>Translate a {@link PlatformChange} into the correct Amazon Ads API call
 *       by {@code changeType}: keyword bid ({@code bid}), campaign budget
 *       ({@code budget}), campaign state ({@code state}), keyword creation
 *       ({@code keyword}), negative keyword creation ({@code negative_keyword}).</li>
 *   <li>Resolve the internal entity's Amazon external id from
 *       {@code external_entity_mappings} (reject {@code NO_EXTERNAL_MAPPING} when
 *       absent).</li>
 *   <li>Obtain a fresh LWA access token via {@link AmazonAdsTokenService}.</li>
 *   <li>Perform exactly one HTTP call per submit.</li>
 *   <li>Map results: 2xx → acceptedAmazon, 429 → retryable, 500/502/503 →
 *       retryable, 400/422 → permanentReject, invalid token → TOKEN_INVALID.</li>
 *   <li>Use {@link AmazonAdsRateLimiter#tryAcquire(String)} before making the
 *       call.</li>
 *   <li>Log API calls via {@link PlatformLogSanitizer} (Req 15.5).</li>
 * </ul>
 *
 * <p><b>Requirements:</b> 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.10, 1.11, 1.12,
 * 14.2, 16.1, 16.2, 15.5</p>
 */
@Slf4j
@Component
public class AmazonAdsWriteConnector implements PlatformWriteConnector {

    static final String PLATFORM = "amazon_ads";

    // Change types routed by this connector
    static final String CHANGE_BID = "bid";
    static final String CHANGE_BUDGET = "budget";
    static final String CHANGE_STATE = "state";
    static final String CHANGE_KEYWORD = "keyword";
    static final String CHANGE_NEGATIVE_KEYWORD = "negative_keyword";

    // Error codes
    static final String ERROR_NO_EXTERNAL_MAPPING = "NO_EXTERNAL_MAPPING";
    static final String ERROR_TOKEN_INVALID = "TOKEN_INVALID";
    static final String ERROR_RATE_LIMITED = "RATE_LIMITED";
    static final String ERROR_UNSUPPORTED_CHANGE_TYPE = "UNSUPPORTED_CHANGE_TYPE";

    // Default backoff for server errors (seconds)
    static final long DEFAULT_SERVER_ERROR_BACKOFF_SECONDS = 30L;

    private final AmazonAdsTokenService tokenService;
    private final AmazonAdsRateLimiter rateLimiter;
    private final ExternalEntityMappingMapper externalEntityMappingMapper;
    private final PlatformConnectionMapper connectionMapper;
    private final AmazonAdsProperties amazonAdsProperties;
    private final ObjectMapper objectMapper;
    private final PlatformLogSanitizer logSanitizer;
    private final RestClient http;

    public AmazonAdsWriteConnector(AmazonAdsTokenService tokenService,
                                   AmazonAdsRateLimiter rateLimiter,
                                   ExternalEntityMappingMapper externalEntityMappingMapper,
                                   PlatformConnectionMapper connectionMapper,
                                   AmazonAdsProperties amazonAdsProperties,
                                   ObjectMapper objectMapper,
                                   PlatformLogSanitizer logSanitizer,
                                   HttpClientFactory httpClientFactory) {
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
        this.externalEntityMappingMapper = externalEntityMappingMapper;
        this.connectionMapper = connectionMapper;
        this.amazonAdsProperties = amazonAdsProperties;
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

        // 2. Resolve external entity ID from external_entity_mappings (Req 14.2)
        String externalEntityId = resolveExternalEntityId(change);
        if (externalEntityId == null) {
            log.warn("No external mapping found for store={} subjectType={} subjectId={}",
                    change.storeId(), change.subjectType(), change.subjectId());
            return PlatformWriteResult.permanentReject(ERROR_NO_EXTERNAL_MAPPING,
                    "No external entity mapping for " + change.subjectType() + "/" + change.subjectId());
        }

        // 3. Resolve the connection's profile ID for rate limiting
        String profileId = resolveProfileId(ctx);

        // 4. Check rate limiter (Req 15.1, 15.2) — never blocks
        if (!rateLimiter.tryAcquire(profileId)) {
            log.info("Rate limited for profile {}", profileId);
            return PlatformWriteResult.retryable(ERROR_RATE_LIMITED,
                    rateLimiter.nextAvailableInstant(profileId).getEpochSecond()
                            - System.currentTimeMillis() / 1000 + 1);
        }

        // 5. Obtain a fresh LWA access token (Req 1.7)
        String accessToken;
        try {
            accessToken = tokenService.getAccessToken(ctx.connectionId());
        } catch (TokenExpiredException e) {
            log.warn("Token expired for connection {}: {}", ctx.connectionId(), e.getMessage());
            return PlatformWriteResult.permanentReject(ERROR_TOKEN_INVALID,
                    "Connection token is invalid/expired; re-authorization required");
        }

        // 6. Build and execute exactly one HTTP call (Req 16.1, 16.2)
        String apiHost = resolveApiHost(ctx);
        try {
            String endpoint = buildEndpoint(changeType, externalEntityId, change);
            String requestBody = buildRequestBody(changeType, externalEntityId, change);
            String url = apiHost + endpoint;

            log.info("Amazon Ads API call: {} {} (changeType={}, storeId={}, operationSource={})",
                    resolveHttpMethod(changeType), logSanitizer.sanitize(endpoint, ctx),
                    changeType, change.storeId(), change.sourceId());

            String responseBody = executeHttpCall(changeType, url, accessToken, profileId, requestBody, ctx);
            long latencyMs = System.currentTimeMillis() - startTime;

            // Parse response and extract Amazon request id
            String amazonRequestId = extractRequestId(responseBody);

            log.info("Amazon Ads API success: changeType={}, externalEntityId={}, latencyMs={}, requestId={}",
                    changeType, externalEntityId, latencyMs, amazonRequestId);

            // 2xx → accepted with structured fields (Req 1.11)
            return PlatformWriteResult.acceptedAmazon(amazonRequestId, externalEntityId,
                    "Successfully submitted " + changeType + " change");

        } catch (RestClientResponseException e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            int status = e.getStatusCode().value();
            String errorBody = logSanitizer.sanitize(e.getResponseBodyAsString(), ctx);

            log.warn("Amazon Ads API error: HTTP {} for changeType={}, externalEntityId={}, latencyMs={}, body={}",
                    status, changeType, externalEntityId, latencyMs, errorBody);

            return mapErrorResponse(status, errorBody, profileId);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            String sanitizedMessage = logSanitizer.sanitize(e.getMessage(), ctx);
            log.error("Amazon Ads API unexpected error: changeType={}, externalEntityId={}, latencyMs={}, error={}",
                    changeType, externalEntityId, latencyMs, sanitizedMessage);

            // Treat unexpected errors as retryable with backoff
            return PlatformWriteResult.retryable(
                    "Unexpected error: " + sanitizedMessage, DEFAULT_SERVER_ERROR_BACKOFF_SECONDS);
        }
    }

    // -------------------------------------------------------------------------
    // Change type routing
    // -------------------------------------------------------------------------

    private boolean isSupportedChangeType(String changeType) {
        if (changeType == null) return false;
        return switch (changeType) {
            case CHANGE_BID, CHANGE_BUDGET, CHANGE_STATE,
                 CHANGE_KEYWORD, CHANGE_NEGATIVE_KEYWORD -> true;
            default -> false;
        };
    }

    /**
     * Build the API endpoint path for the given change type.
     * Routes:
     * - bid → PUT /sp/keywords (keyword bid update)
     * - budget → PUT /sp/campaigns (campaign budget update)
     * - state → PUT /sp/campaigns (campaign state update)
     * - keyword → POST /sp/keywords (keyword creation)
     * - negative_keyword → POST /sp/negativeKeywords (negative keyword creation)
     */
    private String buildEndpoint(String changeType, String externalEntityId, PlatformChange change) {
        return switch (changeType) {
            case CHANGE_BID -> "/sp/keywords";
            case CHANGE_BUDGET -> "/sp/campaigns";
            case CHANGE_STATE -> "/sp/campaigns";
            case CHANGE_KEYWORD -> "/sp/keywords";
            case CHANGE_NEGATIVE_KEYWORD -> "/sp/negativeKeywords";
            default -> throw new IllegalArgumentException("Unsupported change type: " + changeType);
        };
    }

    /**
     * Build the JSON request body for the given change type.
     */
    private String buildRequestBody(String changeType, String externalEntityId, PlatformChange change) {
        try {
            ObjectNode root = objectMapper.createObjectNode();

            switch (changeType) {
                case CHANGE_BID -> {
                    // Keyword bid update: { "keywords": [{ "keywordId": ..., "bid": ... }] }
                    ObjectNode keyword = objectMapper.createObjectNode();
                    keyword.put("keywordId", externalEntityId);
                    keyword.put("bid", parseDecimal(change.recommendedValue()));
                    root.putArray("keywords").add(keyword);
                }
                case CHANGE_BUDGET -> {
                    // Campaign budget update: { "campaigns": [{ "campaignId": ..., "budget": { "budget": ... } }] }
                    ObjectNode campaign = objectMapper.createObjectNode();
                    campaign.put("campaignId", externalEntityId);
                    ObjectNode budget = objectMapper.createObjectNode();
                    budget.put("budget", parseDecimal(change.recommendedValue()));
                    campaign.set("budget", budget);
                    root.putArray("campaigns").add(campaign);
                }
                case CHANGE_STATE -> {
                    // Campaign state change: { "campaigns": [{ "campaignId": ..., "state": "enabled"|"paused" }] }
                    ObjectNode campaign = objectMapper.createObjectNode();
                    campaign.put("campaignId", externalEntityId);
                    campaign.put("state", change.recommendedValue());
                    root.putArray("campaigns").add(campaign);
                }
                case CHANGE_KEYWORD -> {
                    // Keyword creation: { "keywords": [{ "adGroupId": ..., "keywordText": ..., "matchType": ..., "bid": ... }] }
                    ObjectNode keyword = objectMapper.createObjectNode();
                    // For keyword creation, externalEntityId is the ad group's external id
                    keyword.put("adGroupId", externalEntityId);
                    // Parse the recommended value as JSON containing keyword details
                    JsonNode details = parseKeywordDetails(change.recommendedValue());
                    if (details != null) {
                        if (details.has("keywordText")) keyword.put("keywordText", details.get("keywordText").asText());
                        if (details.has("matchType")) keyword.put("matchType", details.get("matchType").asText());
                        if (details.has("bid")) keyword.put("bid", details.get("bid").asDouble());
                    }
                    root.putArray("keywords").add(keyword);
                }
                case CHANGE_NEGATIVE_KEYWORD -> {
                    // Negative keyword creation: { "negativeKeywords": [{ "campaignId": ..., "keywordText": ..., "matchType": ... }] }
                    ObjectNode negKeyword = objectMapper.createObjectNode();
                    negKeyword.put("campaignId", externalEntityId);
                    JsonNode details = parseKeywordDetails(change.recommendedValue());
                    if (details != null) {
                        if (details.has("keywordText")) negKeyword.put("keywordText", details.get("keywordText").asText());
                        if (details.has("matchType")) negKeyword.put("matchType", details.get("matchType").asText());
                        if (details.has("adGroupId")) negKeyword.put("adGroupId", details.get("adGroupId").asText());
                    }
                    root.putArray("negativeKeywords").add(negKeyword);
                }
                default -> throw new IllegalArgumentException("Unsupported change type: " + changeType);
            }

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build request body for changeType=" + changeType, e);
        }
    }

    /**
     * Determine the HTTP method for the given change type.
     */
    private String resolveHttpMethod(String changeType) {
        return switch (changeType) {
            case CHANGE_BID, CHANGE_BUDGET, CHANGE_STATE -> "PUT";
            case CHANGE_KEYWORD, CHANGE_NEGATIVE_KEYWORD -> "POST";
            default -> "UNKNOWN";
        };
    }

    /**
     * Execute exactly one HTTP call to the Amazon Ads API (Req 16.1, 16.2).
     * Uses PUT for updates and POST for creations.
     * Delegates to {@link #doExecuteHttp} which is package-private to allow testing.
     */
    private String executeHttpCall(String changeType, String url, String accessToken,
                                   String profileId, String requestBody, ConnectionContext ctx) {
        return doExecuteHttp(changeType, url, accessToken, profileId, requestBody, ctx);
    }

    /**
     * Performs the actual HTTP call. Package-private (protected) to allow instrumented
     * subclasses in property tests to verify single-call contract (Req 16.1, 16.2).
     */
    protected String doExecuteHttp(String changeType, String url, String accessToken,
                                   String profileId, String requestBody, ConnectionContext ctx) {
        RestClient.RequestBodySpec spec;

        if (isCreation(changeType)) {
            spec = http.post().uri(url);
        } else {
            spec = http.put().uri(url);
        }

        // Amazon Ads required headers (Req 8.1.2)
        spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        spec.header("Amazon-Advertising-API-ClientId", amazonAdsProperties.getClientId());
        spec.header("Amazon-Advertising-API-Scope", profileId);
        spec.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        spec.header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        spec.body(requestBody);

        return spec.retrieve().body(String.class);
    }

    private boolean isCreation(String changeType) {
        return CHANGE_KEYWORD.equals(changeType) || CHANGE_NEGATIVE_KEYWORD.equals(changeType);
    }

    // -------------------------------------------------------------------------
    // Error response mapping (Req 1.8, 1.9, 1.11, 1.12)
    // -------------------------------------------------------------------------

    /**
     * Map HTTP error status to a structured {@link PlatformWriteResult}.
     * <ul>
     *   <li>429 → retryable with Retry-After (Req 1.8)</li>
     *   <li>500/502/503 → retryable with backoff (Req 1.9)</li>
     *   <li>400/422 → permanentReject with error code (Req 1.12)</li>
     *   <li>401 (invalid token) → permanentReject TOKEN_INVALID</li>
     * </ul>
     */
    private PlatformWriteResult mapErrorResponse(int status, String errorBody, String profileId) {
        return switch (status) {
            case 429 -> {
                // Record rate-limit event for adaptive backoff (Req 15.6)
                rateLimiter.recordRateLimit(profileId);
                Long retryAfter = parseRetryAfter(errorBody);
                yield PlatformWriteResult.retryable("Rate limited (HTTP 429)", retryAfter);
            }
            case 500, 502, 503 -> {
                yield PlatformWriteResult.retryable(
                        "Server error (HTTP " + status + ")", DEFAULT_SERVER_ERROR_BACKOFF_SECONDS);
            }
            case 401 -> {
                // Invalid/expired access token — evict the cached token
                yield PlatformWriteResult.permanentReject(ERROR_TOKEN_INVALID,
                        "Access token rejected (HTTP 401); re-authorization may be required");
            }
            case 400, 422 -> {
                String errorCode = extractErrorCode(errorBody);
                String reason = extractErrorMessage(errorBody);
                yield PlatformWriteResult.permanentReject(
                        errorCode != null ? errorCode : "HTTP_" + status,
                        reason != null ? reason : "Request rejected (HTTP " + status + ")");
            }
            default -> {
                // Other 4xx → permanent reject; other 5xx → retryable
                if (status >= 500) {
                    yield PlatformWriteResult.retryable(
                            "Server error (HTTP " + status + ")", DEFAULT_SERVER_ERROR_BACKOFF_SECONDS);
                } else {
                    String errorCode = extractErrorCode(errorBody);
                    yield PlatformWriteResult.permanentReject(
                            errorCode != null ? errorCode : "HTTP_" + status,
                            "Request rejected (HTTP " + status + ")");
                }
            }
        };
    }

    // -------------------------------------------------------------------------
    // External entity resolution (Req 14.2)
    // -------------------------------------------------------------------------

    /**
     * Resolve the Amazon external entity ID from the {@code external_entity_mappings}
     * table. The lookup is keyed by (store_id, platform, internal_entity_type,
     * internal_entity_id).
     *
     * <p>For keyword creation, the subject is the ad group (the parent entity).
     * For negative keyword creation, the subject is the campaign.
     *
     * @return the external entity ID, or {@code null} if no mapping exists
     */
    private String resolveExternalEntityId(PlatformChange change) {
        if (change.subjectId() == null || change.subjectId().isBlank()) {
            return null;
        }

        UUID storeId = change.storeId();
        String subjectType = change.subjectType();
        String subjectId = change.subjectId();

        // Try to parse as UUID first for internal_entity_id lookup
        UUID internalEntityId;
        try {
            internalEntityId = UUID.fromString(subjectId);
        } catch (IllegalArgumentException e) {
            // subjectId might already be an external id in some flows — look up by external id
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

    /**
     * Fallback: try to find a mapping where the external_entity_id matches the
     * provided subject id (for cases where the change already carries an external id).
     */
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
    // Helper methods
    // -------------------------------------------------------------------------

    /**
     * Resolve the Amazon Ads API host from the connection's region.
     */
    private String resolveApiHost(ConnectionContext ctx) {
        String region = ctx.credential("region");
        String normalized = AmazonAdsProperties.normalizeRegion(region);
        AmazonAdsProperties.Region regionConfig = amazonAdsProperties.region(normalized);
        return regionConfig.getApiHost();
    }

    /**
     * Resolve the Amazon Ads profile ID from the connection context or the
     * PlatformConnection entity.
     */
    private String resolveProfileId(ConnectionContext ctx) {
        // Try credential map first (may be populated by the Outbox context builder)
        String profileId = ctx.credential("profileId");
        if (profileId != null && !profileId.isBlank()) {
            return profileId;
        }

        // Fallback: load from the connection entity
        PlatformConnectionEntity connection = connectionMapper.selectById(ctx.connectionId());
        if (connection != null && connection.getProfileId() != null) {
            return connection.getProfileId();
        }

        // Default to connection id as a fallback key
        return ctx.connectionId().toString();
    }

    /**
     * Extract the Amazon request ID from a successful response body.
     */
    private String extractRequestId(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            // Amazon Ads API typically returns requestId at the top level
            if (root.has("requestId")) {
                return root.get("requestId").asText(null);
            }
            // Some endpoints return it nested or as x-amz-request-id (header),
            // captured via response body only here
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract a structured error code from an error response body.
     */
    private String extractErrorCode(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            if (root.has("code")) {
                return root.get("code").asText(null);
            }
            if (root.has("errorType")) {
                return root.get("errorType").asText(null);
            }
            if (root.has("error")) {
                return root.get("error").asText(null);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract an error message from an error response body.
     */
    private String extractErrorMessage(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            if (root.has("message")) {
                return root.get("message").asText(null);
            }
            if (root.has("details")) {
                return root.get("details").asText(null);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse a Retry-After value from an error response body or use a default.
     * Amazon Ads may return it in the response body or headers; we parse the body
     * since RestClientResponseException gives us the body.
     */
    private Long parseRetryAfter(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return DEFAULT_SERVER_ERROR_BACKOFF_SECONDS;
        }
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            if (root.has("retryAfter")) {
                return root.get("retryAfter").asLong(DEFAULT_SERVER_ERROR_BACKOFF_SECONDS);
            }
            if (root.has("retryAfterSeconds")) {
                return root.get("retryAfterSeconds").asLong(DEFAULT_SERVER_ERROR_BACKOFF_SECONDS);
            }
        } catch (Exception e) {
            // Ignore parse errors
        }
        return DEFAULT_SERVER_ERROR_BACKOFF_SECONDS;
    }

    /**
     * Parse a recommended value that may be a JSON object (for keyword/negative
     * keyword creation containing keywordText, matchType, bid, adGroupId).
     */
    private JsonNode parseKeywordDetails(String recommendedValue) {
        if (recommendedValue == null || recommendedValue.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(recommendedValue);
        } catch (Exception e) {
            // Not JSON — treat as a simple value
            return null;
        }
    }

    /**
     * Parse a string as a decimal number (for bid/budget values).
     */
    private double parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    // -------------------------------------------------------------------------
    // Read-after-write verification (Req 1.13, 1.14, 16.6, 20.2, 20.3)
    // -------------------------------------------------------------------------

    /**
     * Verify a previously submitted change by re-reading the entity's current field
     * value from the Amazon Ads API and returning it as a structured result.
     *
     * <p>The VerificationWorker invokes this after a configurable delay following a
     * successful submission. The business layer compares the returned
     * {@code liveValue} against the Operation's {@code after_value}.
     *
     * <p><b>Important (Req 1.14):</b> Entity lifecycle state strings
     * (ENABLED/PAUSED/ARCHIVED) are NEVER mapped to the live value unless the
     * verified {@code field} is actually "state". This prevents falsely marking a
     * bid/budget change as effective just because the entity is in an active state.
     *
     * @param ctx    decrypted connection context
     * @param change the original change that was submitted
     * @param meta   metadata persisted at acceptance time
     * @return the verification result with the live field value, or readFailed
     */
    @Override
    public PlatformVerifyResult verify(ConnectionContext ctx, PlatformChange change,
                                       SubmissionMetadata meta) {
        // Validate required metadata
        if (meta == null) {
            return PlatformVerifyResult.readFailed("SubmissionMetadata is null; cannot verify");
        }
        if (meta.externalEntityId() == null || meta.externalEntityId().isBlank()) {
            return PlatformVerifyResult.readFailed("Missing externalEntityId in submission metadata");
        }
        if (meta.entityType() == null || meta.entityType().isBlank()) {
            return PlatformVerifyResult.readFailed("Missing entityType in submission metadata");
        }
        if (meta.field() == null || meta.field().isBlank()) {
            return PlatformVerifyResult.readFailed("Missing field in submission metadata");
        }

        // Resolve connection profile and token
        String profileId = resolveProfileId(ctx);
        String accessToken;
        try {
            accessToken = tokenService.getAccessToken(ctx.connectionId());
        } catch (TokenExpiredException e) {
            return PlatformVerifyResult.readFailed("Token expired; cannot verify: " + e.getMessage());
        } catch (Exception e) {
            return PlatformVerifyResult.readFailed("Failed to obtain access token: " + e.getMessage());
        }

        // Build the GET URL for the entity type
        String apiHost = resolveApiHost(ctx);
        String getEndpoint = buildVerifyEndpoint(meta.entityType(), meta.externalEntityId());
        if (getEndpoint == null) {
            return PlatformVerifyResult.readFailed(
                    "Unsupported entity type for verification: " + meta.entityType());
        }

        String url = apiHost + getEndpoint;

        try {
            // Execute the GET call to read the entity
            String responseBody = http.get()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header("Amazon-Advertising-API-ClientId", amazonAdsProperties.getClientId())
                    .header("Amazon-Advertising-API-Scope", profileId)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                return PlatformVerifyResult.readFailed("Empty response from Amazon Ads API for entity "
                        + meta.entityType() + "/" + meta.externalEntityId());
            }

            // Extract the live field value from the response
            String liveValue = extractVerifyFieldValue(responseBody, meta.entityType(), meta.field());
            if (liveValue == null) {
                return PlatformVerifyResult.readFailed("Field '" + meta.field()
                        + "' not found in response for " + meta.entityType() + "/" + meta.externalEntityId());
            }

            log.info("Verification read: entityType={}, externalId={}, field={}, liveValue={}",
                    meta.entityType(), meta.externalEntityId(), meta.field(), liveValue);

            return PlatformVerifyResult.success(meta.entityType(), meta.field(), liveValue);

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String errorBody = logSanitizer.sanitize(e.getResponseBodyAsString(), ctx);
            log.warn("Verification read failed: HTTP {} for entityType={}, externalId={}, body={}",
                    status, meta.entityType(), meta.externalEntityId(), errorBody);
            return PlatformVerifyResult.readFailed(
                    "HTTP " + status + " reading " + meta.entityType() + "/" + meta.externalEntityId()
                            + ": " + errorBody);
        } catch (Exception e) {
            String sanitizedMessage = logSanitizer.sanitize(e.getMessage(), ctx);
            log.warn("Verification read failed: entityType={}, externalId={}, error={}",
                    meta.entityType(), meta.externalEntityId(), sanitizedMessage);
            return PlatformVerifyResult.readFailed(
                    "Error reading " + meta.entityType() + "/" + meta.externalEntityId()
                            + ": " + sanitizedMessage);
        }
    }

    /**
     * Build the GET endpoint path for reading an entity by type and external ID.
     *
     * <p>Routes:
     * <ul>
     *   <li>keyword → GET /sp/keywords/{keywordId}</li>
     *   <li>campaign → GET /sp/campaigns/{campaignId}</li>
     *   <li>negative_keyword → GET /sp/negativeKeywords/{negativeKeywordId}</li>
     * </ul>
     *
     * @return the endpoint path, or {@code null} if the entity type is unsupported
     */
    private String buildVerifyEndpoint(String entityType, String externalEntityId) {
        if (entityType == null) return null;
        return switch (entityType.toLowerCase()) {
            case "keyword" -> "/sp/keywords/" + externalEntityId;
            case "campaign" -> "/sp/campaigns/" + externalEntityId;
            case "negative_keyword", "negativekeyword" -> "/sp/negativeKeywords/" + externalEntityId;
            default -> null;
        };
    }

    /**
     * Extract the live field value from the Amazon Ads API GET response.
     *
     * <p><b>Req 1.14:</b> Entity lifecycle state strings (ENABLED, PAUSED, ARCHIVED,
     * PENDING) are NEVER returned as the live value unless the verified field is
     * actually "state". This ensures a bid or budget change is not falsely confirmed
     * effective just because the entity happens to be active.
     *
     * @param responseBody the JSON response from the GET endpoint
     * @param entityType   the entity type being verified
     * @param field        the specific field being verified
     * @return the live string value, or {@code null} if not found
     */
    private String extractVerifyFieldValue(String responseBody, String entityType, String field) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // The response may be a direct object or nested within a wrapper
            JsonNode entity = root;

            // Some Amazon Ads endpoints wrap in an array or under a key
            if (root.isArray() && !root.isEmpty()) {
                entity = root.get(0);
            }

            String fieldValue = readFieldFromEntity(entity, entityType, field);

            // Req 1.14: Never map lifecycle state strings to the field value
            // unless the field being verified IS "state"
            if (fieldValue != null && !"state".equalsIgnoreCase(field)) {
                if (isLifecycleStateString(fieldValue)) {
                    // The API returned a state string for a non-state field — this
                    // means we read the wrong field. Return null to signal field not found.
                    log.warn("Lifecycle state string '{}' found for non-state field '{}'; ignoring",
                            fieldValue, field);
                    return null;
                }
            }

            return fieldValue;
        } catch (Exception e) {
            log.warn("Failed to parse verification response: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Read a specific field from the entity JSON node based on entity type and field name.
     */
    private String readFieldFromEntity(JsonNode entity, String entityType, String field) {
        if (entity == null || entity.isNull()) {
            return null;
        }

        // Map our internal field names to Amazon Ads API JSON field names
        String jsonFieldName = mapFieldToJsonKey(entityType, field);

        // Direct field lookup
        if (entity.has(jsonFieldName)) {
            JsonNode fieldNode = entity.get(jsonFieldName);
            if (fieldNode.isObject()) {
                // For nested objects like "budget": { "budget": 50.0 }
                // Try to extract the inner value
                if (fieldNode.has(jsonFieldName)) {
                    return fieldNode.get(jsonFieldName).asText(null);
                }
                // Try common inner key patterns
                if (fieldNode.has("budget")) {
                    return fieldNode.get("budget").asText(null);
                }
                if (fieldNode.has("amount")) {
                    return fieldNode.get("amount").asText(null);
                }
                return fieldNode.toString();
            }
            return fieldNode.asText(null);
        }

        // Try the field name directly (case-insensitive search)
        var fields = entity.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (entry.getKey().equalsIgnoreCase(jsonFieldName) ||
                entry.getKey().equalsIgnoreCase(field)) {
                JsonNode val = entry.getValue();
                if (val.isObject()) {
                    // Nested budget object
                    if (val.has("budget")) return val.get("budget").asText(null);
                    if (val.has("amount")) return val.get("amount").asText(null);
                    return val.toString();
                }
                return val.asText(null);
            }
        }

        return null;
    }

    /**
     * Map internal field names to the JSON keys used in the Amazon Ads API responses.
     */
    private String mapFieldToJsonKey(String entityType, String field) {
        if (field == null) return "";
        return switch (field.toLowerCase()) {
            case "bid" -> "bid";
            case "dailybudget", "budget" -> "budget";
            case "state" -> "state";
            case "keywordtext", "keyword_text" -> "keywordText";
            case "matchtype", "match_type" -> "matchType";
            default -> field;
        };
    }

    /**
     * Check whether a value is an Amazon Ads entity lifecycle state string.
     * These states (ENABLED, PAUSED, ARCHIVED, PENDING) represent entity
     * lifecycle — NOT the success of a change (Req 1.14).
     */
    private boolean isLifecycleStateString(String value) {
        if (value == null || value.isBlank()) return false;
        return switch (value.trim().toUpperCase()) {
            case "ENABLED", "PAUSED", "ARCHIVED", "PENDING" -> true;
            default -> false;
        };
    }
}
