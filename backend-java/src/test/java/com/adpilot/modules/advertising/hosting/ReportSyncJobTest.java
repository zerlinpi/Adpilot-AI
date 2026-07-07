package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.resilience.CircuitBreaker;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.feishu.service.FeishuService;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.MarketplaceReferenceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportSyncJob} concurrency + resolution behavior
 * (optimization M4). Verifies that:
 *
 * <ul>
 *   <li>each store is processed within a cadence tick, and one store failing
 *       does not abort the others (per-store exception isolation on the bounded
 *       worker pool);</li>
 *   <li>the connection context and marketplace timezone are resolved ONCE per
 *       store per tick (not once per store × report type).</li>
 * </ul>
 *
 * <p>Data-access collaborators are mocked in the repo's established Mockito
 * style. The {@code @Value} fields are set via reflection and the worker pool is
 * built by invoking {@link ReportSyncJob#init()} directly (no Spring context).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReportSyncJob — bounded per-store parallelism + resolve-once (M4)")
class ReportSyncJobTest {

    private static final int REPORT_TYPE_COUNT = ReportType.values().length; // 3

    @Mock private ReportLifecycleClient reportLifecycleClient;
    @Mock private ReportIngestionService reportIngestionService;
    @Mock private ReportSyncRunMapper reportSyncRunMapper;
    @Mock private ReportSyncErrorMapper reportSyncErrorMapper;
    @Mock private PlatformConnectionMapper connectionMapper;
    @Mock private StoreMapper storeMapper;
    @Mock private MarketplaceReferenceService marketplaceReferenceService;
    @Mock private FeishuService feishuService;
    @Mock private CryptoUtil cryptoUtil;

    private ReportSyncJob job;

    private final UUID storeA = UUID.randomUUID();
    private final UUID storeB = UUID.randomUUID();
    private final UUID marketplaceId = UUID.randomUUID();

    private PlatformConnectionEntity connA;
    private PlatformConnectionEntity connB;

    @BeforeAll
    static void initTableInfo() {
        // findStoresWithAmazonAdsConnection builds a LambdaQueryWrapper with a
        // .select(...) lambda column reference, which needs MyBatis-Plus entity
        // metadata normally populated during mapper scanning. Register it for this
        // standalone (no Spring/MyBatis context) test.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PlatformConnectionEntity.class);
    }

    @BeforeEach
    void setUp() {
        job = new ReportSyncJob(reportLifecycleClient, reportIngestionService, reportSyncRunMapper,
                reportSyncErrorMapper, connectionMapper, storeMapper, marketplaceReferenceService,
                feishuService, cryptoUtil, new CircuitBreaker(false, 5, 30));

        ReflectionTestUtils.setField(job, "enabled", true);
        ReflectionTestUtils.setField(job, "finalizationLagDays", 3);
        ReflectionTestUtils.setField(job, "maxParallelStores", 2);
        job.init(); // build the bounded worker pool (normally @PostConstruct)

        connA = PlatformConnectionEntity.builder()
                .id(UUID.randomUUID()).storeId(storeA).platform("amazon_ads").status("connected")
                .refreshTokenEncrypted("ENC_A").profileId("profA").region("NA").build();
        connB = PlatformConnectionEntity.builder()
                .id(UUID.randomUUID()).storeId(storeB).platform("amazon_ads").status("connected")
                .refreshTokenEncrypted("ENC_B").profileId("profB").region("NA").build();

        // connectionMapper.selectList serves both the store-discovery query (no storeId
        // predicate -> returns both connections) and the per-store connection query
        // (storeId predicate -> returns that store's single connection).
        when(connectionMapper.selectList(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<PlatformConnectionEntity> w = inv.getArgument(0);
            // MyBatis-Plus materialises bound parameter values lazily while building
            // the SQL segment, so force segment generation before reading the pairs.
            w.getTargetSql();
            Collection<Object> values = w.getParamNameValuePairs().values();
            if (values.contains(storeA)) {
                return List.of(connA);
            }
            if (values.contains(storeB)) {
                return List.of(connB);
            }
            return List.of(connA, connB); // discovery query
        });

        // Marketplace timezone resolution (once per store when reached). The store
        // lookup still resolves the store's marketplace id; the timezone itself is
        // delegated to the shared, cached MarketplaceReferenceService.
        StoreEntity store = StoreEntity.builder().marketplaceId(marketplaceId).build();
        when(storeMapper.selectById(any())).thenReturn(store);
        when(marketplaceReferenceService.timezoneForMarketplace(any())).thenReturn(ZoneId.of("UTC"));
    }

    @Test
    @DisplayName("intradaySync processes every store and resolves connection/timezone once per store")
    void intradaySyncProcessesEachStoreResolvingOncePerStore() {
        when(cryptoUtil.decrypt(any())).thenReturn("token");
        when(reportLifecycleClient.executeLifecycle(any(), any(), any()))
                .thenReturn(lifecycleResult());
        when(reportIngestionService.ingest(any(), any(), anyInt()))
                .thenReturn(IngestionResult.empty());

        job.intradaySync();

        // (a) every store processed: one lifecycle execution per store × report type.
        ArgumentCaptor<ConnectionContext> ctxCaptor = ArgumentCaptor.forClass(ConnectionContext.class);
        verify(reportLifecycleClient, times(2 * REPORT_TYPE_COUNT))
                .executeLifecycle(ctxCaptor.capture(), any(), any());
        assertThat(ctxCaptor.getAllValues())
                .extracting(ConnectionContext::storeId)
                .containsOnly(storeA, storeB);

        // ingestion invoked once per store × report type as well
        verify(reportIngestionService, times(2 * REPORT_TYPE_COUNT))
                .ingest(any(), any(), anyInt());

        // (b) connection resolved once per store: 1 discovery query + 1 per store = 3,
        // NOT 1 + (store × report type) = 7.
        verify(connectionMapper, times(1 + 2)).selectList(any());

        // timezone resolved once per store (not once per store × report type)
        verify(storeMapper, times(2)).selectById(any());
        verify(marketplaceReferenceService, times(2)).timezoneForMarketplace(any());
    }

    @Test
    @DisplayName("a single store failing does not prevent the other stores from being processed")
    void oneStoreFailingDoesNotAbortOthers() {
        // Store A fails hard during connection resolution; store B is healthy.
        when(cryptoUtil.decrypt("ENC_A")).thenThrow(new RuntimeException("boom decrypting store A"));
        when(cryptoUtil.decrypt("ENC_B")).thenReturn("token");
        when(reportLifecycleClient.executeLifecycle(any(), any(), any()))
                .thenReturn(lifecycleResult());
        when(reportIngestionService.ingest(any(), any(), anyInt()))
                .thenReturn(IngestionResult.empty());

        job.intradaySync();

        // Store B is still fully processed despite store A throwing.
        ArgumentCaptor<ConnectionContext> ctxCaptor = ArgumentCaptor.forClass(ConnectionContext.class);
        verify(reportLifecycleClient, times(REPORT_TYPE_COUNT))
                .executeLifecycle(ctxCaptor.capture(), any(), any());
        assertThat(ctxCaptor.getAllValues())
                .extracting(ConnectionContext::storeId)
                .containsOnly(storeB);
    }

    private ReportLifecycleResult lifecycleResult() {
        LocalDate today = LocalDate.now();
        return new ReportLifecycleResult("report-1", ReportType.SP_CAMPAIGN, today, today, List.of(), 0);
    }
}
