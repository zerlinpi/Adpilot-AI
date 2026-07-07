package com.adpilot.modules.apisync.connector;

import com.adpilot.common.config.HttpClientFactory;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Exchanges an Amazon Login-with-Amazon (LWA) refresh token for a short-lived
 * access token. Both Amazon SP-API and Amazon Ads authenticate with LWA, so
 * both connectors share this client.
 *
 * <h2>Re-auth detection (Req 8.1.5)</h2>
 * <p>When Amazon rejects the refresh token as expired or invalid (HTTP 400/401
 * with an {@code invalid_grant}/{@code invalid_token}/{@code invalid_client}
 * error, or a 4xx with no access token), this client throws a
 * {@link ReauthRequiredException} carrying the connection id and a secret-free
 * reason so the runner can mark the connection as requiring re-authorization.
 * Other failures (5xx, network) bubble up as ordinary exceptions and fail the
 * job as transient.</p>
 *
 * <p>SECURITY: the refresh token, client id, and client secret are sent only
 * to Amazon's official token endpoint and are never logged. Error reasons echo
 * only the OAuth error code, never the credential values.</p>
 */
@Slf4j
@Component
public class AmazonLwaClient {

    static final String TOKEN_URL = "https://api.amazon.com/auth/o2/token";

    private final RestClient http;
    private final ObjectMapper objectMapper;

    public AmazonLwaClient(ObjectMapper objectMapper, HttpClientFactory httpClientFactory) {
        this.objectMapper = objectMapper;
        this.http = httpClientFactory.timeoutRestClientBuilder().build();
    }

    /**
     * Refresh and return an LWA access token for the connection.
     *
     * @throws ReauthRequiredException if Amazon rejects the refresh token as
     *                                 expired/invalid (Req 8.1.5)
     */
    public String fetchAccessToken(ConnectionContext ctx) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", ctx.credential("refreshToken"));
        form.add("client_id", ctx.credential("clientId"));
        form.add("client_secret", ctx.credential("clientSecret"));

        String body;
        try {
            body = http.post().uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String errorCode = extractOAuthError(e.getResponseBodyAsString());
            if (isReauth(status, errorCode)) {
                throw new ReauthRequiredException(ctx.connectionId(),
                        "Amazon LWA token rejected (" + describe(status, errorCode) + ")", e);
            }
            throw e;
        }

        JsonNode node = readTree(body);
        String accessToken = text(node, "access_token");
        if (accessToken == null) {
            // A 2xx without an access token also indicates the credential is no
            // longer usable; treat it as requiring re-authorization.
            String errorCode = node != null ? text(node, "error") : null;
            throw new ReauthRequiredException(ctx.connectionId(),
                    "Amazon LWA response contained no access_token"
                            + (errorCode != null ? " (" + errorCode + ")" : ""));
        }
        return accessToken;
    }

    /** True when the OAuth failure indicates the stored credential must be re-authorized. */
    static boolean isReauth(int status, String errorCode) {
        if (errorCode != null) {
            String c = errorCode.toLowerCase();
            if (c.contains("invalid_grant") || c.contains("invalid_token")
                    || c.contains("invalid_client") || c.contains("unauthorized")) {
                return true;
            }
        }
        // 400/401 from the token endpoint means the refresh credential is bad.
        return status == 400 || status == 401 || status == 403;
    }

    private static String describe(int status, String errorCode) {
        if (errorCode != null && !errorCode.isBlank()) {
            return errorCode;
        }
        return "HTTP " + status;
    }

    private String extractOAuthError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            return text(node, "error");
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return objectMapper.nullNode();
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }
}
