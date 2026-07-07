package com.adpilot.modules.apisync.connector;

import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformVerifyResult;
import com.adpilot.modules.apisync.model.SubmissionMetadata;
import com.adpilot.modules.apisync.oauth.AmazonAdsProperties;
import com.adpilot.modules.apisync.support.PlatformLogSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Property-based test validating the {@link AmazonAdsWriteConnector#verify} method
 * read-after-write verification mapping logic.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 2: Read-after-write verification mapping
 *
 * <p><b>Validates: Requirements 1.13, 1.14, 16.6, 20.2, 20.3</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>When the live value matches the expected after_value → returns success with the matching value</li>
 *   <li>When the live value differs → returns success with the mismatched value (business layer does comparison)</li>
 *   <li>When the read fails → returns readFailed with a message</li>
 *   <li>Entity lifecycle state strings (ENABLED/PAUSED/ARCHIVED) are NEVER mapped to the live value
 *       unless the field IS "state" (Req 1.14)</li>
 *   <li>verify() is never called when SubmissionMetadata is null or incomplete</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 2: Read-after-write verification mapping")
class AmazonAdsVerificationMappingPropertyTest {

    // ────────────────────────────────────────────────────────────────────────────
    // Helper to build a connector with mocked dependencies
    // ────────────────────────────────────────────────────────────────────────────

    private ConnectionContext buildContext() {
        return new ConnectionContext(
                UUID.randomUUID(), UUID.randomUUID(), "amazon_ads",
                Map.of("region", "na", "profileId", "profile-123"));
    }

    private PlatformChange buildChange(UUID storeId, String changeType, String subjectType) {
        return new PlatformChange(
                "amazon_ads", storeId, changeType, subjectType,
                UUID.randomUUID().toString(), "1.0", "2.0",
                "recommendation", UUID.randomUUID().toString(), null);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 1: When live value matches expected → returns success with matching value
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 2: Read-after-write verification mapping
     *
     * <p><b>Validates: Requirements 1.13, 16.6, 20.2</b>
     *
     * <p>When the Amazon Ads API returns the entity with a field value that matches
     * the expected after_value, the verify() method returns a success result carrying
     * that live value. The business layer (VerificationWorker) then compares and
     * transitions the Operation to effective.
     */
    @Property(tries = 150)
    void verifyReturnsSuccessWithMatchingLiveValue(
            @ForAll("entityTypes") String entityType,
            @ForAll("numericFields") String field,
            @ForAll("canonicalNumericValues") String expectedValue) {

        String externalId = "ext-" + UUID.randomUUID().toString().substring(0, 8);
        String jsonKey = mapFieldToJsonKey(field);
        // Use the value as a JSON string so asText() returns it exactly as given
        String responseBody = "{\"" + jsonKey + "\": \"" + expectedValue + "\"}";

        ConnectionContext ctx = buildContext();
        PlatformChange change = buildChange(ctx.storeId(), changeTypeForField(field), entityType);
        SubmissionMetadata meta = new SubmissionMetadata(
                "amzn-req-123", externalId, entityType, field, expectedValue);

        PlatformVerifyResult result = invokeVerifyWithResponse(ctx, change, meta, responseBody);

        assertThat(result.read())
                .as("verify() must return read=true when the API returns the entity successfully")
                .isTrue();
        assertThat(result.liveValue())
                .as("verify() must return the live value from the API response")
                .isEqualTo(expectedValue);
        assertThat(result.entityType())
                .as("verify() must return the entity type")
                .isEqualTo(entityType);
        assertThat(result.field())
                .as("verify() must return the field name")
                .isEqualTo(field);
        assertThat(result.message())
                .as("verify() success should have no error message")
                .isNull();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 2: When live value differs → returns success with the mismatched value
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 2: Read-after-write verification mapping
     *
     * <p><b>Validates: Requirements 1.13, 16.6, 20.3</b>
     *
     * <p>When the live value does not match the expected value, verify() still returns
     * success (read=true) with the actual live value. The connector does NOT make the
     * match/mismatch decision — that is the VerificationWorker's responsibility.
     */
    @Property(tries = 150)
    void verifyReturnsSuccessWithMismatchedLiveValue(
            @ForAll("entityTypes") String entityType,
            @ForAll("numericFields") String field,
            @ForAll("canonicalNumericValues") String expectedValue,
            @ForAll("canonicalNumericValues") String differentLiveValue) {

        // Only test when values actually differ
        Assume.that(!expectedValue.equals(differentLiveValue));

        String externalId = "ext-" + UUID.randomUUID().toString().substring(0, 8);
        String jsonKey = mapFieldToJsonKey(field);
        // Use the live value as a JSON string so asText() returns it exactly
        String responseBody = "{\"" + jsonKey + "\": \"" + differentLiveValue + "\"}";

        ConnectionContext ctx = buildContext();
        PlatformChange change = buildChange(ctx.storeId(), changeTypeForField(field), entityType);
        SubmissionMetadata meta = new SubmissionMetadata(
                "amzn-req-456", externalId, entityType, field, expectedValue);

        PlatformVerifyResult result = invokeVerifyWithResponse(ctx, change, meta, responseBody);

        assertThat(result.read())
                .as("verify() must return read=true even when value mismatches (business layer decides)")
                .isTrue();
        assertThat(result.liveValue())
                .as("verify() must return the ACTUAL live value, not the expected value")
                .isEqualTo(differentLiveValue);
        assertThat(result.liveValue())
                .as("The live value must differ from the expected value in this scenario")
                .isNotEqualTo(expectedValue);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 3: When the read fails → returns readFailed with a message
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 2: Read-after-write verification mapping
     *
     * <p><b>Validates: Requirements 1.13, 20.3</b>
     *
     * <p>When the Amazon Ads API read fails (e.g., HTTP error, network error), the
     * connector returns readFailed with a descriptive message. The Operation will
     * eventually time out or retry.
     */
    @Property(tries = 150)
    void verifyReturnsReadFailedOnApiError(
            @ForAll("entityTypes") String entityType,
            @ForAll("numericFields") String field,
            @ForAll("httpErrorStatuses") int httpStatus) {

        String externalId = "ext-" + UUID.randomUUID().toString().substring(0, 8);

        ConnectionContext ctx = buildContext();
        PlatformChange change = buildChange(ctx.storeId(), changeTypeForField(field), entityType);
        SubmissionMetadata meta = new SubmissionMetadata(
                "amzn-req-789", externalId, entityType, field, "2.0");

        // Simulate an HTTP error from the Amazon Ads API
        PlatformVerifyResult result = invokeVerifyWithHttpError(ctx, change, meta, httpStatus);

        assertThat(result.read())
                .as("verify() must return read=false when the API call fails with HTTP " + httpStatus)
                .isFalse();
        assertThat(result.liveValue())
                .as("verify() readFailed must have null liveValue")
                .isNull();
        assertThat(result.message())
                .as("verify() readFailed must carry a diagnostic message")
                .isNotNull()
                .isNotBlank();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 4: Lifecycle state strings are NEVER the live value for non-state fields
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 2: Read-after-write verification mapping
     *
     * <p><b>Validates: Requirements 1.14</b>
     *
     * <p>Entity lifecycle state strings (ENABLED, PAUSED, ARCHIVED, PENDING) SHALL NOT
     * be mapped to the live value unless the field being verified IS "state". This
     * prevents false positives when reading a bid or budget field that happens to
     * return a state value from a wrong field mapping.
     */
    @Property(tries = 150)
    void lifecycleStateStringsNeverMappedForNonStateFields(
            @ForAll("entityTypes") String entityType,
            @ForAll("nonStateFields") String nonStateField,
            @ForAll("lifecycleStates") String lifecycleState) {

        String externalId = "ext-" + UUID.randomUUID().toString().substring(0, 8);
        String jsonKey = mapFieldToJsonKey(nonStateField);
        // Simulate the API returning a lifecycle state string for a non-state field
        String responseBody = "{\"" + jsonKey + "\": \"" + lifecycleState + "\"}";

        ConnectionContext ctx = buildContext();
        PlatformChange change = buildChange(ctx.storeId(), changeTypeForField(nonStateField), entityType);
        SubmissionMetadata meta = new SubmissionMetadata(
                "amzn-req-state", externalId, entityType, nonStateField, "2.50");

        PlatformVerifyResult result = invokeVerifyWithResponse(ctx, change, meta, responseBody);

        // The connector should either return readFailed or return a null liveValue
        // because lifecycle states must NOT be mapped for non-state fields
        if (result.read()) {
            assertThat(result.liveValue())
                    .as("Lifecycle state '%s' must NEVER be returned as live value for non-state field '%s' (Req 1.14)",
                            lifecycleState, nonStateField)
                    .isNull();
        }
        // If read=false, that's also acceptable (treated as field not found)
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 2: Read-after-write verification mapping
     *
     * <p><b>Validates: Requirements 1.14</b>
     *
     * <p>When the field IS "state", lifecycle state strings ARE valid live values
     * and should be returned normally.
     */
    @Property(tries = 150)
    void lifecycleStateStringsAreValidForStateField(
            @ForAll("entityTypes") String entityType,
            @ForAll("lifecycleStates") String lifecycleState) {

        String externalId = "ext-" + UUID.randomUUID().toString().substring(0, 8);
        String responseBody = "{\"state\": \"" + lifecycleState + "\"}";

        ConnectionContext ctx = buildContext();
        PlatformChange change = buildChange(ctx.storeId(), "state", entityType);
        SubmissionMetadata meta = new SubmissionMetadata(
                "amzn-req-stateok", externalId, entityType, "state", lifecycleState);

        PlatformVerifyResult result = invokeVerifyWithResponse(ctx, change, meta, responseBody);

        assertThat(result.read())
                .as("verify() must return read=true when reading the 'state' field")
                .isTrue();
        assertThat(result.liveValue())
                .as("Lifecycle state '%s' IS a valid live value when the field is 'state'", lifecycleState)
                .isEqualTo(lifecycleState);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 5: verify() returns readFailed when SubmissionMetadata is null or incomplete
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 2: Read-after-write verification mapping
     *
     * <p><b>Validates: Requirements 16.6, 20.2</b>
     *
     * <p>verify() must never be invoked successfully with null or incomplete metadata.
     * When metadata is null or lacks required fields, the connector returns readFailed
     * immediately without making any API call.
     */
    @Property(tries = 150)
    void verifyReturnsReadFailedWhenMetadataIsNullOrIncomplete(
            @ForAll("incompleteMetadataScenarios") SubmissionMetadata meta) {

        ConnectionContext ctx = buildContext();
        PlatformChange change = buildChange(ctx.storeId(), "bid", "keyword");

        // Build a connector that will NOT receive an HTTP call
        AmazonAdsTokenService tokenService = mock(AmazonAdsTokenService.class);
        AmazonAdsRateLimiter rateLimiter = mock(AmazonAdsRateLimiter.class);
        ExternalEntityMappingMapper mappingMapper = mock(ExternalEntityMappingMapper.class);
        PlatformConnectionMapper connectionMapper = mock(PlatformConnectionMapper.class);
        PlatformLogSanitizer logSanitizer = mock(PlatformLogSanitizer.class);
        when(logSanitizer.sanitize(anyString(), any(ConnectionContext.class))).thenAnswer(inv -> inv.getArgument(0));
        when(logSanitizer.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));

        AmazonAdsProperties properties = new AmazonAdsProperties();
        properties.setClientId("test-client-id");

        AmazonAdsWriteConnector connector = new AmazonAdsWriteConnector(
                tokenService, rateLimiter, mappingMapper, connectionMapper,
                properties, new ObjectMapper(), logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));

        PlatformVerifyResult result = connector.verify(ctx, change, meta);

        assertThat(result.read())
                .as("verify() must return read=false when metadata is null or incomplete")
                .isFalse();
        assertThat(result.message())
                .as("verify() readFailed must include a diagnostic message explaining what is missing")
                .isNotNull()
                .isNotBlank();
        assertThat(result.liveValue())
                .as("verify() readFailed must have null liveValue")
                .isNull();

        // Token service should NOT be called when metadata is invalid
        verifyNoInteractions(tokenService);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Helper to invoke verify with a stubbed response body
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Invokes verify() on the real connector, but with the HTTP call intercepted
     * to return the given response body. This tests the actual parsing/mapping logic.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private PlatformVerifyResult invokeVerifyWithResponse(ConnectionContext ctx,
                                                          PlatformChange change,
                                                          SubmissionMetadata meta,
                                                          String responseBody) {
        AmazonAdsTokenService tokenService = mock(AmazonAdsTokenService.class);
        when(tokenService.getAccessToken(any())).thenReturn("test-access-token");

        AmazonAdsRateLimiter rateLimiter = mock(AmazonAdsRateLimiter.class);
        ExternalEntityMappingMapper mappingMapper = mock(ExternalEntityMappingMapper.class);
        PlatformConnectionMapper connectionMapper = mock(PlatformConnectionMapper.class);
        PlatformLogSanitizer logSanitizer = mock(PlatformLogSanitizer.class);
        when(logSanitizer.sanitize(anyString(), any(ConnectionContext.class))).thenAnswer(inv -> inv.getArgument(0));
        when(logSanitizer.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));

        AmazonAdsProperties properties = new AmazonAdsProperties();
        properties.setClientId("test-client-id");

        ObjectMapper objectMapper = new ObjectMapper();

        // Use a spy with RestClient mock to intercept the HTTP GET call
        AmazonAdsWriteConnector connector = new AmazonAdsWriteConnector(
                tokenService, rateLimiter, mappingMapper, connectionMapper,
                properties, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));

        // We need to intercept the RestClient call. Use reflection to replace the http field
        // with a mocked RestClient that returns our response body.
        RestClient mockHttp = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        doReturn(uriSpec).when(mockHttp).get();
        doReturn(headersSpec).when(uriSpec).uri(anyString());
        doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(responseBody);

        // Replace the http field using reflection
        try {
            var httpField = AmazonAdsWriteConnector.class.getDeclaredField("http");
            httpField.setAccessible(true);
            httpField.set(connector, mockHttp);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mock RestClient", e);
        }

        return connector.verify(ctx, change, meta);
    }

    /**
     * Invokes verify() with the HTTP call throwing a RestClientResponseException.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private PlatformVerifyResult invokeVerifyWithHttpError(ConnectionContext ctx,
                                                           PlatformChange change,
                                                           SubmissionMetadata meta,
                                                           int httpStatus) {
        AmazonAdsTokenService tokenService = mock(AmazonAdsTokenService.class);
        when(tokenService.getAccessToken(any())).thenReturn("test-access-token");

        AmazonAdsRateLimiter rateLimiter = mock(AmazonAdsRateLimiter.class);
        ExternalEntityMappingMapper mappingMapper = mock(ExternalEntityMappingMapper.class);
        PlatformConnectionMapper connectionMapper = mock(PlatformConnectionMapper.class);
        PlatformLogSanitizer logSanitizer = mock(PlatformLogSanitizer.class);
        when(logSanitizer.sanitize(anyString(), any(ConnectionContext.class))).thenAnswer(inv -> inv.getArgument(0));
        when(logSanitizer.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));

        AmazonAdsProperties properties = new AmazonAdsProperties();
        properties.setClientId("test-client-id");

        ObjectMapper objectMapper = new ObjectMapper();

        AmazonAdsWriteConnector connector = new AmazonAdsWriteConnector(
                tokenService, rateLimiter, mappingMapper, connectionMapper,
                properties, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));

        // Mock RestClient to throw a RestClientResponseException
        RestClient mockHttp = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        doReturn(uriSpec).when(mockHttp).get();
        doReturn(headersSpec).when(uriSpec).uri(anyString());
        doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
        when(headersSpec.retrieve()).thenReturn(responseSpec);

        RestClientResponseException ex = mock(RestClientResponseException.class);
        when(ex.getStatusCode()).thenReturn(org.springframework.http.HttpStatusCode.valueOf(httpStatus));
        when(ex.getResponseBodyAsString()).thenReturn("{\"error\": \"test error\"}");
        when(responseSpec.body(String.class)).thenThrow(ex);

        // Replace the http field using reflection
        try {
            var httpField = AmazonAdsWriteConnector.class.getDeclaredField("http");
            httpField.setAccessible(true);
            httpField.set(connector, mockHttp);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mock RestClient", e);
        }

        return connector.verify(ctx, change, meta);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Helper: map field names to JSON keys (mirrors connector logic)
    // ────────────────────────────────────────────────────────────────────────────

    private String mapFieldToJsonKey(String field) {
        return switch (field.toLowerCase()) {
            case "bid" -> "bid";
            case "dailybudget", "budget" -> "budget";
            case "state" -> "state";
            default -> field;
        };
    }

    private String changeTypeForField(String field) {
        return switch (field.toLowerCase()) {
            case "bid" -> "bid";
            case "dailybudget", "budget" -> "budget";
            case "state" -> "state";
            default -> "bid";
        };
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Generators
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Entity types supported by verify: keyword, campaign, negative_keyword.
     */
    @Provide
    Arbitrary<String> entityTypes() {
        return Arbitraries.of("keyword", "campaign", "negative_keyword");
    }

    /**
     * Numeric fields that are not "state" — bid and budget.
     */
    @Provide
    Arbitrary<String> numericFields() {
        return Arbitraries.of("bid", "budget");
    }

    /**
     * Non-state fields (used for testing Req 1.14 lifecycle state filtering).
     */
    @Provide
    Arbitrary<String> nonStateFields() {
        return Arbitraries.of("bid", "budget");
    }

    /**
     * Numeric values representing bid/budget amounts as strings.
     * These use canonical double-to-string representations that won't have
     * trailing zeros stripped by Jackson's JSON number parsing.
     */
    @Provide
    Arbitrary<String> numericValues() {
        return Arbitraries.doubles().between(0.01, 999.99)
                .map(d -> String.format("%.2f", d));
    }

    /**
     * Canonical numeric values that represent how values are typically stored
     * and compared in the verification flow. These values are used as JSON
     * string values (quoted) to avoid Jackson numeric node formatting issues.
     */
    @Provide
    Arbitrary<String> canonicalNumericValues() {
        return Arbitraries.of(
                "0.01", "0.05", "0.1", "0.15", "0.25", "0.5", "0.75",
                "1.0", "1.25", "1.5", "2.0", "2.5", "3.0", "5.0",
                "7.5", "10.0", "15.0", "20.0", "25.0", "50.0", "75.0",
                "100.0", "150.0", "200.0", "500.0", "999.99");
    }

    /**
     * Entity lifecycle state strings that Req 1.14 specifies must NOT be
     * mapped to non-state fields.
     */
    @Provide
    Arbitrary<String> lifecycleStates() {
        return Arbitraries.of("ENABLED", "PAUSED", "ARCHIVED", "PENDING");
    }

    /**
     * HTTP error status codes that the Amazon Ads API might return during
     * a verification read.
     */
    @Provide
    Arbitrary<Integer> httpErrorStatuses() {
        return Arbitraries.of(400, 403, 404, 429, 500, 502, 503);
    }

    /**
     * Incomplete SubmissionMetadata scenarios: null metadata, or metadata with
     * missing required fields (externalEntityId, entityType, field).
     */
    @Provide
    Arbitrary<SubmissionMetadata> incompleteMetadataScenarios() {
        return Arbitraries.oneOf(
                // Null metadata
                Arbitraries.just(null),
                // Missing externalEntityId
                Arbitraries.just(new SubmissionMetadata("req-1", null, "keyword", "bid", "2.0")),
                Arbitraries.just(new SubmissionMetadata("req-2", "", "keyword", "bid", "2.0")),
                Arbitraries.just(new SubmissionMetadata("req-3", "  ", "keyword", "bid", "2.0")),
                // Missing entityType
                Arbitraries.just(new SubmissionMetadata("req-4", "ext-123", null, "bid", "2.0")),
                Arbitraries.just(new SubmissionMetadata("req-5", "ext-123", "", "bid", "2.0")),
                Arbitraries.just(new SubmissionMetadata("req-6", "ext-123", "  ", "bid", "2.0")),
                // Missing field
                Arbitraries.just(new SubmissionMetadata("req-7", "ext-123", "keyword", null, "2.0")),
                Arbitraries.just(new SubmissionMetadata("req-8", "ext-123", "keyword", "", "2.0")),
                Arbitraries.just(new SubmissionMetadata("req-9", "ext-123", "keyword", "  ", "2.0"))
        );
    }
}
