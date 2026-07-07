package com.adpilot.modules.apisync.support;

import com.adpilot.modules.apisync.connector.PlatformConnector;
import com.adpilot.modules.apisync.model.ConnectionContext;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for secret masking in platform-request logging performed
 * by {@link PlatformLogSanitizer} (implemented in task 10.5).
 *
 * <p>Feature: advertising-workspace-rework, Property 81: Secrets are masked in
 * logs.
 *
 * <p>Validates: Requirements 51.5.
 *
 * <p>Requirement 51.5: WHEN the Backend logs a platform request, THE Backend
 * SHALL mask credentials and secrets so that no secret value appears in logs.
 * The sanitizer is the single component every write-back / connector / outbox
 * log line is routed through before emission, so the universal property is: for
 * any text about to be logged and any set of secret credential values that text
 * may carry (in plain, URL-encoded, query-string, form-body, JSON, or
 * Authorization-header form), the sanitized output contains none of those secret
 * values, while non-secret diagnostic fields (ids, regions, domains) survive.
 *
 * <p>Secret values are generated as distinctive credential-like tokens (a
 * recognizable credential prefix plus a long random body over a token charset)
 * so that a surviving secret in the output is a real leak and not a coincidental
 * collision with surrounding non-secret text.
 */
class PlatformLogSanitizerSecretMaskingPropertyTest {

    /** A real sanitizer wired from the real platform field specs (no network use). */
    private static final PlatformLogSanitizer SANITIZER =
            new PlatformLogSanitizer(new PlatformConnector(new com.adpilot.common.config.HttpClientFactory(10, 30, 60)));

    /**
     * Feature: advertising-workspace-rework, Property 81: Secrets are masked in
     * logs.
     *
     * <p>Validates: Requirements 51.5.
     *
     * <p>For any log message that embeds secret values (verbatim and/or
     * URL-encoded) and any connection context holding those secrets, the value
     * sanitization strips every secret value and its URL-encoded form from the
     * emitted output.
     */
    @Property(tries = 200)
    void contextValueSanitizationLeavesNoSecretInLog(
            @ForAll("secrets") List<String> secrets,
            @ForAll("nonSecrets") List<String> nonSecrets) {

        String message = buildLogLine(secrets, nonSecrets);
        ConnectionContext ctx = contextWith(secrets, nonSecrets);

        String sanitized = SANITIZER.sanitize(message, ctx);

        assertExcludesAllSecrets(sanitized, secrets);
    }

    /**
     * Feature: advertising-workspace-rework, Property 81: Secrets are masked in
     * logs.
     *
     * <p>Validates: Requirements 51.5.
     *
     * <p>The same guarantee holds when the caller supplies the raw secret values
     * directly (the {@code Collection<String>} overload), independent of the
     * connection context plumbing.
     */
    @Property(tries = 200)
    void rawValueSanitizationLeavesNoSecretInLog(
            @ForAll("secrets") List<String> secrets,
            @ForAll("nonSecrets") List<String> nonSecrets) {

        String message = buildLogLine(secrets, nonSecrets);

        String sanitized = SANITIZER.sanitize(message, secrets);

        assertExcludesAllSecrets(sanitized, secrets);
    }

    /**
     * Feature: advertising-workspace-rework, Property 81: Secrets are masked in
     * logs.
     *
     * <p>Validates: Requirements 51.5.
     *
     * <p>Key-based masking (no known secret values supplied) masks every
     * secret-bearing {@code key=value} / {@code "key":"value"} pair and every
     * {@code Authorization: Bearer/Basic <token>} fragment, so a connector that
     * logs a request line without holding the decrypted context still leaks no
     * secret value.
     */
    @Property(tries = 200)
    void keyBasedSanitizationMasksSecretBearingPairs(
            @ForAll("secrets") List<String> secrets) {

        // Build a request-style line that places each secret behind a secret-bearing
        // key and behind an Authorization scheme, the forms the key/scheme masking
        // is responsible for, without supplying the secret values to the sanitizer.
        StringBuilder sb = new StringBuilder("POST /auth/o2/token ");
        String[] secretKeys = {"client_secret", "refresh_token", "access_token", "password", "api_key"};
        for (int i = 0; i < secrets.size(); i++) {
            String key = secretKeys[i % secretKeys.length];
            sb.append(key).append('=').append(secrets.get(i)).append('&');
            sb.append("\"").append(key).append("\":\"").append(secrets.get(i)).append("\" ");
            sb.append("Authorization: Bearer ").append(secrets.get(i)).append(' ');
        }

        String sanitized = SANITIZER.sanitize(sb.toString());

        assertExcludesAllSecrets(sanitized, secrets);
    }

