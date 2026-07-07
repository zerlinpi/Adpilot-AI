package com.adpilot.modules.apisync.connector;

import com.adpilot.common.config.HttpClientFactory;
import com.adpilot.common.resilience.CircuitBreaker;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.oauth.AmazonAdsProperties;
import com.adpilot.modules.feishu.service.FeishuService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of {@link AmazonAdsTokenService}.
 *
 * <p>Caches LWA access tokens per {@code PlatformConnection} in a
 * {@link ConcurrentHashMap}, proactively refreshing 5 minutes before expiry.
 * On {@code invalid_grant} from Amazon: marks the connection as
 * {@code token_expired}, flags degraded write capability, and triggers a
 * Feishu alert via {@link FeishuService}.</p>
 *
 * <p><b>Requirements:</b> 1.7, 1.15, 15.3, 15.4</p>
 *
 * <p>SECURITY: refresh tokens are decrypted via {@link CryptoUtil} only at the
 * point of use and are never logged. The client-id and client-secret are read
 * from {@link AmazonAdsProperties} (sourced from environment variables).</p>
 */
@Slf4j
@Service
public class AmazonAdsTokenServiceImpl implements AmazonAdsTokenService {

    /** Proactive refresh margin: refresh token 5 minutes before actual expiry (Req 15.3). */
    static final long REFRESH_MARGIN_SECONDS = 300;

    /** Default token lifetime assumed when Amazon doesn't provide expires_in. */
    static final long DEFAULT_TOKEN_LIFETIME_SECONDS = 3600;

    private final PlatformConnectionMapper connectionMapper;
    private final CryptoUtil cryptoUtil;
    private final AmazonAdsProperties amazonAdsProperties;
    private final FeishuService feishuService;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final RestClient http;

    /** Per-connection cached token entries, keyed by connection UUID. */
    private final ConcurrentHashMap<UUID, CachedToken> tokenCache = new ConcurrentHashMap<>();

    /** Connections marked as token-expired (invalid_grant received). */
    private final Set<UUID> expiredConnections = ConcurrentHashMap.newKeySet();

    public AmazonAdsTokenServiceImpl(PlatformConnectionMapper connectionMapper,
                                     CryptoUtil cryptoUtil,
                                     AmazonAdsProperties amazonAdsProperties,
                                     FeishuService feishuService,
                                     ObjectMapper objectMapper,
                                     CircuitBreaker circuitBreaker,
                                     HttpClientFactory httpClientFactory) {
        this.connectionMapper = connectionMapper;
        this.cryptoUtil = cryptoUtil;
        this.amazonAdsProperties = amazonAdsProperties;
        this.feishuService = feishuService;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.http = httpClientFactory.timeoutRestClientBuilder().build();
    }

    @Override
    public String getAccessToken(UUID connectionId) {
        if (connectionId == null) {
            throw new IllegalArgumentException("connectionId must not be null");
        }

        // Req 15.4: if connection is already marked token_expired, fail fast
        if (expiredConnections.contains(connectionId)) {
            throw new TokenExpiredException(connectionId,
                    "Connection " + connectionId + " is marked token_expired; re-authorization required");
        }

        // Check cache: return if valid with proactive refresh margin (Req 15.3)
        CachedToken cached = tokenCache.get(connectionId);
        if (cached != null && !cached.isExpiredOrNearExpiry()) {
            return cached.accessToken();
        }

        // Refresh the token
        return refreshToken(connectionId);
    }

    @Override
    public void evict(UUID connectionId) {
        if (connectionId != null) {
            tokenCache.remove(connectionId);
        }
    }

    @Override
    public boolean isTokenExpired(UUID connectionId) {
        return connectionId != null && expiredConnections.contains(connectionId);
    }

