package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.connector.PlatformConnector;
import com.adpilot.modules.apisync.connector.ShopifyConnector;
import com.adpilot.modules.apisync.connector.WooCommerceConnector;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test for the rejected-credentials failure path through
 * {@link SyncJobRunnerImpl} (Req 1.1.5). A real {@link PlatformConnector} runs
 * its credential pre-check against a local {@link MockApiServer}; when the mock
 * platform rejects the credentials with HTTP 401, the runner is expected to mark
 * the sync job as {@code failed} and record the failure reason.
 *
 * <p>The mappers and pipeline collaborators are mocked so the failure handling
 * is exercised without a database; only the credential-check HTTP call is real.
 * Because the pre-check fails, no records are pulled, upserted, or watermarked.</p>
 */
class SyncJobRunnerCredentialRejectionIntegrationTest {

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
        cryptoUtil = mock(CryptoUtil.class);
        objectMapper = new ObjectMapper();

        server = MockApiServer.http();

        // Real credential checker, with its outbound HTTP redirected to the mock
        // server. WooCommerce's credential test hits /wp-json/wc/v3/products.
        platformConnector = new PlatformConnector(new com.adpilot.common.config.HttpClientFactory(10, 30, 60));
        MockHttpRedirect.redirect(platformConnector, server.host());

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
                List.of(new WooCommerceConnector(objectMapper, new com.adpilot.common.config.HttpClientFactory(10, 30, 60)), new ShopifyConnector(objectMapper, new com.adpilot.common.config.HttpClientFactory(10, 30, 60))),
                4, 100);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private PlatformConnectionEntity connection() throws Exception {
        Map<String, String> creds = new LinkedHashMap<>();
        creds.put("siteUrl", "http://store.example.com");
        creds.put("consumerKey", "ck_bad");
        creds.put("consumerSecret", "cs_bad");
        String configJson = objectMapper.writeValueAsString(creds);
        return PlatformConnectionEntity.builder()
                .id(connectionId)
                .storeId(storeId)
                .platform("woocommerce")
                .status("connected")
                .configEncrypted(configJson)
                .build();
    }

    private ApiSyncJobEntity runningJob() {
        return ApiSyncJobEntity.builder()
                .id(jobId)
                .connectionId(connectionId)
                .syncType("incremental")
                .entityType(ENTITY_TYPE)
                .status("running")
                .totalRecords(0)
                .recordsProcessed(0)
                .failedRecords(0)
                .build();
    }

    @Test
    void rejectedCredentialsMarkJobFailedWithReason() throws Exception {
        // The mock platform rejects the stored credentials.
        server.route("/wp-json/wc/v3/products", query -> MockApiServer.Response.json(401,
                "{\"code\":\"woocommerce_rest_authentication_error\","
                        + "\"message\":\"Consumer key is invalid.\"}"));

        when(apiSyncJobMapper.selectById(jobId)).thenReturn(runningJob());
        when(platformConnectionMapper.selectById(connectionId)).thenReturn(connection());
        when(cryptoUtil.decrypt(any(String.class))).thenAnswer(inv -> inv.getArgument(0));
        when(watermarkStore.get(storeId, ENTITY_TYPE)).thenReturn(Optional.empty());

        runner.execute(jobId);

        // Req 1.1.5: the job is marked failed with a recorded failure reason.
        var jobCaptor = forClass(ApiSyncJobEntity.class);
        verify(apiSyncJobMapper).updateById(jobCaptor.capture());
        ApiSyncJobEntity failed = jobCaptor.getValue();
        assertThat(failed.getStatus()).isEqualTo("failed");
        assertThat(failed.getErrorMessage())
                .as("the failure reason should indicate rejected credentials")
                .isNotBlank()
                .contains("Credentials rejected");
        assertThat(failed.getCompletedAt()).isNotNull();

        // The credential check ran against the mock platform before any pull.
        assertThat(server.lastRequestTo("/wp-json/wc/v3/products")).isNotNull();

        // No records were pulled, upserted, or watermarked on the rejected path.
        verify(upsertService, never()).upsert(any(), any());
        verify(watermarkStore, never()).advance(any(), any(), any());
    }
}
