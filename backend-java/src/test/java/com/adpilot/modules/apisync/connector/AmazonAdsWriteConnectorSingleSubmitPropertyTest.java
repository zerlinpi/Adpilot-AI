package com.adpilot.modules.apisync.connector;

import com.adpilot.modules.apisync.entity.ExternalEntityMappingEntity;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformWriteResult;
import com.adpilot.modules.apisync.oauth.AmazonAdsProperties;
import com.adpilot.modules.apisync.support.PlatformLogSanitizer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test verifying that the {@link AmazonAdsWriteConnector} performs
 * exactly one HTTP call per {@code submit()} invocation and returns immediately
 * for all outcomes (accepted, retryable, permanentReject).
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 5: Single submit per change
 *
 * <p><b>Validates: Requirements 16.1, 16.2, 16.4</b>
 *
 * <p>Properties validated:
 * <ol>
 *   <li>For any valid change, the connector never retries internally (no loop/sleep)</li>
 *   <li>A retryable result is returned immediately without blocking</li>
 *   <li>The connector does not call the platform more than once per invocation</li>
 * </ol>
 */
@Label("Feature: amazon-ads-ai-hosting-system, Property 5: Single submit per change")
class AmazonAdsWriteConnectorSingleSubmitPropertyTest {

    /** Maximum time (ms) any single submit() call is allowed to take before we deem it "blocking". */
    private static final long MAX_SUBMIT_DURATION_MS = 200;