    /**
     * Refresh the LWA access token for the given connection by calling the
     * regional token endpoint with the decrypted refresh_token.
     */
    private synchronized String refreshToken(UUID connectionId) {
        // Double-check after acquiring lock
        if (expiredConnections.contains(connectionId)) {
            throw new TokenExpiredException(connectionId,
                    "Connection " + connectionId + " is marked token_expired; re-authorization required");
        }

        CachedToken cached = tokenCache.get(connectionId);
        if (cached != null && !cached.isExpiredOrNearExpiry()) {
            return cached.accessToken();
        }

        // H3: when the LWA token endpoint for this connection is known-down, do not hit it again
        // this call. Fail fast with the existing transient degraded outcome (an IllegalStateException,
        // as already thrown by this method for other unavailable-token conditions) so callers handle
        // it as a transient token-unavailable failure — NOT a permanent token_expired (re-auth) — and
        // the connection is retried once the breaker half-opens after cooldown. A valid cached token
        // above is still served, so healthy connections are unaffected.
        String breakerKey = tokenBreakerKey(connectionId);
        if (!circuitBreaker.allow(breakerKey)) {
            log.debug("AmazonAdsTokenService: circuit OPEN for {}, skipping token refresh for connection {}",
                    breakerKey, connectionId);
            throw new IllegalStateException(
                    "Amazon LWA token endpoint temporarily unavailable (circuit open) for connection "
                            + connectionId);
        }

        // Load the connection entity
        PlatformConnectionEntity connection = connectionMapper.selectById(connectionId);
        if (connection == null) {
            throw new IllegalStateException("PlatformConnection not found: " + connectionId);
        }

        String encryptedRefreshToken = connection.getRefreshTokenEncrypted();
        if (encryptedRefreshToken == null || encryptedRefreshToken.isBlank()) {
            throw new IllegalStateException(
                    "No encrypted refresh token for connection " + connectionId);
        }

        // Decrypt the refresh token via CryptoUtil (Req 1.7)
        String refreshToken = cryptoUtil.decrypt(encryptedRefreshToken);

        // Resolve the token endpoint URL from region config
        String region = AmazonAdsProperties.normalizeRegion(connection.getRegion());
        AmazonAdsProperties.Region regionConfig = amazonAdsProperties.region(region);
        String tokenUrl = regionConfig.getTokenUrl();

        // Build the token request
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", amazonAdsProperties.getClientId());
        form.add("client_secret", amazonAdsProperties.getClientSecret());

        String responseBody;
        try {
            responseBody = http.post().uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // A rejected/errored token response is a failure signal for the breaker (H3): repeated
            // failures open it so a persistently-failing endpoint is not hit every call.
            circuitBreaker.recordFailure(breakerKey);
            handleTokenError(connectionId, connection, e);
            // handleTokenError always throws; this is unreachable
            throw new IllegalStateException("Unreachable");
        } catch (RuntimeException e) {
            // Network/timeout/etc. (bounded by H1 timeouts) — also a down-dependency signal.
            circuitBreaker.recordFailure(breakerKey);
            throw e;
        }

        // Parse the response
        JsonNode node = parseJson(responseBody);
        String accessToken = textField(node, "access_token");
        if (accessToken == null) {
            // No access token in a 2xx response — treat as invalid grant
            markTokenExpired(connectionId, connection,
                    "LWA response contained no access_token");
            throw new TokenExpiredException(connectionId,
                    "LWA response contained no access_token for connection " + connectionId);
        }

        // Parse token lifetime (default 1h if not provided)
        long expiresIn = longField(node, "expires_in", DEFAULT_TOKEN_LIFETIME_SECONDS);

        // Cache the token with proactive refresh margin (Req 15.3)
        Instant expiresAt = Instant.now().plusSeconds(expiresIn);
        tokenCache.put(connectionId, new CachedToken(accessToken, expiresAt));

        // Successful refresh closes the breaker for this connection (H3).
        circuitBreaker.recordSuccess(breakerKey);

        log.debug("Refreshed LWA access token for connection {} (expires in {}s)", connectionId, expiresIn);
        return accessToken;
    }

    /**
     * Circuit-breaker key for a connection's LWA token endpoint (H3). Keyed by dependency +
     * connectionId so a single connection's failing token endpoint opens the breaker only for
     * itself, never for a healthy connection's token refreshes.
     */
    private static String tokenBreakerKey(UUID connectionId) {
        return "amazon_ads:token:" + connectionId;
    }