    /**
     * Feature: advertising-workspace-rework, Property 81: Secrets are masked in
     * logs.
     *
     * <p>Validates: Requirements 51.5.
     *
     * <p>{@code maskCredentials} masks every secret-keyed field's value while
     * preserving non-secret diagnostic fields, so a credential map rendered into
     * a log line carries no secret value yet stays useful for diagnostics.
     */
    @Property(tries = 200)
    void maskCredentialsMasksSecretValuesAndKeepsNonSecrets(
            @ForAll("secrets") List<String> secrets,
            @ForAll("nonSecrets") List<String> nonSecrets) {

        Map<String, String> credentials = new LinkedHashMap<>();
        // Secret-keyed entries carry secret values.
        String[] secretKeys = {"clientSecret", "refreshToken", "accessToken", "consumerSecret", "appSecret"};
        for (int i = 0; i < secrets.size(); i++) {
            credentials.put(secretKeys[i % secretKeys.length] + i, secrets.get(i));
        }
        // Non-secret-keyed entries carry non-secret diagnostic values.
        String[] plainKeys = {"clientId", "region", "shopDomain", "profileId", "siteUrl"};
        for (int i = 0; i < nonSecrets.size(); i++) {
            credentials.put(plainKeys[i % plainKeys.length] + i, nonSecrets.get(i));
        }

        Map<String, String> masked = SANITIZER.maskCredentials(credentials);

        // No secret value survives anywhere in the masked map's values.
        String rendered = String.join("\n", masked.values());
        assertExcludesAllSecrets(rendered, secrets);

        // Every secret-keyed field was replaced with the mask token.
        credentials.forEach((k, v) -> {
            if (SANITIZER.isSecretKey(k)) {
                assertThat(masked.get(k)).isEqualTo(PlatformLogSanitizer.MASK);
            } else {
                assertThat(masked.get(k)).isEqualTo(v);
            }
        });

        // The original map is never mutated.
        for (int i = 0; i < secrets.size(); i++) {
            assertThat(credentials.get(secretKeys[i % secretKeys.length] + i)).isEqualTo(secrets.get(i));
        }
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Build a realistic platform-request log line that embeds each secret both
     * verbatim and URL-encoded across query-string, form-body, JSON, and
     * Authorization-header shapes, interleaved with non-secret diagnostic values.
     */
    private static String buildLogLine(List<String> secrets, List<String> nonSecrets) {
        StringBuilder sb = new StringBuilder();
        sb.append("Submitting platform request store=").append(UUID.randomUUID())
                .append(" platform=amazon_ads ");
        for (String nonSecret : nonSecrets) {
            sb.append("region=").append(nonSecret).append(' ');
        }
        for (String secret : secrets) {
            String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
            sb.append("client_secret=").append(secret).append('&');
            sb.append("refresh_token=").append(encoded).append(' ');
            sb.append("body={\"accessToken\":\"").append(secret).append("\"} ");
            sb.append("Authorization: Bearer ").append(secret).append(' ');
            sb.append("raw[").append(secret).append("] ");
        }
        return sb.toString();
    }

    private static ConnectionContext contextWith(List<String> secrets, List<String> nonSecrets) {
        Map<String, String> credentials = new LinkedHashMap<>();
        String[] secretKeys = {"clientSecret", "refreshToken", "accessToken", "consumerSecret", "appSecret"};
        for (int i = 0; i < secrets.size(); i++) {
            credentials.put(secretKeys[i % secretKeys.length], secrets.get(i));
        }
        credentials.put("clientId", "amzn1.application-oa2-client.public");
        for (int i = 0; i < nonSecrets.size(); i++) {
            credentials.put("region" + i, nonSecrets.get(i));
        }
        return new ConnectionContext(UUID.randomUUID(), UUID.randomUUID(), "amazon_ads", credentials);
    }

    private static void assertExcludesAllSecrets(String haystack, List<String> secrets) {
        for (String secret : secrets) {
            String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
            assertThat(haystack)
                    .as("leaked secret value (verbatim)")
                    .doesNotContain(secret);
            assertThat(haystack)
                    .as("leaked secret value (URL-encoded)")
                    .doesNotContain(encoded);
        }
    }

    // --- generators --------------------------------------------------------

    /**
     * Distinctive credential/secret values: a recognizable credential prefix plus
     * a long random body over a token charset (letters, digits, and the symbols
     * that appear in real tokens such as {@code Atzr|...}). The body is long
     * enough that a leak is unmistakable and cannot collide with surrounding text.
     */
    @Provide
    Arbitrary<List<String>> secrets() {
        Arbitrary<String> prefix = Arbitraries.of(
                "sk_live_", "Atzr|", "client_secret_", "cs_", "shpat_", "Bearer_");
        Arbitrary<String> body = Arbitraries.strings()
                .withCharRange('A', 'Z')
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .withChars('.', '-', '_', '/', '+', '=', '|')
                .ofMinLength(20)
                .ofMaxLength(48);
        Arbitrary<String> secret = Combinators.combine(prefix, body).as(String::concat);
        return secret.list().ofMinSize(1).ofMaxSize(5).uniqueElements();
    }

    /**
     * Non-secret diagnostic values (regions, profile ids) that MUST survive
     * sanitization. Kept short and distinct from the long secret bodies.
     */
    @Provide
    Arbitrary<List<String>> nonSecrets() {
        Arbitrary<String> value = Arbitraries.of("NA", "EU", "FE", "us-east-1", "profile-42", "store-7");
        List<String> none = new ArrayList<>();
        return Arbitraries.oneOf(
                Arbitraries.just(none),
                value.list().ofMinSize(1).ofMaxSize(3).uniqueElements());
    }
}