    // ────────────────────────────────────────────────────────────────────────────
    // Property: submit() returns exactly one result without internal retry
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 5: Single submit per change
     *
     * <p><b>Validates: Requirements 16.1, 16.2, 16.4</b>
     *
     * <p>For any valid {@code PlatformChange} with a supported change type and an
     * existing external mapping, the connector's {@code submit()} method:
     * <ul>
     *   <li>Returns exactly one {@link PlatformWriteResult} (not a collection)</li>
     *   <li>Makes at most one HTTP call to the platform</li>
     *   <li>Returns immediately (within the wall-clock bound) even for retryable errors</li>
     *   <li>Does not loop, sleep, or retry internally</li>
     * </ul>
     */
    @Property(tries = 150)
    void submitReturnsExactlyOneResultWithSingleHttpCall(
            @ForAll("validChanges") PlatformChange change,
            @ForAll("httpOutcomes") HttpOutcome outcome) {

        // Track how many times the HTTP layer is actually called
        AtomicInteger httpCallCount = new AtomicInteger(0);

        // Build a connector with instrumented dependencies
        InstrumentedConnectorFixture fixture = buildInstrumentedConnector(change, outcome, httpCallCount);

        // Execute submit and measure wall-clock time
        long start = System.nanoTime();
        PlatformWriteResult result = fixture.connector.submit(fixture.ctx, change);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // --- Assertion 1: Returns exactly one non-null PlatformWriteResult ---
        assertThat(result)
                .as("submit() must return exactly one non-null PlatformWriteResult")
                .isNotNull();

        // --- Assertion 2: At most one HTTP call per invocation ---
        assertThat(httpCallCount.get())
                .as("submit() must make at most one HTTP call per invocation (no internal retry)")
                .isLessThanOrEqualTo(1);

        // --- Assertion 3: Returns immediately (no blocking/sleeping) ---
        assertThat(elapsedMs)
                .as("submit() must return immediately (within %d ms), took %d ms",
                        MAX_SUBMIT_DURATION_MS, elapsedMs)
                .isLessThanOrEqualTo(MAX_SUBMIT_DURATION_MS);
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 5: Single submit per change
     *
     * <p><b>Validates: Requirements 16.1, 16.2, 16.4</b>
     *
     * <p>For retryable errors (429, 500, 502, 503), the connector returns a retryable
     * result immediately rather than blocking or sleeping before a retry attempt.
     * The Outbox owns retry scheduling — the connector never delays.
     */
    @Property(tries = 150)
    void retryableResultReturnedImmediatelyWithoutBlocking(
            @ForAll("validChanges") PlatformChange change,
            @ForAll("retryableStatuses") int httpStatus) {

        AtomicInteger httpCallCount = new AtomicInteger(0);
        HttpOutcome retryableOutcome = new HttpOutcome(httpStatus, "{\"message\":\"transient error\"}");

        InstrumentedConnectorFixture fixture = buildInstrumentedConnector(change, retryableOutcome, httpCallCount);

        long start = System.nanoTime();
        PlatformWriteResult result = fixture.connector.submit(fixture.ctx, change);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // Must return immediately
        assertThat(elapsedMs)
                .as("Retryable result must be returned immediately (within %d ms), took %d ms",
                        MAX_SUBMIT_DURATION_MS, elapsedMs)
                .isLessThanOrEqualTo(MAX_SUBMIT_DURATION_MS);

        // Must be marked retryable
        assertThat(result).isNotNull();
        assertThat(result.accepted())
                .as("HTTP %d must produce a non-accepted result", httpStatus)
                .isFalse();
        assertThat(result.retryable())
                .as("HTTP %d must be classified as retryable", httpStatus)
                .isTrue();

        // Exactly one HTTP call — no internal retry loop
        assertThat(httpCallCount.get())
                .as("Retryable error must not trigger internal retry (exactly 1 HTTP call)")
                .isEqualTo(1);
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 5: Single submit per change
     *
     * <p><b>Validates: Requirements 16.1, 16.2, 16.4</b>
     *
     * <p>For permanent rejections (400, 422), the connector returns a non-retryable
     * result immediately with exactly one platform call.
     */
    @Property(tries = 150)
    void permanentRejectReturnedImmediatelyWithSingleCall(
            @ForAll("validChanges") PlatformChange change,
            @ForAll("permanentRejectStatuses") int httpStatus) {

        AtomicInteger httpCallCount = new AtomicInteger(0);
        HttpOutcome rejectOutcome = new HttpOutcome(httpStatus, "{\"code\":\"INVALID\",\"message\":\"bad request\"}");

        InstrumentedConnectorFixture fixture = buildInstrumentedConnector(change, rejectOutcome, httpCallCount);

        long start = System.nanoTime();
        PlatformWriteResult result = fixture.connector.submit(fixture.ctx, change);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // Must return immediately
        assertThat(elapsedMs)
                .as("Permanent reject must be returned immediately (within %d ms), took %d ms",
                        MAX_SUBMIT_DURATION_MS, elapsedMs)
                .isLessThanOrEqualTo(MAX_SUBMIT_DURATION_MS);

        // Must be a non-retryable rejection
        assertThat(result).isNotNull();
        assertThat(result.accepted())
                .as("HTTP %d must produce a non-accepted result", httpStatus)
                .isFalse();
        assertThat(result.retryable())
                .as("HTTP %d must be classified as non-retryable (permanent reject)", httpStatus)
                .isFalse();

        // Exactly one HTTP call
        assertThat(httpCallCount.get())
                .as("Permanent reject must use exactly one HTTP call")
                .isEqualTo(1);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Test infrastructure
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Represents the HTTP outcome the mock RestClient should produce.
     */
    record HttpOutcome(int statusCode, String responseBody) {
        boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    record InstrumentedConnectorFixture(AmazonAdsWriteConnector connector, ConnectionContext ctx) {}

    /**
     * Builds an {@link AmazonAdsWriteConnector} with mocked dependencies that:
     * <ul>
     *   <li>Always resolves external entity mappings (so we reach the HTTP call)</li>
     *   <li>Always provides a valid token (so we reach the HTTP call)</li>
     *   <li>Always passes the rate limiter (so we reach the HTTP call)</li>
     *   <li>Tracks how many times the HTTP layer is invoked via the {@code httpCallCount}</li>
     *   <li>Simulates the given HTTP outcome (success or error)</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private InstrumentedConnectorFixture buildInstrumentedConnector(
            PlatformChange change, HttpOutcome outcome, AtomicInteger httpCallCount) {

        // Mock token service
        AmazonAdsTokenService tokenService = mock(AmazonAdsTokenService.class);
        when(tokenService.getAccessToken(any(UUID.class))).thenReturn("mock-access-token");

        // Mock rate limiter — always allows
        AmazonAdsRateLimiter rateLimiter = mock(AmazonAdsRateLimiter.class);
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
        when(rateLimiter.nextAvailableInstant(anyString())).thenReturn(Instant.now().plusSeconds(1));

        // Mock external entity mapping — always found
        ExternalEntityMappingMapper mappingMapper = mock(ExternalEntityMappingMapper.class);
        ExternalEntityMappingEntity mappingEntity = new ExternalEntityMappingEntity();
        mappingEntity.setExternalEntityId("amzn-ext-" + UUID.randomUUID());
        when(mappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mappingEntity);

        // Mock connection mapper — provides profile
        PlatformConnectionMapper connectionMapper = mock(PlatformConnectionMapper.class);
        PlatformConnectionEntity connectionEntity = new PlatformConnectionEntity();
        connectionEntity.setProfileId("test-profile-123");
        when(connectionMapper.selectById(any())).thenReturn(connectionEntity);

        // Amazon Ads properties
        AmazonAdsProperties properties = new AmazonAdsProperties();
        properties.setClientId("test-client-id");

        // Platform log sanitizer — pass-through
        PlatformLogSanitizer logSanitizer = mock(PlatformLogSanitizer.class);
        when(logSanitizer.sanitize(anyString(), (ConnectionContext) any())).thenAnswer(inv -> inv.getArgument(0));

        ObjectMapper objectMapper = new ObjectMapper();

        // Build the connector with a subclass that overrides the HTTP call to track invocations
        AmazonAdsWriteConnector connector = new AmazonAdsWriteConnector(
                tokenService, rateLimiter, mappingMapper, connectionMapper,
                properties, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(String changeType, String url, String accessToken,
                                           String profileId, String requestBody, ConnectionContext ctx) {
                httpCallCount.incrementAndGet();
                if (outcome.isSuccess()) {
                    return outcome.responseBody();
                }
                throw new RestClientResponseException(
                        "HTTP " + outcome.statusCode(),
                        HttpStatusCode.valueOf(outcome.statusCode()),
                        "Error",
                        null, null, null);
            }
        };

        ConnectionContext ctx = new ConnectionContext(
                UUID.randomUUID(), change.storeId(), "amazon_ads",
                Map.of("region", "na", "profileId", "test-profile-123"));

        return new InstrumentedConnectorFixture(connector, ctx);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Generators
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Generates valid {@link PlatformChange} instances covering all supported change types.
     */
    @Provide
    Arbitrary<PlatformChange> validChanges() {
        Arbitrary<String> changeTypes = Arbitraries.of("bid", "budget", "state", "keyword", "negative_keyword");
        Arbitrary<UUID> storeIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> subjectIds = Arbitraries.create(() -> UUID.randomUUID().toString());
        Arbitrary<String> recommendedValues = Arbitraries.of(
                "1.50", "2.75", "10.00", "50.00", "100.00",
                "enabled", "paused",
                "{\"keywordText\":\"test keyword\",\"matchType\":\"EXACT\",\"bid\":1.5}",
                "{\"keywordText\":\"negative term\",\"matchType\":\"PHRASE\"}");

        return Combinators.combine(changeTypes, storeIds, subjectIds, recommendedValues)
                .as((changeType, storeId, subjectId, value) -> {
                    String subjectType = resolveSubjectType(changeType);
                    String recValue = resolveRecommendedValue(changeType, value);
                    return new PlatformChange(
                            "amazon_ads", storeId, changeType, subjectType, subjectId,
                            "1.0", recValue, "recommendation", UUID.randomUUID().toString());
                });
    }

    /**
     * HTTP outcomes covering all classified response types.
     */
    @Provide
    Arbitrary<HttpOutcome> httpOutcomes() {
        Arbitrary<HttpOutcome> success = Arbitraries.just(
                new HttpOutcome(200, "{\"requestId\":\"amzn-req-123\"}"));
        Arbitrary<HttpOutcome> rateLimited = Arbitraries.just(
                new HttpOutcome(429, "{\"retryAfter\":5}"));
        Arbitrary<HttpOutcome> serverErrors = Arbitraries.of(500, 502, 503)
                .map(s -> new HttpOutcome(s, "{\"message\":\"server error\"}"));
        Arbitrary<HttpOutcome> permanent = Arbitraries.of(400, 422)
                .map(s -> new HttpOutcome(s, "{\"code\":\"INVALID_VALUE\",\"message\":\"bad\"}"));

        return Arbitraries.oneOf(success, rateLimited, serverErrors, permanent);
    }

    /**
     * HTTP status codes that classify as retryable (429, 500, 502, 503).
     */
    @Provide
    Arbitrary<Integer> retryableStatuses() {
        return Arbitraries.of(429, 500, 502, 503);
    }

    /**
     * HTTP status codes that classify as permanent rejection (400, 422).
     */
    @Provide
    Arbitrary<Integer> permanentRejectStatuses() {
        return Arbitraries.of(400, 422);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────────

    private static String resolveSubjectType(String changeType) {
        return switch (changeType) {
            case "bid" -> "keyword";
            case "budget", "state" -> "campaign";
            case "keyword" -> "ad_group";
            case "negative_keyword" -> "campaign";
            default -> "entity";
        };
    }

    private static String resolveRecommendedValue(String changeType, String fallback) {
        return switch (changeType) {
            case "bid", "budget" -> {
                // Ensure numeric for bid/budget
                try {
                    Double.parseDouble(fallback);
                    yield fallback;
                } catch (NumberFormatException e) {
                    yield "2.50";
                }
            }
            case "state" -> {
                if ("enabled".equals(fallback) || "paused".equals(fallback)) {
                    yield fallback;
                }
                yield "enabled";
            }
            case "keyword" -> "{\"keywordText\":\"test keyword\",\"matchType\":\"EXACT\",\"bid\":1.5}";
            case "negative_keyword" -> "{\"keywordText\":\"negative term\",\"matchType\":\"PHRASE\"}";
            default -> fallback;
        };
    }
}