    /**
     * Handle a token endpoint error response. On {@code invalid_grant}: marks the
     * connection as token-expired, flags degraded write capability, triggers Feishu
     * alert, and throws {@link TokenExpiredException}. On other errors: re-throws.
     */
    private void handleTokenError(UUID connectionId, PlatformConnectionEntity connection,
                                  RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String errorCode = extractOAuthError(e.getResponseBodyAsString());

        if (isInvalidGrant(status, errorCode)) {
            String reason = "Amazon LWA token rejected ("
                    + (errorCode != null ? errorCode : "HTTP " + status) + ")";
            markTokenExpired(connectionId, connection, reason);
            throw new TokenExpiredException(connectionId, reason, e);
        }

        // Not an invalid_grant — rethrow as a transient failure
        log.warn("LWA token refresh failed for connection {} (HTTP {}): {}",
                connectionId, status, errorCode);
        throw e;
    }

    /**
     * Mark a connection as token-expired (Req 15.4):
     * 1. Add to the expired-connections set (stop further calls)
     * 2. Update connection status to "token_expired" in the database
     * 3. Evict the cached token
     * 4. Trigger a Feishu alert
     */
    private void markTokenExpired(UUID connectionId, PlatformConnectionEntity connection,
                                  String reason) {
        // 1. Stop further calls (Req 15.4)
        expiredConnections.add(connectionId);

        // 2. Mark connection status as token_expired in the database
        connection.setStatus(ConnectionStatus.TOKEN_EXPIRED);
        connectionMapper.updateById(connection);
        log.warn("Connection {} marked token_expired: {}", connectionId, reason);

        // 3. Evict cached token
        tokenCache.remove(connectionId);

        // 4. Trigger Feishu alert for degraded write capability (Req 15.4)
        triggerFeishuAlert(connectionId, connection, reason);
    }

    /**
     * Send a Feishu alert notifying that a connection's token has expired and
     * write capability is degraded (Req 15.4).
     */
    private void triggerFeishuAlert(UUID connectionId, PlatformConnectionEntity connection,
                                    String reason) {
        try {
            UUID storeId = connection.getStoreId();
            String title = "⚠️ Amazon Ads 连接授权失效";
            String message = String.format(
                    "店铺连接 [%s] (ID: %s) 的 Amazon Ads 授权 token 已失效，写入能力已降级。\n"
                            + "原因: %s\n"
                            + "请尽快重新授权以恢复广告操作执行能力。",
                    connection.getConnectionName() != null ? connection.getConnectionName() : connectionId,
                    connectionId,
                    reason);
            feishuService.pushAlert(storeId, title, message);
        } catch (Exception ex) {
            // Feishu alert failure must not block the token-expired flow
            log.warn("Failed to send Feishu alert for token_expired connection {}: {}",
                    connectionId, ex.getMessage());
        }
    }

    /**
     * Determine if the OAuth error indicates the refresh token is permanently
     * invalid (invalid_grant).
     */
    static boolean isInvalidGrant(int httpStatus, String errorCode) {
        if (errorCode != null) {
            String code = errorCode.toLowerCase();
            if (code.contains("invalid_grant")) {
                return true;
            }
        }
        // HTTP 400/401 from the token endpoint with no specific code also
        // indicates a credential problem, but we only mark token_expired on
        // explicit invalid_grant to avoid false positives on transient errors.
        return false;
    }

    private String extractOAuthError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            return textField(node, "error");
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return objectMapper.nullNode();
        }
    }

    private static String textField(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    private static long longField(JsonNode node, String field, long defaultValue) {
        if (node == null) return defaultValue;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return defaultValue;
        return v.asLong(defaultValue);
    }

    // -------------------------------------------------------------------------
    // Inner cached-token record
    // -------------------------------------------------------------------------

    /**
     * Holds a cached LWA access token and its expiry instant.
     */
    record CachedToken(String accessToken, Instant expiresAt) {

        /**
         * Returns {@code true} if the token is expired or will expire within
         * the proactive refresh margin (5 minutes).
         */
        boolean isExpiredOrNearExpiry() {
            return Instant.now().plusSeconds(REFRESH_MARGIN_SECONDS).isAfter(expiresAt);
        }
    }
}
