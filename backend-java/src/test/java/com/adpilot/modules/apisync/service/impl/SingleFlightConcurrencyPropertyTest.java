package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.exception.BusinessException;
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
import com.adpilot.modules.apisync.model.PageCursor;
import com.adpilot.modules.apisync.service.DataQualityValidator;
import com.adpilot.modules.apisync.service.RecordMapper;
import com.adpilot.modules.apisync.service.UpsertService;
import com.adpilot.modules.apisync.service.WatermarkStore;
import com.adpilot.modules.apisync.vo.ApiSyncJobVo;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link SyncJobRunnerImpl}'s single-flight enforcement.
 *
 * Feature: core-platform-completion, Property 8: At most one running sync per
 * store and entity type.
 *
 * <p>For any sequence of {@code startSync} requests for the <em>same</em>
 * {@code (store, entityType)} pair, at no point are two jobs simultaneously in
 * the running state for that pair; the design rejects the additional requests
 * (HTTP 409 / {@code SYNC_IN_PROGRESS}).</p>
 *
 * <p>The test fires a generated number of {@code startSync} calls
 * <em>concurrently</em> for one {@code (store, entityType)} while the winning
 * job's pipeline is pinned in the running state by a gate. It asserts that:</p>
 * <ul>
 *   <li>exactly one request wins and starts a job (the rest are rejected),</li>
 *   <li>every rejection is a {@code SYNC_IN_PROGRESS} business error, and</li>
 *   <li>the observed maximum number of simultaneously running pipelines never
 *       exceeds one.</li>
 * </ul>
 * It then releases the gate and confirms the lock is released and reusable: a
 * subsequent {@code startSync} for the same pair succeeds.
 *
 * <p>All mappers and pipeline collaborators are mocked. The single-flight guard
 * is exercised purely through the runner's in-memory reservation: the defensive
 * DB running-job check is wired to report no running rows so the reservation is
 * the sole arbiter under concurrency. A gate hooked into the async pipeline's
 * first {@code selectById} pins the winning job in the running state, then
 * returns {@code null} so the pipeline exits cleanly and releases the lock.</p>
 *
 * Validates: Requirements 1.3.6
 */
class SingleFlightConcurrencyPropertyTest {

    private static final String PLATFORM = "woocommerce";
    private static final String ENTITY_TYPE = "order";

    private final UUID connectionId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID triggeredBy = UUID.randomUUID();

    private PlatformConnectionMapper platformConnectionMapper;
    private ApiSyncJobMapper apiSyncJobMapper;
    private RecordMapper recordMapper;
    private DataQualityValidator dataQualityValidator;
    private UpsertService upsertService;
    private WatermarkStore watermarkStore;
    private PlatformConnector platformConnector;

    private SyncJobRunnerImpl runner;

    /**
     * Number of pipelines currently in the running state, and the running max.
     *
     * <p>Re-created fresh for every try (not merely reset) so a straggler pipeline
     * left over from a previous try — still executing on that try's now-defunct
     * runner/executor — holds references to the previous try's counters and gate
     * and can never perturb the current try's measurements. The async pipeline mock
     * below closes over the per-try instances rather than these fields.
     */
    private AtomicInteger running = new AtomicInteger(0);
    private AtomicInteger maxRunning = new AtomicInteger(0);

    /** Releasing this gate lets pinned pipelines finish and free the lock (per-try). */
    private AtomicReference<CountDownLatch> gate = new AtomicReference<>();

    static {
        // The single-flight DB guard builds MyBatis-Plus LambdaQueryWrappers whose
        // .select(...) eagerly resolves column metadata; register it outside Spring.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PlatformConnectionEntity.class);
        TableInfoHelper.initTableInfo(assistant, ApiSyncJobEntity.class);
    }

