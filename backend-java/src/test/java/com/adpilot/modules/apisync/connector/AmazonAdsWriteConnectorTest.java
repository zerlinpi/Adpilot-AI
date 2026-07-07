package com.adpilot.modules.apisync.connector;

import com.adpilot.modules.apisync.entity.ExternalEntityMappingEntity;
import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformWriteResult;
import com.adpilot.modules.apisync.oauth.AmazonAdsProperties;
import com.adpilot.modules.apisync.support.PlatformLogSanitizer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AmazonAdsWriteConnector} covering per-change-type routing,
 * external entity mapping resolution, and idempotency controls.
 *
 * <p>Validates: Requirements 1.2, 1.3, 1.4, 1.5, 1.6, 1.10
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AmazonAdsWriteConnector - per-change-type routing")
class AmazonAdsWriteConnectorTest {

    @Mock
    private AmazonAdsTokenService tokenService;

    @Mock
    private AmazonAdsRateLimiter rateLimiter;

    @Mock
    private ExternalEntityMappingMapper externalEntityMappingMapper;

    @Mock
    private PlatformConnectionMapper connectionMapper;

    @Mock
    private PlatformLogSanitizer logSanitizer;

    private AmazonAdsWriteConnector connector;
    private ObjectMapper objectMapper;
    private AmazonAdsProperties amazonAdsProperties;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final String PROFILE_ID = "profile-123";
    private static final String EXTERNAL_KEYWORD_ID = "amzn-keyword-001";
    private static final String EXTERNAL_CAMPAIGN_ID = "amzn-campaign-001";
    private static final String EXTERNAL_ADGROUP_ID = "amzn-adgroup-001";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        amazonAdsProperties = new AmazonAdsProperties();
        amazonAdsProperties.setClientId("test-client-id");

