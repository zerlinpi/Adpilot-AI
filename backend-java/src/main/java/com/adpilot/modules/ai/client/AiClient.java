package com.adpilot.modules.ai.client;

import com.adpilot.modules.ai.entity.AiSettings;
import com.adpilot.modules.ai.service.AiSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Thin client for any OpenAI-compatible Chat Completions API.
 *
 * <p>Works with OpenAI, Azure OpenAI, DeepSeek, Moonshot (Kimi), Qwen/DashScope
 * (compatible mode), local Ollama/vLLM, etc. — the provider is selected purely
 * by the {@code base-url} + {@code api-key} + {@code model} stored in
 * {@link AiSettings}, which can be changed at runtime.</p>
 *
 * <p>Callers should use {@link #isEnabled()} to decide whether to call the model
 * or fall back to template logic, so features keep working when AI is not
 * configured.</p>
 *
 * <p><b>Thread-pool protection:</b> {@link #complete(String, String)} is a
 * blocking HTTP call made on request threads. To stop a slow or unreachable
 * provider from exhausting the Tomcat thread pool, the number of simultaneous
 * in-flight calls is bounded by a fair {@link Semaphore}; when saturated, extra
 * callers fail fast with {@link AiException} (and fall back to templates) after
 * a short bounded wait instead of parking a request thread on the provider.</p>
 */
@Slf4j
@Component
public class AiClient {

    private final AiSettingsService aiSettingsService;
    private final ObjectMapper objectMapper;

    private final long acquireTimeoutMillis;

    /** Caps how many request threads can be simultaneously blocked in an AI call. */
    private final Semaphore inFlightPermits;

    // Bounded connect/read timeouts so a slow or unreachable AI provider fails
    // cleanly (caller falls back to templates) instead of hanging the request
    // thread indefinitely.
    private final RestClient restClient;

    public AiClient(AiSettingsService aiSettingsService,
                    ObjectMapper objectMapper,
                    @Value("${adpilot.ai.connect-timeout-seconds:5}") int connectTimeoutSeconds,
                    @Value("${adpilot.ai.read-timeout-seconds:30}") int readTimeoutSeconds,
                    @Value("${adpilot.ai.max-concurrent-calls:8}") int maxConcurrentCalls,
                    @Value("${adpilot.ai.acquire-timeout-millis:2000}") long acquireTimeoutMillis) {
        this.aiSettingsService = aiSettingsService;
        this.objectMapper = objectMapper;
        this.acquireTimeoutMillis = acquireTimeoutMillis;
        this.inFlightPermits = new Semaphore(Math.max(1, maxConcurrentCalls), true);
        this.restClient = RestClient.builder()
                .requestFactory(timeoutRequestFactory(connectTimeoutSeconds, readTimeoutSeconds))
                .build();
    }

    private static SimpleClientHttpRequestFactory timeoutRequestFactory(int connectTimeoutSeconds,
                                                                        int readTimeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(connectTimeoutSeconds).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(readTimeoutSeconds).toMillis());
        return factory;
    }

    /** Whether a usable AI configuration exists (enabled + key + base url + model). */
    public boolean isEnabled() {
        AiSettings s = aiSettingsService.getActiveSettings();
        return s != null
                && Boolean.TRUE.equals(s.getEnabled())
                && hasText(s.getApiKey())
                && hasText(s.getBaseUrl())
                && hasText(s.getModel());
    }

    /**
     * Run a single-turn chat completion. Returns the assistant message content,
     * or throws {@link AiException} on any failure so callers can fall back.
     *
     * <p>Fails fast with {@link AiException} if the concurrency guard cannot be
     * acquired within {@code adpilot.ai.acquire-timeout-millis}, so a slow
     * provider under load cannot pile up blocked request threads.</p>
     */
    public String complete(String systemPrompt, String userPrompt) {
        AiSettings s = aiSettingsService.getActiveSettings();
        if (s == null || !hasText(s.getApiKey()) || !hasText(s.getBaseUrl()) || !hasText(s.getModel())) {
            throw new AiException("AI is not configured");
        }

        boolean acquired;
        try {
            acquired = inFlightPermits.tryAcquire(acquireTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("AI call interrupted while waiting for a slot", e);
        }
        if (!acquired) {
            throw new AiException("AI busy: too many concurrent requests");
        }

        try {
            List<Map<String, String>> messages = new ArrayList<>();
            if (hasText(systemPrompt)) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }
            messages.add(Map.of("role", "user", "content", userPrompt));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", s.getModel());
            body.put("messages", messages);
            body.put("temperature", s.getTemperature() != null ? s.getTemperature() : new BigDecimal("0.7"));
            body.put("max_tokens", s.getMaxTokens() != null ? s.getMaxTokens() : 1024);

            String url = buildChatUrl(s.getBaseUrl());

            try {
                return callProvider(s, body, url);
            } catch (AiException e) {
                throw e;
            } catch (Exception e) {
                log.warn("AI call failed ({} {} -> {}): {}", s.getProvider(), s.getModel(), url, e.getMessage());
                throw new AiException("AI call failed (" + url + "): " + e.getMessage(), e);
            }
        } finally {
            inFlightPermits.release();
        }
    }

    /**
     * Perform the blocking HTTP round-trip and parse the assistant content.
     * Isolated as a seam so the concurrency-guard behavior can be unit-tested
     * without a live provider.
     */
    protected String callProvider(AiSettings s, Map<String, Object> body, String url) throws Exception {
        String response = restClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + s.getApiKey())
                .headers(h -> {
                    if (s.getExtraHeaders() != null) {
                        s.getExtraHeaders().forEach(h::set);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(response);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new AiException("AI response missing choices[0].message.content");
        }
        return content.asText().trim();
    }

    /**
     * Build the chat-completions endpoint from a user-supplied base URL,
     * tolerating common variations so we don't 404:
     *  - already a full .../chat/completions URL -> use as-is
     *  - ends with a version segment (/v1, /compatible-mode/v1, ...) -> append /chat/completions
     *  - bare host (no version) -> append /v1/chat/completions
     */
    private String buildChatUrl(String baseUrl) {
        String u = baseUrl.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        String lower = u.toLowerCase();
        if (lower.endsWith("/chat/completions")) {
            return u;
        }
        if (lower.endsWith("/completions")) {
            return u;
        }
        // Does the path already contain a version segment near the end?
        if (lower.matches(".*/v\\d+$") || lower.endsWith("/compatible-mode/v1")
                || lower.contains("/deployments/")) {
            return u + "/chat/completions";
        }
        // Bare host or custom path without a version: assume OpenAI-style /v1.
        return u + "/v1/chat/completions";
    }

    private boolean hasText(String v) {
        return v != null && !v.isBlank();
    }

    /** Unchecked exception so callers can choose to fall back to templates. */
    public static class AiException extends RuntimeException {
        public AiException(String message) {
            super(message);
        }

        public AiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
