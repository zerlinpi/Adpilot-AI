package com.adpilot.modules.feishu.client;

import com.adpilot.common.config.HttpClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin HTTP client for the Feishu (Lark) open platform. Handles tenant access
 * token retrieval (with an in-memory cache), application message sending via the
 * IM v1 API, and custom-bot webhook delivery (with optional HMAC-SHA256 signing).
 *
 * <p>Secrets (app secret, webhook URL/secret) are passed in decrypted by the
 * caller and are never logged. All sending is best-effort from the caller's
 * perspective; this client throws {@link RuntimeException} on failure so the
 * caller can record a failed message log.</p>
 */
@Slf4j
@Component
public class FeishuApiClient {

    private static final String TOKEN_URL =
            "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";
    private static final String MESSAGE_URL =
            "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id";

    private final RestClient http;
    private final ObjectMapper objectMapper;

    /** Per-appId token cache. */
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public FeishuApiClient(ObjectMapper objectMapper, HttpClientFactory httpClientFactory) {
        this.objectMapper = objectMapper;
        // Bounded connect/read timeouts so a slow Feishu endpoint never pins the
        // calling thread indefinitely.
        this.http = httpClientFactory.timeoutRestClientBuilder().build();
    }

    // ── tenant access token ───────────────────────────────────────────────────