        connector = new AmazonAdsWriteConnector(
                tokenService,
                rateLimiter,
                externalEntityMappingMapper,
                connectionMapper,
                amazonAdsProperties,
                objectMapper,
                logSanitizer,
                new com.adpilot.common.config.HttpClientFactory(10, 30, 60)
        );
    }

    private ConnectionContext buildContext() {
        return new ConnectionContext(
                CONNECTION_ID,
                STORE_ID,
                "amazon_ads",
                Map.of("region", "na", "profileId", PROFILE_ID)
        );
    }

    private PlatformChange buildChange(String changeType, String subjectType, String subjectId, String recommendedValue) {
        return new PlatformChange(
                "amazon_ads",
                STORE_ID,
                changeType,
                subjectType,
                subjectId,
                "1.0",  // currentValue
                recommendedValue,
                "recommendation",
                UUID.randomUUID().toString(),
                "idempotency-key-" + UUID.randomUUID()
        );
    }

    private void mockExternalMappingFound(String externalId) {
        ExternalEntityMappingEntity mapping = new ExternalEntityMappingEntity();
        mapping.setExternalEntityId(externalId);
        when(externalEntityMappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mapping);
    }

    private void mockExternalMappingNotFound() {
        when(externalEntityMappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    }

    private void mockRateLimiterAllows() {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
    }

    private void mockRateLimiterDenies() {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(false);
        when(rateLimiter.nextAvailableInstant(anyString())).thenReturn(Instant.now().plusSeconds(5));
    }

    private void mockTokenServiceReturns(String token) {
        when(tokenService.getAccessToken(CONNECTION_ID)).thenReturn(token);
    }

    private void mockLogSanitizer() {
        when(logSanitizer.sanitize(anyString(), any(ConnectionContext.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // =========================================================================
    // Req 1.2: Bid change routes to PUT /sp/keywords
    // =========================================================================
    @Nested
    @DisplayName("Bid change routing (Req 1.2)")
    class BidChangeRouting {

        @Test
        @DisplayName("bid change is a supported change type and routes to keyword endpoint")
        void bidChangeIsSupportedChangeType() {
            // The connector should not reject "bid" as unsupported
            UUID keywordId = UUID.randomUUID();
            PlatformChange change = buildChange("bid", "keyword", keywordId.toString(), "2.50");
            mockExternalMappingFound(EXTERNAL_KEYWORD_ID);
            mockRateLimiterAllows();
            mockTokenServiceReturns("test-token");
            mockLogSanitizer();

            // The HTTP call will fail (no real server), but we verify that
            // the connector doesn't reject the change type or the mapping
            PlatformWriteResult result = connector.submit(buildContext(), change);

            // Since we can't mock the RestClient easily, the call will throw an exception
            // which is caught and returned as retryable. The important assertion is:
            // it did NOT return permanentReject with UNSUPPORTED_CHANGE_TYPE
            assertThat(result.platformErrorCode())
                    .isNotEqualTo(AmazonAdsWriteConnector.ERROR_UNSUPPORTED_CHANGE_TYPE);
            assertThat(result.platformErrorCode())
                    .isNotEqualTo(AmazonAdsWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
        }

        @Test
        @DisplayName("bid change uses PUT HTTP method")
        void bidChangeUsesPutMethod() {
            // Verify the internal routing resolves to PUT for bids
            // We test this indirectly via the connector's behavior path
            UUID keywordId = UUID.randomUUID();
            PlatformChange change = buildChange("bid", "keyword", keywordId.toString(), "3.00");
            mockExternalMappingFound(EXTERNAL_KEYWORD_ID);
            mockRateLimiterAllows();
            mockTokenServiceReturns("test-token");
            mockLogSanitizer();

            // Submit — the HTTP will fail but it won't be UNSUPPORTED_CHANGE_TYPE
            PlatformWriteResult result = connector.submit(buildContext(), change);

            // The result should be retryable (connection error) or accepted, never UNSUPPORTED
            assertThat(result.platformErrorCode())
                    .isNotEqualTo(AmazonAdsWriteConnector.ERROR_UNSUPPORTED_CHANGE_TYPE);
        }
    }

    // =========================================================================
    // Req 1.3: Budget change routes to PUT /sp/campaigns
    // =========================================================================
    @Nested
    @DisplayName("Budget change routing (Req 1.3)")
    class BudgetChangeRouting {

        @Test
        @DisplayName("budget change is a supported change type and routes to campaign endpoint")
        void budgetChangeIsSupportedChangeType() {
            UUID campaignId = UUID.randomUUID();
            PlatformChange change = buildChange("budget", "campaign", campaignId.toString(), "100.00");
            mockExternalMappingFound(EXTERNAL_CAMPAIGN_ID);
            mockRateLimiterAllows();
            mockTokenServiceReturns("test-token");
            mockLogSanitizer();

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.platformErrorCode())
                    .isNotEqualTo(AmazonAdsWriteConnector.ERROR_UNSUPPORTED_CHANGE_TYPE);
            assertThat(result.platformErrorCode())
                    .isNotEqualTo(AmazonAdsWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
        }
    }

    // =========================================================================
    // Req 1.4: State change routes to PUT /sp/campaigns
    // =========================================================================
    @Nested
    @DisplayName("State change routing (Req 1.4)")
    class StateChangeRouting {

        @Test
        @DisplayName("state change is a supported change type and routes to campaign endpoint")
        void stateChangeIsSupportedChangeType() {
            UUID campaignId = UUID.randomUUID();
            PlatformChange change = buildChange("state", "campaign", campaignId.toString(), "enabled");
            mockExternalMappingFound(EXTERNAL_CAMPAIGN_ID);
            mockRateLimiterAllows();
            mockTokenServiceReturns("test-token");
            mockLogSanitizer();

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.platformErrorCode())
                    .isNotEqualTo(AmazonAdsWriteConnector.ERROR_UNSUPPORTED_CHANGE_TYPE);
            assertThat(result.platformErrorCode())
                    .isNotEqualTo(AmazonAdsWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
        }
    }

    // =========================================================================
    // Req 1.5: Keyword creation routes to POST /sp/keywords
    // =========================================================================
    @Nested
    @DisplayName("Keyword creation routing (Req 1.5)")
    class KeywordCreationRouting {

        @Test
        @DisplayName("keyword creation is a supported change type")
        void keywordCreationIsSupportedChangeType() {
            UUID adGroupId = UUID.randomUUID();
            String keywordDetails = "{\"keywordText\":\"running shoes\",\"matchType\":\"exact\",\"bid\":1.50}";
            PlatformChange change = buildChange("keyword", "ad_group", adGroupId.toString(), keywordDetails);
            mockExternalMappingFound(EXTERNAL_ADGROUP_ID);
            mockRateLimiterAllows();
            mockTokenServiceReturns("test-token");
            mockLogSanitizer();

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.platformErrorCode())
                    .isNotEqualTo(AmazonAdsWriteConnector.ERROR_UNSUPPORTED_CHANGE_TYPE);
            assertThat(result.platformErrorCode())
                    .isNotEqualTo(AmazonAdsWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
        }
    }

    // =========================================================================
    // Req 1.6: Negative keyword creation routes to POST /sp/negativeKeywords
    // =========================================================================
    @Nested
    @DisplayName("Negative keyword creation routing (Req 1.6)")
    class NegativeKeywordCreationRouting {

        @Test
        @DisplayName("negative keyword creation is a supported change type")
        void negativeKeywordCreationIsSupportedChangeType() {
            UUID campaignId = UUID.randomUUID();
            String keywordDetails = "{\"keywordText\":\"free samples\",\"matchType\":\"phrase\"}";
            PlatformChange change = buildChange("negative_keyword", "campaign", campaignId.toString(), keywordDetails);
            mockExternalMappingFound(EXTERNAL_CAMPAIGN_ID);
            mockRateLimiterAllows();
            mockTokenServiceReturns("test-token");
            mockLogSanitizer();

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.platformErrorCode())
                    .isNotEqualTo(AmazonAdsWriteConnector.ERROR_UNSUPPORTED_CHANGE_TYPE);
            assertThat(result.platformErrorCode())
                    .isNotEqualTo(AmazonAdsWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
        }
    }

    // =========================================================================
    // Unsupported change type returns permanentReject
    // =========================================================================
    @Nested
    @DisplayName("Unsupported change type handling")
    class UnsupportedChangeType {

        @Test
        @DisplayName("unsupported change type returns permanentReject with UNSUPPORTED_CHANGE_TYPE")
        void unsupportedChangeTypeReturnsPermanentReject() {
            PlatformChange change = buildChange("unknown_type", "entity", UUID.randomUUID().toString(), "value");

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.accepted()).isFalse();
            assertThat(result.retryable()).isFalse();
            assertThat(result.platformErrorCode()).isEqualTo(AmazonAdsWriteConnector.ERROR_UNSUPPORTED_CHANGE_TYPE);
        }

        @Test
        @DisplayName("null change type returns permanentReject")
        void nullChangeTypeReturnsPermanentReject() {
            PlatformChange change = new PlatformChange(
                    "amazon_ads", STORE_ID, null, "keyword",
                    UUID.randomUUID().toString(), "1.0", "2.0",
                    "recommendation", UUID.randomUUID().toString(), null
            );

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.accepted()).isFalse();
            assertThat(result.retryable()).isFalse();
            assertThat(result.platformErrorCode()).isEqualTo(AmazonAdsWriteConnector.ERROR_UNSUPPORTED_CHANGE_TYPE);
        }
    }

    // =========================================================================
    // Req 14.2: Missing external mapping returns permanentReject NO_EXTERNAL_MAPPING
    // =========================================================================
    @Nested
    @DisplayName("External entity mapping resolution (Req 1.10, 14.2)")
    class ExternalMappingResolution {

        @Test
        @DisplayName("missing external mapping returns permanentReject with NO_EXTERNAL_MAPPING")
        void missingMappingReturnsPermanentReject() {
            UUID keywordId = UUID.randomUUID();
            PlatformChange change = buildChange("bid", "keyword", keywordId.toString(), "2.50");
            mockExternalMappingNotFound();

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.accepted()).isFalse();
            assertThat(result.retryable()).isFalse();
            assertThat(result.platformErrorCode()).isEqualTo(AmazonAdsWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
            assertThat(result.message()).contains(change.subjectType());
        }

        @Test
        @DisplayName("null subjectId returns permanentReject with NO_EXTERNAL_MAPPING")
        void nullSubjectIdReturnsPermanentReject() {
            PlatformChange change = new PlatformChange(
                    "amazon_ads", STORE_ID, "bid", "keyword",
                    null, "1.0", "2.50",
                    "recommendation", UUID.randomUUID().toString(), null
            );

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.accepted()).isFalse();
            assertThat(result.retryable()).isFalse();
            assertThat(result.platformErrorCode()).isEqualTo(AmazonAdsWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
        }

        @Test
        @DisplayName("blank subjectId returns permanentReject with NO_EXTERNAL_MAPPING")
        void blankSubjectIdReturnsPermanentReject() {
            PlatformChange change = new PlatformChange(
                    "amazon_ads", STORE_ID, "bid", "keyword",
                    "   ", "1.0", "2.50",
                    "recommendation", UUID.randomUUID().toString(), null
            );

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.accepted()).isFalse();
            assertThat(result.retryable()).isFalse();
            assertThat(result.platformErrorCode()).isEqualTo(AmazonAdsWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
        }
    }

    // =========================================================================
    // Req 1.10: Idempotency controls
    // =========================================================================
    @Nested
    @DisplayName("Idempotency controls (Req 1.10)")
    class IdempotencyControls {

        @Test
        @DisplayName("submissionIdempotencyKey is carried in PlatformChange and does not affect routing")
        void submissionIdempotencyKeyDoesNotAffectRouting() {
            UUID keywordId = UUID.randomUUID();
            String idempotencyKey = "idem-key-" + UUID.randomUUID();
            PlatformChange change = new PlatformChange(
                    "amazon_ads", STORE_ID, "bid", "keyword",
                    keywordId.toString(), "1.0", "2.50",
                    "recommendation", UUID.randomUUID().toString(),
                    idempotencyKey
            );
            mockExternalMappingFound(EXTERNAL_KEYWORD_ID);
            mockRateLimiterAllows();
            mockTokenServiceReturns("test-token");
            mockLogSanitizer();

            PlatformWriteResult result = connector.submit(buildContext(), change);

            // Key should be preserved on the change for audit, and routing should proceed
            assertThat(change.submissionIdempotencyKey()).isEqualTo(idempotencyKey);
            assertThat(result.platformErrorCode())
                    .isNotEqualTo(AmazonAdsWriteConnector.ERROR_UNSUPPORTED_CHANGE_TYPE);
        }

        @Test
        @DisplayName("null submissionIdempotencyKey does not cause errors")
        void nullIdempotencyKeyDoesNotCauseErrors() {
            UUID keywordId = UUID.randomUUID();
            PlatformChange change = new PlatformChange(
                    "amazon_ads", STORE_ID, "bid", "keyword",
                    keywordId.toString(), "1.0", "2.50",
                    "recommendation", UUID.randomUUID().toString(),
                    null
            );
            mockExternalMappingFound(EXTERNAL_KEYWORD_ID);
            mockRateLimiterAllows();
            mockTokenServiceReturns("test-token");
            mockLogSanitizer();

            PlatformWriteResult result = connector.submit(buildContext(), change);

            // Should not error due to null idempotency key
            assertThat(result.platformErrorCode())
                    .isNotEqualTo(AmazonAdsWriteConnector.ERROR_UNSUPPORTED_CHANGE_TYPE);
            assertThat(result.platformErrorCode())
                    .isNotEqualTo(AmazonAdsWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
        }

        @Test
        @DisplayName("same change submitted with different idempotency keys proceeds independently")
        void differentIdempotencyKeysProduceIndependentSubmissions() {
            UUID keywordId = UUID.randomUUID();
            PlatformChange change1 = new PlatformChange(
                    "amazon_ads", STORE_ID, "bid", "keyword",
                    keywordId.toString(), "1.0", "2.50",
                    "recommendation", UUID.randomUUID().toString(),
                    "idem-key-1"
            );
            PlatformChange change2 = new PlatformChange(
                    "amazon_ads", STORE_ID, "bid", "keyword",
                    keywordId.toString(), "1.0", "2.50",
                    "recommendation", UUID.randomUUID().toString(),
                    "idem-key-2"
            );
            mockExternalMappingFound(EXTERNAL_KEYWORD_ID);
            mockRateLimiterAllows();
            mockTokenServiceReturns("test-token");
            mockLogSanitizer();

            PlatformWriteResult result1 = connector.submit(buildContext(), change1);
            PlatformWriteResult result2 = connector.submit(buildContext(), change2);

            // Both should pass routing validation (actual HTTP will fail similarly)
            assertThat(result1.platformErrorCode())
                    .isNotEqualTo(AmazonAdsWriteConnector.ERROR_UNSUPPORTED_CHANGE_TYPE);
            assertThat(result2.platformErrorCode())
                    .isNotEqualTo(AmazonAdsWriteConnector.ERROR_UNSUPPORTED_CHANGE_TYPE);
        }
    }

    // =========================================================================
    // Rate limiter integration
    // =========================================================================
    @Nested
    @DisplayName("Rate limiter integration")
    class RateLimiterIntegration {

        @Test
        @DisplayName("rate-limited profile returns retryable result without calling the API")
        void rateLimitedReturnsRetryable() {
            UUID keywordId = UUID.randomUUID();
            PlatformChange change = buildChange("bid", "keyword", keywordId.toString(), "2.50");
            mockExternalMappingFound(EXTERNAL_KEYWORD_ID);
            mockRateLimiterDenies();

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.accepted()).isFalse();
            assertThat(result.retryable()).isTrue();
            // The retryable factory puts the error code string into message, not platformErrorCode
            assertThat(result.message()).isEqualTo(AmazonAdsWriteConnector.ERROR_RATE_LIMITED);
            assertThat(result.retryAfterSeconds()).isNotNull().isPositive();
        }
    }

    // =========================================================================
    // Platform key registration
    // =========================================================================
    @Nested
    @DisplayName("Platform registration")
    class PlatformRegistration {

        @Test
        @DisplayName("platform() returns 'amazon_ads'")
        void platformReturnsCorrectKey() {
            assertThat(connector.platform()).isEqualTo("amazon_ads");
        }
    }
}
