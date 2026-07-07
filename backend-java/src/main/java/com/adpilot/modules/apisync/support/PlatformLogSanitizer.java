package com.adpilot.modules.apisync.support;

import com.adpilot.modules.apisync.connector.PlatformConnector;
import com.adpilot.modules.apisync.model.ConnectionContext;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Masks credentials and secret values out of any text that is about to be
 * written to a log on the advertising write-back / connector / outbox path
 * (Req 51.5). The guarantee is: for any logged platform request, no credential
 * or secret value appears in the emitted log output (Property 81).
 *
 * <p>Three complementary strategies are applied so masking holds whether or not
 * the caller knows the concrete secret values:
 * <ol>
 *   <li><b>Value masking</b> — when a {@link ConnectionContext} (or an explicit
 *       set of secret values) is supplied, every non-blank secret value is
 *       replaced verbatim wherever it appears, including its URL-encoded form
 *       (form bodies and query strings encode tokens such as {@code Atzr|...}).
 *       This is the strongest guarantee and is what the connector / write-back
 *       path uses, because it always holds the decrypted context.</li>
 *   <li><b>Auth-scheme masking</b> — {@code Authorization: Bearer <token>} and
 *       {@code Basic <base64>} fragments are masked even when the value is not
 *       otherwise known.</li>
 *   <li><b>Key/value masking</b> — secret-bearing keys in query strings, form
 *       bodies, and JSON ({@code client_secret=...}, {@code "accessToken":"..."},
 *       {@code refresh_token=...}, etc.) are masked by key name.</li>
 * </ol>
 *
 * <p>The set of secret credential field keys is derived from
 * {@link PlatformConnector#fields(String)} (the {@code secret == true} fields
 * each platform declares) so the sanitizer stays consistent with the single
 * source of truth for which fields are secret, plus a base set of generic
 * secret-bearing key fragments.
 *
 * <p>This component never throws on malformed input and returns the input
 * unchanged when there is nothing to mask.
 */
@Component
public class PlatformLogSanitizer {

    /** Replacement token written in place of any masked secret. */
    public static final String MASK = "***";

    /**
     * Generic secret-bearing key fragments (lowercase). A credential field whose
     * key contains any of these, or a {@code key=value} / {@code "key":"value"}
     * pair whose key matches one of these, is masked even when the platform did
     * not explicitly flag the field.
     */
    private static final Set<String> GENERIC_SECRET_KEY_FRAGMENTS = Set.of(
            "secret", "token", "password", "passwd", "pwd",
            "apikey", "api_key", "authorization", "credential",
            "privatekey", "private_key", "signature");

    /** {@code Authorization: Bearer <token>} / {@code Basic <base64>} masking. */
    private static final Pattern AUTH_SCHEME =
            Pattern.compile("(?i)\\b(bearer|basic)\\s+([A-Za-z0-9._~+/=|\\-]+)");

    /** {@code key=value} (query/form) and {@code "key":"value"} (JSON) masking. */
    private static final Pattern SECRET_KEY_VALUE = Pattern.compile(
            "(?i)([\"']?(?:client_secret|clientsecret|refresh_token|refreshtoken"
                    + "|access_token|accesstoken|app_secret|appsecret|consumer_secret"
                    + "|consumersecret|password|passwd|pwd|api_key|apikey|secret|token"
                    + "|authorization|signature)[\"']?\\s*[:=]\\s*)([\"']?)([^\"'&\\s,}]+)([\"']?)");

    /** Lowercase secret credential field keys, derived from the platform field specs. */
    private final Set<String> secretFieldKeys;

    public PlatformLogSanitizer(PlatformConnector platformConnector) {
        Set<String> keys = new HashSet<>(GENERIC_SECRET_KEY_FRAGMENTS);
        for (String platform : PlatformConnector.SUPPORTED) {
            for (PlatformConnector.FieldSpec field : platformConnector.fields(platform)) {
                if (field.secret() && field.key() != null) {
                    keys.add(field.key().toLowerCase(Locale.ROOT));
                }
            }
        }
        this.secretFieldKeys = Set.copyOf(keys);
    }

    /**
     * Sanitize free text using auth-scheme and key/value masking only (no known
     * secret values). Safe to call on any string, including {@code null}.
     */
    public String sanitize(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String s = AUTH_SCHEME.matcher(message).replaceAll(m -> m.group(1) + " " + MASK);
        s = SECRET_KEY_VALUE.matcher(s).replaceAll(m -> m.group(1) + m.group(2) + MASK + m.group(4));
        return s;
    }

    /**
     * Sanitize free text and additionally strip every secret value held by the
     * connection context (and its URL-encoded form). This is the strongest
     * guarantee and is what the write-back / connector / outbox path uses.
     */
    public String sanitize(String message, ConnectionContext ctx) {
        if (ctx == null) {
            return sanitize(message);
        }
        return sanitize(message, ctx.credentials().values());
    }

    /**
     * Sanitize free text and additionally strip every supplied secret value
     * (and its URL-encoded form) wherever it appears.
     */
    public String sanitize(String message, Collection<String> secretValues) {
        String s = sanitize(message);
        if (s == null || s.isEmpty() || secretValues == null) {
            return s;
        }
        for (String value : secretValues) {
            if (value == null || value.isBlank()) {
                continue;
            }
            s = s.replace(value, MASK);
            String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
            if (!encoded.equals(value)) {
                s = s.replace(encoded, MASK);
            }
        }
        return s;
    }

    /**
     * Return a copy of a credential map safe to log: every secret field value is
     * replaced with {@link #MASK} while non-secret fields (ids, regions, domains)
     * are preserved for diagnostics. The original map is never mutated.
     */
    public Map<String, String> maskCredentials(Map<String, String> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            return Map.of();
        }
        Map<String, String> masked = new LinkedHashMap<>();
        credentials.forEach((k, v) -> masked.put(k, isSecretKey(k) ? MASK : v));
        return masked;
    }

    /** Whether a credential field key denotes a secret that must never be logged. */
    public boolean isSecretKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT);
        if (secretFieldKeys.contains(k)) {
            return true;
        }
        for (String fragment : GENERIC_SECRET_KEY_FRAGMENTS) {
            if (k.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** The immutable set of lowercase secret field keys (for diagnostics/tests). */
    public Set<String> secretFieldKeys() {
        return secretFieldKeys;
    }

    /** Convenience accessor exposing the generic secret key fragments. */
    public List<String> genericSecretKeyFragments() {
        return List.copyOf(GENERIC_SECRET_KEY_FRAGMENTS);
    }
}
