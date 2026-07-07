package com.adpilot.modules.apisync.connector;

import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.apisync.entity.ExternalEntityMappingEntity;
import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformWriteResult;
import com.adpilot.modules.apisync.support.PlatformLogSanitizer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GoogleAdsWriteConnector} covering per-change-type routing,
 * external entity mapping resolution, response classification, request-body
 * building, and status mapping. All tests are offline: HTTP is never performed
 * (either short-circuited before the call, or exercised through pure helper
 * methods / a single-call subclass hook), mirroring the Amazon write-connector
 * test style.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GoogleAdsWriteConnector")
class GoogleAdsWriteConnectorTest {

    @Mock
    private GoogleAdsTokenClient tokenClient;

    @Mock
    private ExternalEntityMappingMapper externalEntityMappingMapper;

    @Mock
    private PlatformLogSanitizer logSanitizer;

    private GoogleAdsWriteConnector connector;
    private ObjectMapper objectMapper;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final String CUSTOMER_ID = "1234567890";
    private static final String EXTERNAL_ID = "987654321";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        connector = new GoogleAdsWriteConnector(
                tokenClient, externalEntityMappingMapper, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));
    }

    private ConnectionContext buildContext() {
        return new ConnectionContext(CONNECTION_ID, STORE_ID, "google_ads",
                Map.of("customerId", CUSTOMER_ID,
                        "developerToken", "dev-token",
                        "refreshToken", "1//refresh",
                        "clientId", "client.apps.googleusercontent.com",
                        "clientSecret", "secret"));
    }

    private PlatformChange buildChange(String changeType, String subjectType,
                                       String subjectId, String recommendedValue) {
        return new PlatformChange("google_ads", STORE_ID, changeType, subjectType,
                subjectId, "1.0", recommendedValue, "recommendation",
                UUID.randomUUID().toString(), null);
    }

    private void mockMappingFound() {
        ExternalEntityMappingEntity mapping = new ExternalEntityMappingEntity();
        mapping.setExternalEntityId(EXTERNAL_ID);
        when(externalEntityMappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mapping);
    }

    private void mockTokenOk() {
        when(tokenClient.fetchAccessToken(any(ConnectionContext.class))).thenReturn("access-token");
    }

    private void mockSanitizer() {
        lenient().when(logSanitizer.sanitize(anyString(), any(ConnectionContext.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // =========================================================================
    // Platform registration
    // =========================================================================
    @Test
    @DisplayName("platform() returns 'google_ads'")
    void platformKey() {
        assertThat(connector.platform()).isEqualTo("google_ads");
    }

    // =========================================================================
    // changeType routing — supported types
    // =========================================================================
    @Nested
    @DisplayName("Change-type routing")
    class ChangeTypeRouting {

        @Test
        @DisplayName("bid/budget/state are supported; unknown is not")
        void supportedTypes() {
            assertThat(connector.isSupportedChangeType("bid")).isTrue();
            assertThat(connector.isSupportedChangeType("budget")).isTrue();
            assertThat(connector.isSupportedChangeType("state")).isTrue();
            assertThat(connector.isSupportedChangeType("unknown")).isFalse();
            assertThat(connector.isSupportedChangeType(null)).isFalse();
        }

        @Test
        @DisplayName("bid routes to adGroupCriteria:mutate")
        void bidEndpoint() {
            assertThat(connector.buildEndpoint("bid", CUSTOMER_ID))
                    .isEqualTo("/v17/customers/1234567890/adGroupCriteria:mutate");
        }

        @Test
        @DisplayName("budget routes to campaignBudgets:mutate")
        void budgetEndpoint() {
            assertThat(connector.buildEndpoint("budget", CUSTOMER_ID))
                    .isEqualTo("/v17/customers/1234567890/campaignBudgets:mutate");
        }

        @Test
        @DisplayName("state routes to campaigns:mutate")
        void stateEndpoint() {
            assertThat(connector.buildEndpoint("state", CUSTOMER_ID))
                    .isEqualTo("/v17/customers/1234567890/campaigns:mutate");
        }

        @Test
        @DisplayName("unsupported change type returns permanentReject without any HTTP/token work")
        void unsupportedTypeRejected() {
            PlatformChange change = buildChange("unknown_type", "campaign",
                    UUID.randomUUID().toString(), "5.0");

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.accepted()).isFalse();
            assertThat(result.retryable()).isFalse();
            assertThat(result.platformErrorCode())
                    .isEqualTo(GoogleAdsWriteConnector.ERROR_UNSUPPORTED_CHANGE_TYPE);
        }
    }

    // =========================================================================
    // External entity mapping resolution
    // =========================================================================
    @Nested
    @DisplayName("External mapping resolution")
    class MappingResolution {

        @Test
        @DisplayName("missing mapping returns permanentReject NO_EXTERNAL_MAPPING")
        void missingMapping() {
            when(externalEntityMappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            PlatformChange change = buildChange("bid", "keyword",
                    UUID.randomUUID().toString(), "2.5");

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.accepted()).isFalse();
            assertThat(result.platformErrorCode())
                    .isEqualTo(GoogleAdsWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
        }

        @Test
        @DisplayName("null subjectId returns permanentReject NO_EXTERNAL_MAPPING")
        void nullSubject() {
            PlatformChange change = buildChange("bid", "keyword", null, "2.5");

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.platformErrorCode())
                    .isEqualTo(GoogleAdsWriteConnector.ERROR_NO_EXTERNAL_MAPPING);
        }
    }

    // =========================================================================
    // Token / customer id pre-checks
    // =========================================================================
    @Nested
    @DisplayName("Pre-flight checks")
    class PreFlight {

        @Test
        @DisplayName("re-auth from the token client surfaces as TOKEN_INVALID permanentReject")
        void reauthBecomesTokenInvalid() {
            mockMappingFound();
            when(tokenClient.fetchAccessToken(any(ConnectionContext.class)))
                    .thenThrow(new ReauthRequiredException(CONNECTION_ID, "Google OAuth token rejected (invalid_grant)"));
            PlatformChange change = buildChange("budget", "campaign",
                    UUID.randomUUID().toString(), "50.0");

            PlatformWriteResult result = connector.submit(buildContext(), change);

            assertThat(result.accepted()).isFalse();
            assertThat(result.retryable()).isFalse();
            assertThat(result.platformErrorCode())
                    .isEqualTo(GoogleAdsWriteConnector.ERROR_TOKEN_INVALID);
        }

        @Test
        @DisplayName("blank customerId returns permanentReject INVALID_CUSTOMER_ID")
        void blankCustomerId() {
            mockMappingFound();
            ConnectionContext ctx = new ConnectionContext(CONNECTION_ID, STORE_ID, "google_ads",
                    Map.of("developerToken", "dev-token", "refreshToken", "1//r",
                            "clientId", "c", "clientSecret", "s"));
            PlatformChange change = buildChange("budget", "campaign",
                    UUID.randomUUID().toString(), "50.0");

            PlatformWriteResult result = connector.submit(ctx, change);

            assertThat(result.platformErrorCode())
                    .isEqualTo(GoogleAdsWriteConnector.ERROR_INVALID_CUSTOMER_ID);
        }
    }

    // =========================================================================
    // Single-call submit via subclass hook → 2xx accepted
    // =========================================================================
    @Test
    @DisplayName("successful 2xx submit returns accepted with resource name and exactly one HTTP call")
    void successfulSubmit() {
        mockMappingFound();
        mockTokenOk();
        mockSanitizer();

        int[] calls = {0};
        GoogleAdsWriteConnector spyConnector = new GoogleAdsWriteConnector(
                tokenClient, externalEntityMappingMapper, objectMapper, logSanitizer, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)) {
            @Override
            protected String doExecuteHttp(String url, String accessToken, ConnectionContext ctx, String requestBody) {
                calls[0]++;
                return "{\"results\":[{\"resourceName\":\"customers/1234567890/campaignBudgets/987654321\"}]}";
            }
        };

        PlatformChange change = buildChange("budget", "campaign",
                UUID.randomUUID().toString(), "50.0");
        PlatformWriteResult result = spyConnector.submit(buildContext(), change);

        assertThat(calls[0]).isEqualTo(1);
        assertThat(result.accepted()).isTrue();
        assertThat(result.platformReference())
                .isEqualTo("customers/1234567890/campaignBudgets/987654321");
        assertThat(result.externalEntityId()).isEqualTo(EXTERNAL_ID);
    }

    // =========================================================================
    // Response classification
    // =========================================================================
    @Nested
    @DisplayName("Response classification (2xx/429/5xx/4xx/401)")
    class ResponseClassification {

        @Test
        @DisplayName("429 → retryable")
        void rateLimited() {
            PlatformWriteResult r = connector.mapErrorResponse(429, "{}");
            assertThat(r.accepted()).isFalse();
            assertThat(r.retryable()).isTrue();
            assertThat(r.retryAfterSeconds()).isPositive();
        }

        @Test
        @DisplayName("500/502/503 → retryable")
        void serverErrors() {
            assertThat(connector.mapErrorResponse(500, "{}").retryable()).isTrue();
            assertThat(connector.mapErrorResponse(502, "{}").retryable()).isTrue();
            assertThat(connector.mapErrorResponse(503, "{}").retryable()).isTrue();
        }

        @Test
        @DisplayName("401 → permanentReject TOKEN_INVALID")
        void unauthorized() {
            PlatformWriteResult r = connector.mapErrorResponse(401, "{}");
            assertThat(r.accepted()).isFalse();
            assertThat(r.retryable()).isFalse();
            assertThat(r.platformErrorCode()).isEqualTo(GoogleAdsWriteConnector.ERROR_TOKEN_INVALID);
        }

        @Test
        @DisplayName("4xx → permanentReject carrying the platform error code")
        void clientError() {
            String body = "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\"bad bid\"}}";
            PlatformWriteResult r = connector.mapErrorResponse(400, body);
            assertThat(r.accepted()).isFalse();
            assertThat(r.retryable()).isFalse();
            assertThat(r.platformErrorCode()).isEqualTo("INVALID_ARGUMENT");
            assertThat(r.message()).isEqualTo("bad bid");
        }

        @Test
        @DisplayName("other 5xx (e.g. 504) → retryable")
        void otherServerError() {
            assertThat(connector.mapErrorResponse(504, "{}").retryable()).isTrue();
        }
    }

    // =========================================================================
    // Request-body building
    // =========================================================================
    @Nested
    @DisplayName("Request-body building")
    class RequestBody {

        @Test
        @DisplayName("budget body sets amountMicros and updateMask")
        void budgetBody() throws Exception {
            String body = connector.buildRequestBody("budget", CUSTOMER_ID, EXTERNAL_ID,
                    buildChange("budget", "campaign", EXTERNAL_ID, "50.0"));
            JsonNode op = objectMapper.readTree(body).path("operations").get(0);
            assertThat(op.path("updateMask").asText()).isEqualTo("amountMicros");
            assertThat(op.path("update").path("amountMicros").asLong()).isEqualTo(50_000_000L);
            assertThat(op.path("update").path("resourceName").asText())
                    .isEqualTo("customers/1234567890/campaignBudgets/987654321");
        }

        @Test
        @DisplayName("bid body sets cpcBidMicros and updateMask")
        void bidBody() throws Exception {
            String body = connector.buildRequestBody("bid", CUSTOMER_ID, EXTERNAL_ID,
                    buildChange("bid", "keyword", EXTERNAL_ID, "2.5"));
            JsonNode op = objectMapper.readTree(body).path("operations").get(0);
            assertThat(op.path("updateMask").asText()).isEqualTo("cpcBidMicros");
            assertThat(op.path("update").path("cpcBidMicros").asLong()).isEqualTo(2_500_000L);
        }

        @Test
        @DisplayName("state body normalizes status and sets updateMask")
        void stateBody() throws Exception {
            String body = connector.buildRequestBody("state", CUSTOMER_ID, EXTERNAL_ID,
                    buildChange("state", "campaign", EXTERNAL_ID, "paused"));
            JsonNode op = objectMapper.readTree(body).path("operations").get(0);
            assertThat(op.path("updateMask").asText()).isEqualTo("status");
            assertThat(op.path("update").path("status").asText()).isEqualTo("PAUSED");
        }
    }

    // =========================================================================
    // Helper conversions
    // =========================================================================
    @Nested
    @DisplayName("Helper conversions")
    class Helpers {

        @Test
        @DisplayName("toMicros multiplies and rounds half-up; invalid → 0")
        void micros() {
            assertThat(GoogleAdsWriteConnector.toMicros("1")).isEqualTo(1_000_000L);
            assertThat(GoogleAdsWriteConnector.toMicros("1.23")).isEqualTo(1_230_000L);
            assertThat(GoogleAdsWriteConnector.toMicros("0.0000005")).isEqualTo(1L);
            assertThat(GoogleAdsWriteConnector.toMicros(null)).isZero();
            assertThat(GoogleAdsWriteConnector.toMicros("abc")).isZero();
        }

        @Test
        @DisplayName("campaign status normalization")
        void statusNormalize() {
            assertThat(GoogleAdsWriteConnector.normalizeCampaignStatus("enabled")).isEqualTo("ENABLED");
            assertThat(GoogleAdsWriteConnector.normalizeCampaignStatus("pause")).isEqualTo("PAUSED");
            assertThat(GoogleAdsWriteConnector.normalizeCampaignStatus("archived")).isEqualTo("REMOVED");
            assertThat(GoogleAdsWriteConnector.normalizeCampaignStatus(null)).isEqualTo("PAUSED");
        }
    }

    // =========================================================================
    // Status mapping
    // =========================================================================
    @Nested
    @DisplayName("mapPlatformStatus")
    class StatusMapping {

        @Test
        @DisplayName("active entity statuses map to EFFECTIVE")
        void effective() {
            assertThat(connector.mapPlatformStatus("ENABLED")).isEqualTo(SyncState.EFFECTIVE);
            assertThat(connector.mapPlatformStatus("PAUSED")).isEqualTo(SyncState.EFFECTIVE);
            assertThat(connector.mapPlatformStatus("REMOVED")).isEqualTo(SyncState.EFFECTIVE);
        }

        @Test
        @DisplayName("failure statuses map to FAILED")
        void failed() {
            assertThat(connector.mapPlatformStatus("FAILED")).isEqualTo(SyncState.FAILED);
            assertThat(connector.mapPlatformStatus("REJECTED")).isEqualTo(SyncState.FAILED);
        }

        @Test
        @DisplayName("null/unknown status maps to RECONCILIATION_REQUIRED")
        void unknown() {
            assertThat(connector.mapPlatformStatus(null)).isEqualTo(SyncState.RECONCILIATION_REQUIRED);
            assertThat(connector.mapPlatformStatus("wat")).isEqualTo(SyncState.RECONCILIATION_REQUIRED);
        }
    }
}
