package com.adpilot.modules.apisync.connector;

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
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Property-based test validating that the {@link AmazonAdsWriteConnector} always
 * rejects submission when no external entity mapping exists.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 6: External mapping required for submission
 *
 * <p><b>Validates: Requirements 14.2</b>
 *
 * <p>Properties validated:
 * <ol>
 *   <li>For any supported change type and any subject (keyword/campaign/ad_group),
 *       if external_entity_mappings has no entry, the connector returns permanentReject
 *       with NO_EXTERNAL_MAPPING.</li>
 *   <li>The connector never attempts an HTTP call (never invokes token service or
 *       rate limiter) when no mapping is found.</li>
 *   <li>The rejection carries a clear structured error code (NO_EXTERNAL_MAPPING),
 *       not just a message string.</li>
 * </ol>
 */
@Label("Feature: amazon-ads-ai-hosting-system, Property 6: External mapping required for submission")
class AmazonAdsExternalMappingPropertyTest {

    // ────────────────────────────────────────────────────────────────────────────
    // Property 1: Missing mapping always yields permanentReject with NO_EXTERNAL_MAPPING
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 6: External mapping required for submission
     *
     * <p><b>Validates: Requirements 14.2</b>
     *
     * <p>For any supported change type and any subject entity (keyword, campaign, ad_group),
     * when the external_entity_mappings table has no entry for that entity, the connector
     * returns a permanent rejection with error code NO_EXTERNAL_MAPPING.
     */
    @Property(tries = 150)
    void missingExternalMappingAlwaysReturnsPermanentRejectWithErrorCode(
            @ForAll("supportedChangeTypes") String changeType,
            @ForAll("subjectTypes") String subjectType,
            @ForAll("subjectIds") String subjectId,
            @ForAll("recommendedValues") String recommendedValue) {

        // Setup: mapper always returns null (no mapping exists)
        ExternalEntityMappingMapper mappingMapper = mock(ExternalEntityMappingMapper.class);
        when(mappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        AmazonAdsTokenService tokenService = mock(AmazonAdsTokenService.class);
        AmazonAdsRateLimiter rateLimiter = mock(AmazonAdsRateLimiter.class);
        PlatformConnectionMapper connectionMapper = mock(PlatformConnectionMapper.class);
        PlatformLogSanitizer logSanitizer = mock(PlatformLogSanitizer.class);

        AmazonAdsProperties properties = new AmazonAdsProperties();
        properties.setClientId("test-client-id");

        AmazonAdsWriteConnector connector = new AmazonAdsWriteConnector(
                tokenService, rateLimiter, mappingMapper, connectionMapper,
                properties, new ObjectMapper(), logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));

        UUID storeId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        ConnectionContext ctx = new ConnectionContext(
                connectionId, storeId, "amazon_ads",
                Map.of("region", "na", "profileId", "profile-" + UUID.randomUUID()));

        PlatformChange change = new PlatformChange(
                "amazon_ads", storeId, changeType, subjectType, subjectId,
                "1.0", recommendedValue, "recommendation", UUID.randomUUID().toString(), null);

        // Act
        PlatformWriteResult result = connector.submit(ctx, change);

        // Assert: permanent rejection with structured error code
        assertThat(result.accepted())
                .as("Submission must NOT be accepted when no external mapping exists")
                .isFalse();
        assertThat(result.retryable())
                .as("Missing mapping rejection must NOT be retryable (it is a permanent condition)")
                .isFalse();
        assertThat(result.platformErrorCode())
                .as("Rejection must carry the structured error code NO_EXTERNAL_MAPPING")
                .isEqualTo(AmazonAdsWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 2: No HTTP call attempted when mapping is missing
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 6: External mapping required for submission
     *
     * <p><b>Validates: Requirements 14.2</b>
     *
     * <p>When no external mapping exists, the connector must short-circuit before
     * attempting any HTTP call. This means neither the token service nor the rate
     * limiter should be consulted (they gate the HTTP call).
     */
    @Property(tries = 150)
    void noHttpCallAttemptedWhenMappingMissing(
            @ForAll("supportedChangeTypes") String changeType,
            @ForAll("subjectTypes") String subjectType,
            @ForAll("subjectIds") String subjectId,
            @ForAll("recommendedValues") String recommendedValue) {

        // Setup: mapper always returns null (no mapping exists)
        ExternalEntityMappingMapper mappingMapper = mock(ExternalEntityMappingMapper.class);
        when(mappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        AmazonAdsTokenService tokenService = mock(AmazonAdsTokenService.class);
        AmazonAdsRateLimiter rateLimiter = mock(AmazonAdsRateLimiter.class);
        PlatformConnectionMapper connectionMapper = mock(PlatformConnectionMapper.class);
        PlatformLogSanitizer logSanitizer = mock(PlatformLogSanitizer.class);

        AmazonAdsProperties properties = new AmazonAdsProperties();
        properties.setClientId("test-client-id");

        AmazonAdsWriteConnector connector = new AmazonAdsWriteConnector(
                tokenService, rateLimiter, mappingMapper, connectionMapper,
                properties, new ObjectMapper(), logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));

        UUID storeId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        ConnectionContext ctx = new ConnectionContext(
                connectionId, storeId, "amazon_ads",
                Map.of("region", "na", "profileId", "profile-" + UUID.randomUUID()));

        PlatformChange change = new PlatformChange(
                "amazon_ads", storeId, changeType, subjectType, subjectId,
                "1.0", recommendedValue, "recommendation", UUID.randomUUID().toString(), null);

        // Act
        connector.submit(ctx, change);

        // Assert: token service was never called (HTTP path not reached)
        verifyNoInteractions(tokenService);
        // Assert: rate limiter was never called (HTTP path not reached)
        verifyNoInteractions(rateLimiter);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 3: Error code is structured (not just a message)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 6: External mapping required for submission
     *
     * <p><b>Validates: Requirements 14.2</b>
     *
     * <p>The rejection result carries the error code as a structured field
     * ({@code platformErrorCode}), not just embedded in the human-readable message.
     * The message may contain additional context but the code field is the canonical
     * identifier for programmatic handling.
     */
    @Property(tries = 150)
    void rejectionCarriesStructuredErrorCodeNotJustMessage(
            @ForAll("supportedChangeTypes") String changeType,
            @ForAll("subjectTypes") String subjectType,
            @ForAll("subjectIds") String subjectId,
            @ForAll("recommendedValues") String recommendedValue) {

        // Setup: mapper always returns null
        ExternalEntityMappingMapper mappingMapper = mock(ExternalEntityMappingMapper.class);
        when(mappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        AmazonAdsTokenService tokenService = mock(AmazonAdsTokenService.class);
        AmazonAdsRateLimiter rateLimiter = mock(AmazonAdsRateLimiter.class);
        PlatformConnectionMapper connectionMapper = mock(PlatformConnectionMapper.class);
        PlatformLogSanitizer logSanitizer = mock(PlatformLogSanitizer.class);

        AmazonAdsProperties properties = new AmazonAdsProperties();
        properties.setClientId("test-client-id");

        AmazonAdsWriteConnector connector = new AmazonAdsWriteConnector(
                tokenService, rateLimiter, mappingMapper, connectionMapper,
                properties, new ObjectMapper(), logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));

        UUID storeId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        ConnectionContext ctx = new ConnectionContext(
                connectionId, storeId, "amazon_ads",
                Map.of("region", "na", "profileId", "profile-" + UUID.randomUUID()));

        PlatformChange change = new PlatformChange(
                "amazon_ads", storeId, changeType, subjectType, subjectId,
                "1.0", recommendedValue, "recommendation", UUID.randomUUID().toString(), null);

        // Act
        PlatformWriteResult result = connector.submit(ctx, change);

        // Assert: error code is in the structured platformErrorCode field
        assertThat(result.platformErrorCode())
                .as("platformErrorCode must be the canonical NO_EXTERNAL_MAPPING constant")
                .isNotNull()
                .isNotBlank()
                .isEqualTo("NO_EXTERNAL_MAPPING");

        // The message field provides additional human-readable context
        assertThat(result.message())
                .as("message field should provide additional context about the missing mapping")
                .isNotNull()
                .isNotBlank();

        // The amazonRequestId must be null (no API call was made)
        assertThat(result.amazonRequestId())
                .as("amazonRequestId must be null when no API call was made")
                .isNull();

        // The externalEntityId must be null (none was resolved)
        assertThat(result.externalEntityId())
                .as("externalEntityId must be null when no mapping exists")
                .isNull();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Generators
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * All supported change types that the connector routes (bid, budget, state,
     * keyword, negative_keyword). These are the change types that pass the
     * "supported change type" check and would proceed to mapping resolution.
     */
    @Provide
    Arbitrary<String> supportedChangeTypes() {
        return Arbitraries.of("bid", "budget", "state", "keyword", "negative_keyword");
    }

    /**
     * Subject types for the entities that are submitted through the connector:
     * keyword, campaign, ad_group. These represent the internal entity types that
     * would have an external mapping entry.
     */
    @Provide
    Arbitrary<String> subjectTypes() {
        return Arbitraries.of("keyword", "campaign", "ad_group");
    }

    /**
     * Subject IDs: valid UUIDs representing internal entity identifiers. These are
     * the IDs that would be looked up in the external_entity_mappings table.
     * Uses UUID format since the connector attempts UUID.fromString parsing.
     * Includes enough variety to force randomized (non-exhaustive) generation
     * so jqwik runs 150+ iterations as specified.
     */
    @Provide
    Arbitrary<String> subjectIds() {
        return Arbitraries.create(() -> UUID.randomUUID().toString());
    }

    /**
     * Recommended values: various value formats representing bid amounts, budget
     * amounts, states, keyword JSON payloads, etc.
     */
    @Provide
    Arbitrary<String> recommendedValues() {
        return Arbitraries.of(
                "1.50", "2.75", "5.00", "10.00", "25.00", "50.00", "100.00",
                "enabled", "paused",
                "{\"keywordText\":\"test\",\"matchType\":\"exact\",\"bid\":1.5}",
                "{\"keywordText\":\"shoes\",\"matchType\":\"phrase\"}");
    }
}
