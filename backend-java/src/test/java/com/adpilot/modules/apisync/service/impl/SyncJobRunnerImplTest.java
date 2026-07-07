package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.connector.PlatformConnector;
import com.adpilot.modules.apisync.connector.PlatformDataConnector;
import com.adpilot.modules.apisync.entity.ApiSyncJobEntity;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.ApiSyncJobMapper;
import com.adpilot.modules.apisync.mapper.ApiSyncLogMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.mapper.SyncRecordErrorMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.MappedRecord;
import com.adpilot.modules.apisync.model.PageCursor;
import com.adpilot.modules.apisync.model.SyncContext;
import com.adpilot.modules.apisync.model.UpsertResult;
import com.adpilot.modules.apisync.model.ValidationResult;
import com.adpilot.modules.apisync.service.DataQualityValidator;
import com.adpilot.modules.apisync.service.RecordMapper;
import com.adpilot.modules.apisync.service.UpsertService;
import com.adpilot.modules.apisync.service.WatermarkStore;
import com.adpilot.modules.apisync.vo.ApiSyncJobVo;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentCaptor.forClass;

/**
 * Unit tests for {@link SyncJobRunnerImpl} covering the job lifecycle and
 * on-demand creation.
 *
 * <ul>
 *   <li>Req 1.3.1 — a started job is recorded as {@code running} with its start time.</li>
 *   <li>Req 1.3.3 — a job that finishes successfully is recorded as {@code completed}
 *       with its completion time.</li>
 *   <li>Req 1.1.2 — triggering an on-demand sync creates a sync job for the
 *       store's connection.</li>
 * </ul>
 *
 * <p>The mappers and pipeline collaborators are mocked so the lifecycle
 * transitions are exercised without a database. A stub
 * {@link PlatformDataConnector} feeds the runner a controllable page of records.</p>
 */
class SyncJobRunnerImplTest {

    private static final String PLATFORM = "woocommerce";
    private static final String ENTITY_TYPE = "order";

    private final UUID connectionId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID triggeredBy = UUID.randomUUID();

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

    private StubDataConnector stubConnector;
    private SyncJobRunnerImpl runner;

    @BeforeAll
    static void initTableInfo() {
        // The runner's single-flight guard builds MyBatis-Plus LambdaQueryWrappers
        // whose .select(...) eagerly resolves column metadata. Outside a Spring
        // context that metadata must be registered explicitly.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PlatformConnectionEntity.class);
        TableInfoHelper.initTableInfo(assistant, ApiSyncJobEntity.class);
    }

    @BeforeEach
    void setUp() {
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

        stubConnector = new StubDataConnector();

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
                new ObjectMapper(),
                List.of(stubConnector),
                4, 100);