    @BeforeTry
    void setUp() {
        platformConnectionMapper = mock(PlatformConnectionMapper.class);
        apiSyncJobMapper = mock(ApiSyncJobMapper.class);
        ApiSyncLogMapper apiSyncLogMapper = mock(ApiSyncLogMapper.class);
        SyncRecordErrorMapper syncRecordErrorMapper = mock(SyncRecordErrorMapper.class);
        recordMapper = mock(RecordMapper.class);
        dataQualityValidator = mock(DataQualityValidator.class);
        upsertService = mock(UpsertService.class);
        watermarkStore = mock(WatermarkStore.class);
        platformConnector = mock(PlatformConnector.class);
        CryptoUtil cryptoUtil = mock(CryptoUtil.class);

        // Fresh per-try state so a straggler pipeline from a previous try cannot
        // touch this try's counters/gate (the mock below closes over these locals).
        this.running = new AtomicInteger(0);
        this.maxRunning = new AtomicInteger(0);
        this.gate = new AtomicReference<>();
        final AtomicInteger running = this.running;
        final AtomicInteger maxRunning = this.maxRunning;
        final AtomicReference<CountDownLatch> gate = this.gate;

        PlatformConnectionEntity connection = PlatformConnectionEntity.builder()
                .id(connectionId)
                .storeId(storeId)
                .platform(PLATFORM)
                .status("connected")
                .configEncrypted(null)
                .build();
        when(platformConnectionMapper.selectById(connectionId)).thenReturn(connection);

        // Defensive DB running-job check: the store has this connection, and no DB
        // row is currently running, so the in-memory reservation is the sole arbiter.
        when(platformConnectionMapper.selectList(any())).thenReturn(List.of(connection));
        when(apiSyncJobMapper.selectCount(any())).thenReturn(0L);

        // insert assigns a generated id the way MyBatis-Plus would.
        when(apiSyncJobMapper.insert(any(ApiSyncJobEntity.class))).thenAnswer(inv -> {
            ApiSyncJobEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return 1;
        });

        // Hook the async pipeline's first read: mark the job running, wait on the
        // gate (pinning it in the running state), then return null so the pipeline
        // exits and the single-flight reservation is released.
        when(apiSyncJobMapper.selectById(any(UUID.class))).thenAnswer(inv -> {
            int now = running.incrementAndGet();
            maxRunning.accumulateAndGet(now, Math::max);
            try {
                CountDownLatch g = gate.get();
                if (g != null) {
                    g.await(5, TimeUnit.SECONDS);
                }
            } finally {
                running.decrementAndGet();
            }
            return null;
        });

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
                List.of(new StubDataConnector()),
                4, 100);
    }

    // Feature: core-platform-completion, Property 8: At most one running sync per store and entity type
    @Property(tries = 200)
    void atMostOneRunningSyncPerStoreAndEntityType(
            @ForAll @IntRange(min = 2, max = 12) int concurrentStarts) throws Exception {

        CountDownLatch g = new CountDownLatch(1);
        gate.set(g);

        // Fire all start requests at the same instant for the same (store, entityType).
        CyclicBarrier ready = new CyclicBarrier(concurrentStarts);
        ExecutorService pool = Executors.newFixedThreadPool(concurrentStarts);
        List<java.util.concurrent.Future<Outcome>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < concurrentStarts; i++) {
                futures.add(pool.submit(() -> {
                    ready.await(5, TimeUnit.SECONDS);
                    try {
                        ApiSyncJobVo vo = runner.startSync(connectionId, ENTITY_TYPE, false, triggeredBy);
                        return Outcome.success(vo);
                    } catch (BusinessException e) {
                        return Outcome.rejected(e);
                    }
                }));
            }

            int successes = 0;
            int rejections = 0;
            for (var future : futures) {
                Outcome outcome = future.get(10, TimeUnit.SECONDS);
                if (outcome.success) {
                    successes++;
                    assertThat(outcome.vo).isNotNull();
                    assertThat(outcome.vo.getStatus()).isEqualTo("running");
                } else {
                    rejections++;
                    // Extra concurrent requests are rejected with a single-flight error.
                    assertThat(outcome.error.getCode()).isEqualTo("SYNC_IN_PROGRESS");
                    assertThat(outcome.error.getStatus()).isEqualTo(409);
                }
            }

            // Exactly one request wins; all others are rejected (Req 1.3.6).
            assertThat(successes)
                    .as("at most one concurrent start may begin a running job")
                    .isEqualTo(1);
            assertThat(rejections).isEqualTo(concurrentStarts - 1);
        } finally {
            // Let the pinned pipeline finish and release the single-flight lock.
            g.countDown();
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        // At no point were two jobs simultaneously running for the pair (Req 1.3.6).
        assertThat(maxRunning.get())
                .as("never more than one running pipeline for the same (store, entityType)")
                .isLessThanOrEqualTo(1);

        // The lock is released and reusable: a later start for the same pair succeeds.
        ApiSyncJobVo reuse = startUntilAcquired();
        assertThat(reuse)
                .as("after the running job completes, a new sync may start for the same pair")
                .isNotNull();
        assertThat(reuse.getStatus()).isEqualTo("running");

        // Drain the reuse job too (gate is already open, so it completes immediately).
        awaitRunningZero();
        assertThat(maxRunning.get()).isLessThanOrEqualTo(1);
    }

    /** Retry startSync until the single-flight reservation has been released. */
    private ApiSyncJobVo startUntilAcquired() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        BusinessException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return runner.startSync(connectionId, ENTITY_TYPE, false, triggeredBy);
            } catch (BusinessException e) {
                last = e;
                Thread.sleep(2);
            }
        }
        throw new AssertionError("single-flight lock was never released for reuse", last);
    }

    private void awaitRunningZero() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (running.get() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
    }

    private static final class Outcome {
        final boolean success;
        final ApiSyncJobVo vo;
        final BusinessException error;

        private Outcome(boolean success, ApiSyncJobVo vo, BusinessException error) {
            this.success = success;
            this.vo = vo;
            this.error = error;
        }

        static Outcome success(ApiSyncJobVo vo) {
            return new Outcome(true, vo, null);
        }

        static Outcome rejected(BusinessException error) {
            return new Outcome(false, null, error);
        }
    }

    /** Minimal connector; the pipeline exits before any pull is attempted. */
    private static final class StubDataConnector implements PlatformDataConnector {
        @Override
        public String platform() {
            return PLATFORM;
        }

        @Override
        public ExternalPage pullOrders(ConnectionContext ctx, Instant since, PageCursor cursor) {
            return ExternalPage.empty();
        }

        @Override
        public ExternalPage pullProducts(ConnectionContext ctx, Instant since, PageCursor cursor) {
            return ExternalPage.empty();
        }
    }
}
