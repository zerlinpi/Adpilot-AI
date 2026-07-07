package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.connector.AmazonLwaClient;
import com.adpilot.modules.apisync.connector.AmazonSpApiConnector;
import com.adpilot.modules.apisync.connector.PlatformConnector;
import com.adpilot.modules.apisync.entity.ApiSyncJobEntity;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.ApiSyncJobMapper;
import com.adpilot.modules.apisync.mapper.ApiSyncLogMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.mapper.SyncRecordErrorMapper;
import com.adpilot.modules.apisync.service.DataQualityValidator;
import com.adpilot.modules.apisync.service.RecordMapper;
import com.adpilot.modules.apisync.service.UpsertService;
import com.adpilot.modules.apisync.service.WatermarkStore;
import com.adpilot.modules.apisync.support.MockApiServer;
import com.adpilot.modules.apisync.support.MockHttpRedirect;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test for the Amazon expired/invalid-token path through
 * {@link SyncJobRunnerImpl} (Req 8.1.5). A real {@link AmazonSpApiConnector}
 * runs against a local {@link MockApiServer}; when the server rejects the access
 * token with HTTP 401 the connector raises {@code ReauthRequiredException}, and
 * the runner is expected to record the failure reason on the job and mark the
 * originating connection's status as {@code requires_reauth}.
 *
 * <p>The mappers and pipeline collaborators are mocked so the re-auth handling
 * is exercised without a database; only the connector's HTTP calls are real.</p>
 */
class SyncJobRunnerAmazonReauthIntegrationTest {

    private static final String PLATFORM = "amazon_sp_api";
    private static final String ENTITY_TYPE = "order";

    private final UUID connectionId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    private PlatformConnectionMapper platformConnectionMapper;
    private ApiSyncJobMapper apiSyncJobMapper;
    private ApiSyncLogMapper apiSyncLogMapper;
    private SyncRecordErrorMapper syncRecordErrorMapper;
    private RecordMapper recordMapper;
    private DataQualityValidator dataQualityValidator;
    private UpsertService upsertService;
    private WatermarkStore watermarkStore;
    private PlatformConnector platformConnector;
    private CryptoUtil cryptoUtil;
    private ObjectMapper objectMapper;

    private MockApiServer server;
    private SyncJobRunnerImpl runner;

    @BeforeEach
    void setUp() throws IOException {
        platformConnectionMapper = mock(PlatformConnectionMapper.class);
        apiSyncJobMapper = mock(ApiSyncJobMapper.class);
        apiSyncLogMapper = mock(ApiSyncLogMapper.class);
        syncRecordErrorMapper = mock(SyncRecordErrorMapper.class);
        recordMapper = mock(RecordMapper.class);
        dataQualityValidator = mock(DataQualityValidator.class);
        upsertService = mock(UpsertService.class);
        watermarkStore = mock(WatermarkStore.class);
        platformConnector = mock(PlatformConnector.class);
        cryptoUtil = mock(CryptoUtil.class);
        objectMapper = new ObjectMapper();

        server = MockApiServer.http();
        // Valid LWA token, but the orders endpoint rejects it as expired (Req 8.1.5).
        server.route("/auth/o2/token", query -> MockApiServer.Response.json(200,
                "{\"access_token\":\"ACCESS-TOKEN\",\"token_type\":\"bearer\",\"expires_in\":3600}"));
        server.route("/orders/v0/orders", query -> MockApiServer.Response.json(401,
                "{\"errors\":[{\"code\":\"Unauthorized\",\"message\":\"Access token expired\"}]}"));

        AmazonLwaClient lwaClient = new AmazonLwaClient(objectMapper, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));
        AmazonSpApiConnector spApi = new AmazonSpApiConnector(objectMapper, lwaClient, new com.adpilot.common.config.HttpClientFactory(10, 30, 60));
        MockHttpRedirect.redirect(lwaClient, server.host());
        MockHttpRedirect.redirect(spApi, server.host());

        runner = new SyncJobRunnerImpl(
                platformConnectionMapper,
                apiSyncJobMapper,
                apiSyncLogMapper,
                syncRecordErrorMapper,
                recordMapper,
                dataQualityValidator,
                upsertService,
                watermarkStore,
                platformConnector,
                cryptoUtil,
                objectMapper,
                List.of(spApi),
                4, 100);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private PlatformConnectionEntity connection() throws Exception {
        Map<String, String> creds = new LinkedHashMap<>();
        creds.put("refreshToken", "refresh-abc");
        creds.put("clientId", "amzn1.application.client");
        creds.put("clientSecret", "client-secret");
        creds.put("marketplaceId", "ATVPDKIKX0DER");
        creds.put("region", "na");
        String configJson = objectMapper.writeValueAsString(creds);
        return PlatformConnectionEntity.builder()
                .id(connectionId)
                .storeId(storeId)
                .platform(PLATFORM)
                .status("connected")
                .configEncrypted(configJson)
                .build();
    }

    @Test
    void expiredAmazonTokenMarksConnectionRequiresReauthAndFailsJob() throws Exception {
        ApiSyncJobEntity job = ApiSyncJobEntity.builder()
                .id(jobId)
                .connectionId(connectionId)
                .syncType("incremental")
                .entityType(ENTITY_TYPE)
                .status("running")
                .totalRecords(0)
                .recordsProcessed(0)
                .failedRecords(0)
                .build();

        when(apiSyncJobMapper.selectById(jobId)).thenReturn(job);
        when(platformConnectionMapper.selectById(connectionId)).thenReturn(connection());
        // Stored credentials are "encrypted"; decrypt is identity for the test.
        when(cryptoUtil.decrypt(any(String.class))).thenAnswer(inv -> inv.getArgument(0));
        when(watermarkStore.get(storeId, ENTITY_TYPE)).thenReturn(Optional.empty());

        runner.execute(jobId);

        // Req 8.1.5: the connection was marked as requiring re-authorization.
        var connCaptor = forClass(PlatformConnectionEntity.class);
        verify(platformConnectionMapper).updateById(connCaptor.capture());
        PlatformConnectionEntity connUpdate = connCaptor.getValue();
        assertThat(connUpdate.getId()).isEqualTo(connectionId);
        assertThat(connUpdate.getStatus()).isEqualTo("requires_reauth");

        // Req 8.1.5: the job is failed with the recorded (secret-free) reason.
        var jobCaptor = forClass(ApiSyncJobEntity.class);
        verify(apiSyncJobMapper, atLeastOnce()).updateById(jobCaptor.capture());
        ApiSyncJobEntity failed = jobCaptor.getAllValues().stream()
                .filter(u -> "failed".equals(u.getStatus()))
                .reduce((first, second) -> second)
                .orElse(null);
        assertThat(failed).as("the job should be marked failed").isNotNull();
        assertThat(failed.getErrorMessage()).isNotBlank();

        // A self-validating Amazon connector skips the generic credential pre-check.
        verify(platformConnector, never()).test(any());
        // No records were upserted on the re-auth path.
        verify(upsertService, never()).upsert(any(), any());
    }
}