        // Insert assigns a generated id the way MyBatis-Plus would.
        when(apiSyncJobMapper.insert(any(ApiSyncJobEntity.class))).thenAnswer(inv -> {
            ApiSyncJobEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return 1;
        });
    }

    private PlatformConnectionEntity connection() {
        return PlatformConnectionEntity.builder()
                .id(connectionId)
                .storeId(storeId)
                .platform(PLATFORM)
                .status("connected")
                .configEncrypted(null)
                .build();
    }

    // ── Req 1.3.1 / 1.1.2: start records running + start time, on-demand creation ──

    @Test
    void startSyncRecordsRunningStatusAndStartTime() throws InterruptedException {
        when(platformConnectionMapper.selectById(connectionId)).thenReturn(connection());

        // The async pipeline reads the job back; return null so it exits immediately,
        // and use a latch to await its completion before the test ends.
        CountDownLatch pipelineRan = new CountDownLatch(1);
        when(apiSyncJobMapper.selectById(any(UUID.class))).thenAnswer(inv -> {
            pipelineRan.countDown();
            return null;
        });

        ApiSyncJobVo vo = runner.startSync(connectionId, ENTITY_TYPE, false, triggeredBy);

        // Returned view reflects the running state with a recorded start time (Req 1.3.1).
        assertThat(vo).isNotNull();
        assertThat(vo.getStatus()).isEqualTo("running");
        assertThat(vo.getStartedAt()).isNotNull();

        // The persisted job row is running with a start time set (Req 1.3.1).
        var captor = forClass(ApiSyncJobEntity.class);
        verify(apiSyncJobMapper).insert(captor.capture());
        ApiSyncJobEntity inserted = captor.getValue();
        assertThat(inserted.getStatus()).isEqualTo("running");
        assertThat(inserted.getStartedAt()).isNotNull();

        assertThat(pipelineRan.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void startSyncCreatesOnDemandJobForTriggeringUser() throws InterruptedException {
        when(platformConnectionMapper.selectById(connectionId)).thenReturn(connection());

        CountDownLatch pipelineRan = new CountDownLatch(1);
        when(apiSyncJobMapper.selectById(any(UUID.class))).thenAnswer(inv -> {
            pipelineRan.countDown();
            return null;
        });

        ApiSyncJobVo vo = runner.startSync(connectionId, ENTITY_TYPE, false, triggeredBy);

        // A job was created for the requested connection/entity type (Req 1.1.2).
        assertThat(vo).isNotNull();
        assertThat(vo.getId()).isNotBlank();
        assertThat(vo.getConnectionId()).isEqualTo(connectionId.toString());
        assertThat(vo.getEntityType()).isEqualTo(ENTITY_TYPE);

        var captor = forClass(ApiSyncJobEntity.class);
        verify(apiSyncJobMapper).insert(captor.capture());
        ApiSyncJobEntity inserted = captor.getValue();
        assertThat(inserted.getConnectionId()).isEqualTo(connectionId);
        assertThat(inserted.getEntityType()).isEqualTo(ENTITY_TYPE);
        // The triggering user is recorded on the created job (on-demand creation).
        assertThat(inserted.getCreatedBy()).isEqualTo(triggeredBy);
        assertThat(inserted.getSyncType()).isEqualTo("incremental");

        assertThat(pipelineRan.await(2, TimeUnit.SECONDS)).isTrue();
    }

    // ── Req 1.3.3: successful execution transitions running → completed with completion time ──

    @Test
    void executeCompletesJobRecordingCompletedStatusAndCompletionTime() {
        UUID jobId = UUID.randomUUID();
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
        when(platformConnector.test(any(ConnectionContext.class)))
                .thenReturn(PlatformConnector.TestResult.ok("ok"));
        when(watermarkStore.get(storeId, ENTITY_TYPE)).thenReturn(Optional.empty());

        // One valid record that maps, validates, and upserts successfully.
        Instant changedAt = Instant.parse("2024-03-01T00:00:00Z");
        stubConnector.orderPage = ExternalPage.last(List.of(
                new ExternalRecord("ext-1", ENTITY_TYPE, changedAt, "completed", Map.of())));
        MappedRecord mapped = new MappedRecord("ext-1", ENTITY_TYPE, storeId, "completed", Map.of(), changedAt);
        when(recordMapper.map(any(SyncContext.class), any(ExternalRecord.class))).thenReturn(mapped);
        when(dataQualityValidator.validate(eq(ENTITY_TYPE), any(MappedRecord.class)))
                .thenReturn(ValidationResult.ok());
        when(upsertService.upsert(any(SyncContext.class), any(MappedRecord.class)))
                .thenReturn(UpsertResult.created(UUID.randomUUID()));

        runner.execute(jobId);

        // Capture all job updates and confirm a terminal completed update was written (Req 1.3.3).
        var captor = forClass(ApiSyncJobEntity.class);
        verify(apiSyncJobMapper, atLeastOnce()).updateById(captor.capture());
        AtomicReference<ApiSyncJobEntity> completed = new AtomicReference<>();
        for (ApiSyncJobEntity update : captor.getAllValues()) {
            if ("completed".equals(update.getStatus())) {
                completed.set(update);
            }
        }
        assertThat(completed.get())
                .as("a completed terminal update should be recorded")
                .isNotNull();
        assertThat(completed.get().getCompletedAt()).isNotNull();
        assertThat(completed.get().getRecordsProcessed()).isEqualTo(1);
        assertThat(completed.get().getFailedRecords()).isEqualTo(0);

        // Watermark advanced to the processed record's change timestamp (Req 1.1.8).
        verify(watermarkStore).advance(storeId, ENTITY_TYPE, changedAt);
        // The job never failed.
        for (ApiSyncJobEntity update : captor.getAllValues()) {
            assertThat(update.getStatus()).isNotEqualTo("failed");
        }
    }

    // ── M2: bounded executor construction is safe and still runs submitted jobs ──

    @Test
    void boundedRunnerConfigStillRunsSubmittedJob() throws InterruptedException {
        // Construct the runner with a minimal bounded pool/queue (M2 config-driven
        // executor). Construction must not fail and a submitted job must still run.
        SyncJobRunnerImpl boundedRunner = new SyncJobRunnerImpl(
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
                new ObjectMapper(),
                List.of(stubConnector),
                1, 1);

        when(platformConnectionMapper.selectById(connectionId)).thenReturn(connection());

        CountDownLatch pipelineRan = new CountDownLatch(1);
        when(apiSyncJobMapper.selectById(any(UUID.class))).thenAnswer(inv -> {
            pipelineRan.countDown();
            return null;
        });

        ApiSyncJobVo vo = boundedRunner.startSync(connectionId, ENTITY_TYPE, false, triggeredBy);

        assertThat(vo).isNotNull();
        assertThat(vo.getStatus()).isEqualTo("running");
        // The dispatched pipeline ran on the bounded executor.
        assertThat(pipelineRan.await(2, TimeUnit.SECONDS)).isTrue();
    }

    /** Stub connector that returns a configurable order page and no further pages. */
    private static final class StubDataConnector implements PlatformDataConnector {
        private ExternalPage orderPage = ExternalPage.empty();

        @Override
        public String platform() {
            return PLATFORM;
        }

        @Override
        public ExternalPage pullOrders(ConnectionContext ctx, Instant since, PageCursor cursor) {
            return orderPage;
        }

        @Override
        public ExternalPage pullProducts(ConnectionContext ctx, Instant since, PageCursor cursor) {
            return ExternalPage.empty();
        }
    }
}