    /**
     * Obtain a tenant access token for an internal app, caching it in memory per
     * appId until shortly before it expires.
     *
     * @throws RuntimeException with a clear message on failure
     */
    public String tenantAccessToken(String appId, String appSecret) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new RuntimeException("Feishu app credentials are missing (app_id/app_secret)");
        }
        CachedToken cached = tokenCache.get(appId);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMillis > now) {
            return cached.token;
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("app_id", appId);
        body.put("app_secret", appSecret);

        String response;
        try {
            response = http.post().uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new RuntimeException("Feishu token request failed (HTTP "
                    + e.getStatusCode().value() + ")");
        } catch (Exception e) {
            throw new RuntimeException("Failed to reach Feishu token endpoint: " + e.getMessage());
        }

        JsonNode node = readTree(response);
        int code = node.path("code").asInt(-1);
        String token = node.path("tenant_access_token").asText(null);
        if (code != 0 || token == null || token.isBlank()) {
            throw new RuntimeException("Feishu token request returned error code "
                    + code + " (" + node.path("msg").asText("unknown") + ")");
        }
        int expireSeconds = node.path("expire").asInt(7200);
        long safety = 60L;
        long expiresAt = now + Math.max(1, (expireSeconds - safety)) * 1000L;
        tokenCache.put(appId, new CachedToken(token, expiresAt));
        return token;
    }

    // ── application message ─────────────────────────────────────────────────

    /**
     * Send a message to a chat via the IM v1 API. {@code contentJson} must be a
     * JSON string per the Feishu spec (e.g. the result of {@link #buildTextContent}).
     *
     * @throws RuntimeException on failure
     */
    public void sendAppMessage(String tenantToken, String appId, String chatId,
                               String msgType, String contentJson) {
        if (chatId == null || chatId.isBlank()) {
            throw new RuntimeException("Feishu chat_id is missing");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("receive_id", chatId);
        body.put("msg_type", msgType);
        body.put("content", contentJson);

        String response;
        try {
            response = http.post().uri(MESSAGE_URL)
                    .header("Authorization", "Bearer " + tenantToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new RuntimeException("Feishu message send failed (HTTP "
                    + e.getStatusCode().value() + ")");
        } catch (Exception e) {
            throw new RuntimeException("Failed to send Feishu message: " + e.getMessage());
        }
        // Check Feishu business-level response code (HTTP 200 does not guarantee success).
        JsonNode node = readTree(response);
        int code = node.path("code").asInt(-1);
        if (code != 0) {
            throw new RuntimeException("Feishu message send returned error code "
                    + code + " (" + node.path("msg").asText("unknown") + ")");
        }
        log.debug("Feishu app message sent: appId={}, chatId={}, msgType={}", appId, chatId, msgType);
    }

    // ── custom-bot webhook ────────────────────────────────────────────────────

    /**
     * Deliver a message to a custom-bot webhook URL. When {@code webhookSecret} is
     * non-blank, computes and includes the Feishu custom-bot signature.
     *
     * @param msgType     "text" or "interactive"
     * @param contentJson for "text": the inner content JSON object string
     *                    (e.g. {@code {"text":"hi"}}); for "interactive": the card
     *                    JSON object string.
     * @throws RuntimeException on failure
     */
    public void sendWebhook(String webhookUrl, String webhookSecret, String msgType, String contentJson) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new RuntimeException("Feishu webhook URL is missing");
        }
        ObjectNode body = objectMapper.createObjectNode();
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            long timestamp = System.currentTimeMillis() / 1000L;
            String sign = customBotSign(timestamp, webhookSecret);
            body.put("timestamp", String.valueOf(timestamp));
            body.put("sign", sign);
        }
        body.put("msg_type", msgType);
        JsonNode content = readTree(contentJson);
        if ("interactive".equals(msgType)) {
            body.set("card", content);
        } else {
            body.set("content", content);
        }

        try {
            http.post().uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new RuntimeException("Feishu webhook send failed (HTTP "
                    + e.getStatusCode().value() + ")");
        } catch (Exception e) {
            throw new RuntimeException("Failed to deliver Feishu webhook: " + e.getMessage());
        }
        log.debug("Feishu webhook delivered: msgType={}", msgType);
    }

    /**
     * Feishu custom-bot signature: stringToSign = timestamp + "\n" + secret;
     * sign = Base64( HmacSHA256(key = stringToSign bytes, data = empty) ).
     */
    private String customBotSign(long timestamp, String secret) {
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(new byte[0]);
            return Base64.getEncoder().encodeToString(signData);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute Feishu webhook signature");
        }
    }

    // ── content builders ────────────────────────────────────────────────────

    /** Build a text content JSON string: {@code {"text":"..."}}. */
    public String buildTextContent(String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("text", text == null ? "" : text);
        return node.toString();
    }

    /**
     * Build an interactive card JSON string with a title header and a markdown
     * body. Returns the card object as a JSON string (suitable for both app
     * message {@code content} and webhook {@code card}).
     */
    public String buildCardContent(String title, String markdownBody) {
        return buildConfirmCard(title, markdownBody, null).toString();
    }

    /**
     * Build a confirm interactive card. When {@code actionRequestId} is non-null,
     * adds an actions block with two buttons ("✅ 批准" / "❌ 驳回") whose value
     * carries {@code {action_request_id, decision}}.
     */
    public String buildConfirmCardContent(String title, String markdownBody, String actionRequestId) {
        return buildConfirmCard(title, markdownBody, actionRequestId).toString();
    }

    private ObjectNode buildConfirmCard(String title, String markdownBody, String actionRequestId) {
        ObjectNode card = objectMapper.createObjectNode();

        ObjectNode config = objectMapper.createObjectNode();
        config.put("wide_screen_mode", true);
        card.set("config", config);

        ObjectNode header = objectMapper.createObjectNode();
        ObjectNode titleNode = objectMapper.createObjectNode();
        titleNode.put("tag", "plain_text");
        titleNode.put("content", title == null ? "" : title);
        header.set("title", titleNode);
        header.put("template", "blue");
        card.set("header", header);

        ArrayNode elements = objectMapper.createArrayNode();
        ObjectNode bodyDiv = objectMapper.createObjectNode();
        bodyDiv.put("tag", "div");
        ObjectNode bodyText = objectMapper.createObjectNode();
        bodyText.put("tag", "lark_md");
        bodyText.put("content", markdownBody == null ? "" : markdownBody);
        bodyDiv.set("text", bodyText);
        elements.add(bodyDiv);

        if (actionRequestId != null && !actionRequestId.isBlank()) {
            ObjectNode actions = objectMapper.createObjectNode();
            actions.put("tag", "action");
            ArrayNode buttons = objectMapper.createArrayNode();
            buttons.add(button("✅ 批准", "primary", actionRequestId, "approve"));
            buttons.add(button("❌ 驳回", "danger", actionRequestId, "reject"));
            actions.set("actions", buttons);
            elements.add(actions);
        }

        card.set("elements", elements);
        return card;
    }

    private ObjectNode button(String label, String type, String actionRequestId, String decision) {
        ObjectNode btn = objectMapper.createObjectNode();
        btn.put("tag", "button");
        ObjectNode text = objectMapper.createObjectNode();
        text.put("tag", "plain_text");
        text.put("content", label);
        btn.set("text", text);
        btn.put("type", type);
        ObjectNode value = objectMapper.createObjectNode();
        value.put("action_request_id", actionRequestId);
        value.put("decision", decision);
        btn.set("value", value);
        return btn;
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    // ── tenant access token (no cache) ──────────────────────────────────────

    /**
     * Obtain a tenant access token WITHOUT reading from or writing to the cache.
     * Used for credential validation during connect/update so stale cache entries
     * do not mask invalid credentials.
     *
     * @throws RuntimeException with a clear message on failure
     */
    public String tenantAccessTokenNoCache(String appId, String appSecret) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new RuntimeException("Feishu app credentials are missing (app_id/app_secret)");
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("app_id", appId);
        body.put("app_secret", appSecret);

        String response;
        try {
            response = http.post().uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new RuntimeException("Feishu token request failed (HTTP "
                    + e.getStatusCode().value() + ")");
        } catch (Exception e) {
            throw new RuntimeException("Failed to reach Feishu token endpoint: " + e.getMessage());
        }

        JsonNode node = readTree(response);
        int code = node.path("code").asInt(-1);
        String token = node.path("tenant_access_token").asText(null);
        if (code != 0 || token == null || token.isBlank()) {
            throw new RuntimeException("Feishu token request returned error code "
                    + code + " (" + node.path("msg").asText("unknown") + ")");
        }
        return token;
    }

    private record CachedToken(String token, long expiresAtMillis) {
    }
}
