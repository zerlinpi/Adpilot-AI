package com.adpilot.modules.apisync.connector;

import com.adpilot.common.config.HttpClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared HTTP/JSON plumbing for REST-based {@link PlatformDataConnector}
 * implementations (WooCommerce, Shopify). Holds a single {@link RestClient}
 * and the configured {@link ObjectMapper}, and provides tolerant timestamp
 * parsing plus JSON-to-field normalization helpers.
 *
 * <p>SECURITY: subclasses pass credentials only to the official platform
 * endpoints. Nothing in this base logs credential values; logging records
 * only the platform key, entity type, and outcome.</p>
 */
@Slf4j
abstract class AbstractRestDataConnector implements PlatformDataConnector {

    /** Shared, stateless HTTP client (mirrors the existing {@code PlatformConnector}).
     *  The default {@link StringHttpMessageConverter} decodes a {@code String}
     *  response body as ISO-8859-1 when the platform omits a charset on its
     *  {@code Content-Type}, which garbles non-ASCII data (e.g. Chinese campaign
     *  names from Google Ads). Pin the String converter to UTF-8 so {@code
     *  .body(String.class)} reads are always decoded as UTF-8. */
    protected final RestClient http;

    protected final ObjectMapper objectMapper;

    protected AbstractRestDataConnector(ObjectMapper objectMapper, HttpClientFactory httpClientFactory) {
        this.objectMapper = objectMapper;
        // Bounded connect/read timeouts (via the shared factory) so a slow platform
        // endpoint never pins the sync thread indefinitely, while keeping the UTF-8
        // String converter pinned as before.
        this.http = httpClientFactory.timeoutRestClientBuilder()
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof StringHttpMessageConverter);
                    converters.add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));
                })
                .build();
    }

    /** Remove a single trailing slash run from a URL/host string. */
    protected static String stripTrailingSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    /** Parse a JSON document into a {@link JsonNode}; never returns null. */
    protected JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse platform response JSON: " + e.getMessage(), e);
        }
    }

    /** Convert a JSON object node into a flat field map preserving raw values. */
    protected Map<String, Object> toFieldMap(JsonNode node) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return fields;
        }
        node.fields().forEachRemaining(entry -> {
            try {
                fields.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), Object.class));
            } catch (Exception e) {
                fields.put(entry.getKey(), entry.getValue().asText(null));
            }
        });
        return fields;
    }

    /** Read a text field from a JSON object, returning {@code null} when absent/null. */
    protected static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Tolerantly parse a platform timestamp into an {@link Instant}. Accepts
     * ISO offset/zoned forms (e.g. Shopify {@code 2024-01-02T03:04:05-05:00}),
     * plain local date-times treated as UTC (e.g. WooCommerce GMT fields
     * {@code 2024-01-02T03:04:05}), and {@code "..."}-padded variants.
     * Returns {@code null} when the value is missing or unparseable.
     */
    protected Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through to local-as-UTC handling
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        // Last resort: space-separated "yyyy-MM-dd HH:mm:ss" treated as UTC.
        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return LocalDateTime.parse(s, fmt).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            log.debug("Unparseable platform timestamp '{}' on {}", s, platform());
            return null;
        }
    }

    /** Format an {@link Instant} as ISO-8601 UTC (suitable for platform query params). */
    protected static String toIso(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }
}
