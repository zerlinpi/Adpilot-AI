package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.MetricQuarantineEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.entity.SearchTermDailyEntity;
import com.adpilot.modules.advertising.mapper.MetricQuarantineMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.mapper.SearchTermDailyMapper;
import com.adpilot.modules.apisync.entity.ExternalEntityMappingEntity;
import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Feature: amazon-ads-ai-hosting-system, Property 10: Backfill bumps data version
 *
 * <p><b>Validates: Requirements 2.4</b></p>
 *
 * <p>Verifies that the {@link ReportIngestionServiceImpl} backfill version bump logic:
 * <ol>
 *   <li>First ingestion creates a row with data_version=1</li>
 *   <li>Re-ingesting with CHANGED metrics increments data_version to 2</li>
 *   <li>Re-ingesting with SAME metrics does NOT increment data_version</li>
 *   <li>data_version is always a positive integer ≥ 1</li>
 *   <li>data_version increases monotonically (never decreases)</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 10: Backfill bumps data version")
class BackfillVersionBumpPropertyTest {

    static {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PerformanceDailyEntity.class);
        TableInfoHelper.initTableInfo(assistant, SearchTermDailyEntity.class);
        TableInfoHelper.initTableInfo(assistant, ExternalEntityMappingEntity.class);
        TableInfoHelper.initTableInfo(assistant, MetricQuarantineEntity.class);
    }

    private static final Pattern CONDITION_PATTERN =
            Pattern.compile("`?(\\w+)`?\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)}");

    // ── In-memory stores ─────────────────────────────────────────────────────────

    private static class InMemoryPerformanceStore {
        private final List<PerformanceDailyEntity> rows = new CopyOnWriteArrayList<>();

        void insert(PerformanceDailyEntity entity) {
            entity.setId(UUID.randomUUID());
            rows.add(entity);
        }

        void updateById(PerformanceDailyEntity entity) {
            rows.removeIf(r -> r.getId().equals(entity.getId()));
            rows.add(entity);
        }

        PerformanceDailyEntity findByKey(UUID storeId, String entityType, UUID entityId, LocalDate reportDate) {
            return rows.stream()
                    .filter(r -> Objects.equals(r.getStoreId(), storeId))
                    .filter(r -> Objects.equals(r.getEntityType(), entityType))
                    .filter(r -> Objects.equals(r.getEntityId(), entityId))
                    .filter(r -> Objects.equals(r.getDate(), reportDate))
                    .findFirst()
                    .orElse(null);
        }

        List<PerformanceDailyEntity> all() { return Collections.unmodifiableList(rows); }
    }

    private static class InMemorySearchTermStore {
        private final List<SearchTermDailyEntity> rows = new CopyOnWriteArrayList<>();

        void insert(SearchTermDailyEntity entity) {
            entity.setId(UUID.randomUUID());
            rows.add(entity);
        }

        void updateById(SearchTermDailyEntity entity) {
            rows.removeIf(r -> r.getId().equals(entity.getId()));
            rows.add(entity);
        }

        SearchTermDailyEntity findByKey(UUID storeId, UUID campaignId, UUID adGroupId,
                                         String searchTerm, LocalDate reportDate) {
            return rows.stream()
                    .filter(r -> Objects.equals(r.getStoreId(), storeId))
                    .filter(r -> Objects.equals(r.getCampaignId(), campaignId))
                    .filter(r -> Objects.equals(r.getAdGroupId(), adGroupId))
                    .filter(r -> Objects.equals(r.getSearchTerm(), searchTerm))
                    .filter(r -> Objects.equals(r.getReportDate(), reportDate))
                    .findFirst()
                    .orElse(null);
        }

        List<SearchTermDailyEntity> all() { return Collections.unmodifiableList(rows); }
    }

    // ── SQL param extraction ─────────────────────────────────────────────────────

    private static Object extractParamByColumn(LambdaQueryWrapper<?> wrapper, String columnName) {
        String sql = wrapper.getSqlSegment();
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        if (sql == null || params == null) return null;

        Matcher m = CONDITION_PATTERN.matcher(sql);
        while (m.find()) {
            if (m.group(1).equals(columnName)) {
                return params.get(m.group(2));
            }
        }
        return null;
    }

    // ── Test context builder ─────────────────────────────────────────────────────

    private record TestContext(
            ReportIngestionServiceImpl service,
            InMemoryPerformanceStore perfStore,
            InMemorySearchTermStore stStore
    ) {}

    @SuppressWarnings("unchecked")
    private TestContext buildTestContext(UUID storeId, Map<String, UUID> entityMappings) {
        InMemoryPerformanceStore perfStore = new InMemoryPerformanceStore();
        InMemorySearchTermStore stStore = new InMemorySearchTermStore();

        PerformanceDailyMapper perfMapper = mock(PerformanceDailyMapper.class);
        SearchTermDailyMapper stMapper = mock(SearchTermDailyMapper.class);
        MetricQuarantineMapper quarantineMapper = mock(MetricQuarantineMapper.class);
        ExternalEntityMappingMapper mappingMapper = mock(ExternalEntityMappingMapper.class);

        // Wire performance daily mapper
        doAnswer(inv -> {
            perfStore.insert(inv.getArgument(0));
            return 1;
        }).when(perfMapper).insert(any(PerformanceDailyEntity.class));

        doAnswer(inv -> {
            perfStore.updateById(inv.getArgument(0));
            return 1;
        }).when(perfMapper).updateById(any(PerformanceDailyEntity.class));

        when(perfMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(inv -> {
            LambdaQueryWrapper<PerformanceDailyEntity> wrapper = inv.getArgument(0);
            wrapper.getTargetSql();

            UUID qStoreId = (UUID) extractParamByColumn(wrapper, "store_id");
            String qEntityType = (String) extractParamByColumn(wrapper, "entity_type");
            UUID qEntityId = (UUID) extractParamByColumn(wrapper, "entity_id");
            LocalDate qReportDate = (LocalDate) extractParamByColumn(wrapper, "report_date");

            if (qStoreId != null && qEntityType != null && qEntityId != null && qReportDate != null) {
                return perfStore.findByKey(qStoreId, qEntityType, qEntityId, qReportDate);
            }
            return null;
        });

        // Wire search term daily mapper
        doAnswer(inv -> {
            stStore.insert(inv.getArgument(0));
            return 1;
        }).when(stMapper).insert(any(SearchTermDailyEntity.class));

        doAnswer(inv -> {
            stStore.updateById(inv.getArgument(0));
            return 1;
        }).when(stMapper).updateById(any(SearchTermDailyEntity.class));

        when(stMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(inv -> {
            LambdaQueryWrapper<SearchTermDailyEntity> wrapper = inv.getArgument(0);
            wrapper.getTargetSql();

            UUID qStoreId = (UUID) extractParamByColumn(wrapper, "store_id");
            UUID qCampaignId = (UUID) extractParamByColumn(wrapper, "campaign_id");
            UUID qAdGroupId = (UUID) extractParamByColumn(wrapper, "ad_group_id");
            String qSearchTerm = (String) extractParamByColumn(wrapper, "search_term");
            LocalDate qReportDate = (LocalDate) extractParamByColumn(wrapper, "report_date");

            if (qStoreId != null && qCampaignId != null && qAdGroupId != null
                    && qSearchTerm != null && qReportDate != null) {
                return stStore.findByKey(qStoreId, qCampaignId, qAdGroupId, qSearchTerm, qReportDate);
            }
            return null;
        });

        // Wire external entity mapping resolution
        when(mappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(inv -> {
            LambdaQueryWrapper<ExternalEntityMappingEntity> wrapper = inv.getArgument(0);
            wrapper.getTargetSql();

            String externalId = (String) extractParamByColumn(wrapper, "external_entity_id");
            if (externalId != null) {
                UUID internalId = entityMappings.get(externalId);
                if (internalId != null) {
                    return ExternalEntityMappingEntity.builder()
                            .id(UUID.randomUUID())
                            .storeId(storeId)
                            .platform("amazon_ads")
                            .externalEntityId(externalId)
                            .internalEntityId(internalId)
                            .build();
                }
            }
            return null;
        });

        // Wire quarantine mapper (no-op for this test)
        doAnswer(inv -> 1).when(quarantineMapper).insert(any(MetricQuarantineEntity.class));

        ReportIngestionServiceImpl service = new ReportIngestionServiceImpl(
                perfMapper, stMapper, quarantineMapper, mappingMapper, new ObjectMapper());

        return new TestContext(service, perfStore, stStore);
    }

    // ── Property 1: First ingestion creates row with data_version=1 ──────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 10: Backfill bumps data version
     *
     * <p><b>Validates: Requirements 2.4</b></p>
     *
     * <p>On first ingestion, every newly created performance_daily row has data_version=1.</p>
     */
    @Property(tries = 120)
    void firstIngestionCreatesRowWithDataVersionOne(
            @ForAll("performanceRowInput") PerformanceRowInput input) {

        TestContext ctx = buildTestContext(input.storeId, input.entityMappings);

        ReportLifecycleResult result = new ReportLifecycleResult(
                "report-v1", input.reportType, input.reportDate, input.reportDate,
                List.of(input.row), 1);

        IngestionResult ingestionResult = ctx.service().ingest(input.storeId, result, 3);

        assertThat(ingestionResult.rowsInserted())
                .as("First ingestion inserts one row")
                .isEqualTo(1);

        // Verify data_version is 1
        for (PerformanceDailyEntity entity : ctx.perfStore().all()) {
            assertThat(entity.getDataVersion())
                    .as("First ingestion sets data_version=1")
                    .isEqualTo(1);
        }
    }

    // ── Property 2: Changed metrics bumps data_version ───────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 10: Backfill bumps data version
     *
     * <p><b>Validates: Requirements 2.4</b></p>
     *
     * <p>Re-ingesting with at least one changed metric increments data_version.</p>
     */
    @Property(tries = 120)
    void changedMetricsBumpsDataVersion(
            @ForAll("performanceBackfillInput") PerformanceBackfillInput input) {

        TestContext ctx = buildTestContext(input.storeId, input.entityMappings);

        // First ingestion
        ReportLifecycleResult firstResult = new ReportLifecycleResult(
                "report-v1", input.reportType, input.reportDate, input.reportDate,
                List.of(input.originalRow), 1);
        ctx.service().ingest(input.storeId, firstResult, 3);

        // Second ingestion with changed metrics
        ReportLifecycleResult secondResult = new ReportLifecycleResult(
                "report-v2", input.reportType, input.reportDate, input.reportDate,
                List.of(input.changedRow), 1);
        IngestionResult secondIngestion = ctx.service().ingest(input.storeId, secondResult, 3);

        assertThat(secondIngestion.rowsVersionBumped())
                .as("Changed metrics triggers version bump")
                .isEqualTo(1);

        // Verify data_version is now 2
        for (PerformanceDailyEntity entity : ctx.perfStore().all()) {
            assertThat(entity.getDataVersion())
                    .as("data_version incremented to 2 after backfill change")
                    .isEqualTo(2);
        }
    }

    // ── Property 3: Same metrics does NOT bump data_version ──────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 10: Backfill bumps data version
     *
     * <p><b>Validates: Requirements 2.4</b></p>
     *
     * <p>Re-ingesting with identical metrics does not increment data_version.</p>
     */
    @Property(tries = 120)
    void sameMetricsDoesNotBumpDataVersion(
            @ForAll("performanceRowInput") PerformanceRowInput input) {

        TestContext ctx = buildTestContext(input.storeId, input.entityMappings);

        ReportLifecycleResult result = new ReportLifecycleResult(
                "report-v1", input.reportType, input.reportDate, input.reportDate,
                List.of(input.row), 1);

        // First ingestion
        ctx.service().ingest(input.storeId, result, 3);

        // Second ingestion with same data
        IngestionResult secondIngestion = ctx.service().ingest(input.storeId, result, 3);

        assertThat(secondIngestion.rowsVersionBumped())
                .as("Identical metrics do not trigger version bump")
                .isEqualTo(0);

        // Verify data_version remains 1
        for (PerformanceDailyEntity entity : ctx.perfStore().all()) {
            assertThat(entity.getDataVersion())
                    .as("data_version stays at 1 when metrics unchanged")
                    .isEqualTo(1);
        }
    }

    // ── Property 4: data_version is always ≥ 1 ──────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 10: Backfill bumps data version
     *
     * <p><b>Validates: Requirements 2.4</b></p>
     *
     * <p>After any sequence of ingestions (initial + backfills), data_version is always ≥ 1.</p>
     */
    @Property(tries = 120)
    void dataVersionIsAlwaysPositive(
            @ForAll("backfillSequenceInput") BackfillSequenceInput input) {

        TestContext ctx = buildTestContext(input.storeId, input.entityMappings);

        // Ingest each row in sequence (simulating multiple backfills)
        for (Map<String, Object> row : input.rowSequence) {
            ReportLifecycleResult result = new ReportLifecycleResult(
                    "report-seq", input.reportType, input.reportDate, input.reportDate,
                    List.of(row), 1);
            ctx.service().ingest(input.storeId, result, 3);
        }

        // Verify all rows have data_version ≥ 1
        for (PerformanceDailyEntity entity : ctx.perfStore().all()) {
            assertThat(entity.getDataVersion())
                    .as("data_version is always ≥ 1")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    // ── Property 5: data_version increases monotonically ─────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 10: Backfill bumps data version
     *
     * <p><b>Validates: Requirements 2.4</b></p>
     *
     * <p>data_version never decreases across successive ingestions. Each changed backfill
     * increases it by exactly 1, and same-data re-ingestion leaves it unchanged.</p>
     */
    @Property(tries = 120)
    void dataVersionIncreasesMonotonically(
            @ForAll("backfillSequenceInput") BackfillSequenceInput input) {

        TestContext ctx = buildTestContext(input.storeId, input.entityMappings);

        int previousVersion = 0; // before any ingestion

        for (Map<String, Object> row : input.rowSequence) {
            ReportLifecycleResult result = new ReportLifecycleResult(
                    "report-mono", input.reportType, input.reportDate, input.reportDate,
                    List.of(row), 1);
            ctx.service().ingest(input.storeId, result, 3);

            // Get current version
            for (PerformanceDailyEntity entity : ctx.perfStore().all()) {
                int currentVersion = entity.getDataVersion();
                assertThat(currentVersion)
                        .as("data_version never decreases (was %d, now %d)", previousVersion, currentVersion)
                        .isGreaterThanOrEqualTo(previousVersion);
                previousVersion = currentVersion;
            }
        }
    }

    // ── Property 6: Search-term backfill version bump (same logic) ───────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 10: Backfill bumps data version
     *
     * <p><b>Validates: Requirements 2.4</b></p>
     *
     * <p>The same backfill version bump behavior applies to search_term_daily rows.</p>
     */
    @Property(tries = 120)
    void searchTermChangedMetricsBumpsDataVersion(
            @ForAll("searchTermBackfillInput") SearchTermBackfillInput input) {

        TestContext ctx = buildTestContext(input.storeId, input.entityMappings);

        // First ingestion
        ReportLifecycleResult firstResult = new ReportLifecycleResult(
                "report-st-v1", ReportType.SP_SEARCH_TERM, input.reportDate, input.reportDate,
                List.of(input.originalRow), 1);
        ctx.service().ingest(input.storeId, firstResult, 3);

        // Verify first ingestion creates data_version=1
        for (SearchTermDailyEntity entity : ctx.stStore().all()) {
            assertThat(entity.getDataVersion())
                    .as("First search-term ingestion sets data_version=1")
                    .isEqualTo(1);
        }

        // Second ingestion with changed metrics
        ReportLifecycleResult secondResult = new ReportLifecycleResult(
                "report-st-v2", ReportType.SP_SEARCH_TERM, input.reportDate, input.reportDate,
                List.of(input.changedRow), 1);
        IngestionResult secondIngestion = ctx.service().ingest(input.storeId, secondResult, 3);

        assertThat(secondIngestion.rowsVersionBumped())
                .as("Changed search-term metrics triggers version bump")
                .isEqualTo(1);

        for (SearchTermDailyEntity entity : ctx.stStore().all()) {
            assertThat(entity.getDataVersion())
                    .as("search-term data_version incremented to 2")
                    .isEqualTo(2);
        }
    }

    // ── Data holders ─────────────────────────────────────────────────────────────

    record PerformanceRowInput(
            UUID storeId,
            ReportType reportType,
            LocalDate reportDate,
            Map<String, Object> row,
            Map<String, UUID> entityMappings
    ) {}

    record PerformanceBackfillInput(
            UUID storeId,
            ReportType reportType,
            LocalDate reportDate,
            Map<String, Object> originalRow,
            Map<String, Object> changedRow,
            Map<String, UUID> entityMappings
    ) {}

    record BackfillSequenceInput(
            UUID storeId,
            ReportType reportType,
            LocalDate reportDate,
            List<Map<String, Object>> rowSequence,
            Map<String, UUID> entityMappings
    ) {}

    record SearchTermBackfillInput(
            UUID storeId,
            LocalDate reportDate,
            Map<String, Object> originalRow,
            Map<String, Object> changedRow,
            Map<String, UUID> entityMappings
    ) {}

    // ── Generators ───────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<PerformanceRowInput> performanceRowInput() {
        return Arbitraries.lazyOf(() -> {
            UUID storeId = UUID.randomUUID();
            LocalDate reportDate = LocalDate.now().minusDays(10);

            return Arbitraries.of(ReportType.SP_CAMPAIGN, ReportType.SP_KEYWORD)
                    .map(reportType -> {
                        String entityType = reportType == ReportType.SP_KEYWORD ? "keyword" : "campaign";
                        String idField = reportType == ReportType.SP_KEYWORD ? "keywordId" : "campaignId";
                        String externalId = "ext-" + entityType + "-001";
                        UUID internalId = UUID.randomUUID();

                        Map<String, UUID> mappings = new LinkedHashMap<>();
                        mappings.put(externalId, internalId);
                        if (reportType == ReportType.SP_KEYWORD) {
                            mappings.put("ext-campaign-001", UUID.randomUUID());
                        }

                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put(idField, externalId);
                        if (reportType == ReportType.SP_KEYWORD) {
                            row.put("campaignId", "ext-campaign-001");
                        }
                        row.put("date", reportDate);
                        row.put("impressions", 1000L);
                        row.put("clicks", 50);
                        row.put("spend", BigDecimal.valueOf(25.50));
                        row.put("sales", BigDecimal.valueOf(100.00));
                        row.put("orders", 5);
                        row.put("acos", BigDecimal.valueOf(0.255));
                        row.put("roas", BigDecimal.valueOf(3.92));
                        row.put("currency", "USD");

                        return new PerformanceRowInput(storeId, reportType, reportDate, row, mappings);
                    });
        });
    }

    @Provide
    Arbitrary<PerformanceBackfillInput> performanceBackfillInput() {
        return Arbitraries.lazyOf(() -> {
            UUID storeId = UUID.randomUUID();
            LocalDate reportDate = LocalDate.now().minusDays(10);

            return Arbitraries.of(ReportType.SP_CAMPAIGN, ReportType.SP_KEYWORD)
                    .flatMap(reportType -> {
                        String entityType = reportType == ReportType.SP_KEYWORD ? "keyword" : "campaign";
                        String idField = reportType == ReportType.SP_KEYWORD ? "keywordId" : "campaignId";
                        String externalId = "ext-" + entityType + "-backfill";
                        UUID internalId = UUID.randomUUID();

                        Map<String, UUID> mappings = new LinkedHashMap<>();
                        mappings.put(externalId, internalId);
                        if (reportType == ReportType.SP_KEYWORD) {
                            mappings.put("ext-campaign-backfill", UUID.randomUUID());
                        }

                        // Generate varying original metrics
                        return Arbitraries.longs().between(100, 10000).flatMap(impressions ->
                                Arbitraries.integers().between(1, 500).flatMap(clicks ->
                                        Arbitraries.doubles().between(1.0, 100.0).map(spend -> {
                                            Map<String, Object> originalRow = new LinkedHashMap<>();
                                            originalRow.put(idField, externalId);
                                            if (reportType == ReportType.SP_KEYWORD) {
                                                originalRow.put("campaignId", "ext-campaign-backfill");
                                            }
                                            originalRow.put("date", reportDate);
                                            originalRow.put("impressions", impressions);
                                            originalRow.put("clicks", clicks);
                                            originalRow.put("spend", BigDecimal.valueOf(spend));
                                            originalRow.put("sales", BigDecimal.valueOf(spend * 3));
                                            originalRow.put("orders", clicks / 5);
                                            originalRow.put("acos", BigDecimal.valueOf(0.25));
                                            originalRow.put("roas", BigDecimal.valueOf(3.0));
                                            originalRow.put("currency", "USD");

                                            // Changed row: different impressions and spend
                                            Map<String, Object> changedRow = new LinkedHashMap<>(originalRow);
                                            changedRow.put("impressions", impressions + 500L);
                                            changedRow.put("spend", BigDecimal.valueOf(spend + 10.0));

                                            return new PerformanceBackfillInput(
                                                    storeId, reportType, reportDate,
                                                    originalRow, changedRow, mappings);
                                        })
                                )
                        );
                    });
        });
    }

    @Provide
    Arbitrary<BackfillSequenceInput> backfillSequenceInput() {
        return Arbitraries.lazyOf(() -> {
            UUID storeId = UUID.randomUUID();
            LocalDate reportDate = LocalDate.now().minusDays(10);

            return Arbitraries.of(ReportType.SP_CAMPAIGN, ReportType.SP_KEYWORD)
                    .flatMap(reportType -> {
                        String entityType = reportType == ReportType.SP_KEYWORD ? "keyword" : "campaign";
                        String idField = reportType == ReportType.SP_KEYWORD ? "keywordId" : "campaignId";
                        String externalId = "ext-" + entityType + "-seq";
                        UUID internalId = UUID.randomUUID();

                        Map<String, UUID> mappings = new LinkedHashMap<>();
                        mappings.put(externalId, internalId);
                        if (reportType == ReportType.SP_KEYWORD) {
                            mappings.put("ext-campaign-seq", UUID.randomUUID());
                        }

                        // Generate a sequence of 2-5 ingestions with varying metrics
                        return Arbitraries.integers().between(2, 5).flatMap(seqLen -> {
                            return Arbitraries.longs().between(100, 5000).map(baseImpressions -> {
                                List<Map<String, Object>> sequence = new ArrayList<>();
                                for (int i = 0; i < seqLen; i++) {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    row.put(idField, externalId);
                                    if (reportType == ReportType.SP_KEYWORD) {
                                        row.put("campaignId", "ext-campaign-seq");
                                    }
                                    row.put("date", reportDate);
                                    // Each successive row may change impressions to simulate backfill
                                    row.put("impressions", baseImpressions + (i * 100L));
                                    row.put("clicks", 50 + i * 10);
                                    row.put("spend", BigDecimal.valueOf(25.50 + i * 5.0));
                                    row.put("sales", BigDecimal.valueOf(100.00 + i * 20.0));
                                    row.put("orders", 5 + i);
                                    row.put("acos", BigDecimal.valueOf(0.255));
                                    row.put("roas", BigDecimal.valueOf(3.92));
                                    row.put("currency", "USD");
                                    sequence.add(row);
                                }
                                return new BackfillSequenceInput(
                                        storeId, reportType, reportDate, sequence, mappings);
                            });
                        });
                    });
        });
    }

    @Provide
    Arbitrary<SearchTermBackfillInput> searchTermBackfillInput() {
        return Arbitraries.lazyOf(() -> {
            UUID storeId = UUID.randomUUID();
            LocalDate reportDate = LocalDate.now().minusDays(10);

            String externalCampaignId = "ext-campaign-st";
            String externalAdGroupId = "ext-adgroup-st";
            UUID campaignInternalId = UUID.randomUUID();
            UUID adGroupInternalId = UUID.randomUUID();

            Map<String, UUID> mappings = new LinkedHashMap<>();
            mappings.put(externalCampaignId, campaignInternalId);
            mappings.put(externalAdGroupId, adGroupInternalId);

            return Arbitraries.longs().between(100, 10000).flatMap(impressions ->
                    Arbitraries.integers().between(1, 500).map(clicks -> {
                        Map<String, Object> originalRow = new LinkedHashMap<>();
                        originalRow.put("campaignId", externalCampaignId);
                        originalRow.put("adGroupId", externalAdGroupId);
                        originalRow.put("searchTerm", "test search term");
                        originalRow.put("date", reportDate);
                        originalRow.put("impressions", impressions);
                        originalRow.put("clicks", clicks);
                        originalRow.put("orders", clicks / 10);
                        originalRow.put("spend", BigDecimal.valueOf(15.75));
                        originalRow.put("sales", BigDecimal.valueOf(60.00));
                        originalRow.put("acos", BigDecimal.valueOf(0.26));
                        originalRow.put("currency", "USD");

                        // Changed row: modify impressions and clicks
                        Map<String, Object> changedRow = new LinkedHashMap<>(originalRow);
                        changedRow.put("impressions", impressions + 200L);
                        changedRow.put("clicks", clicks + 30);

                        return new SearchTermBackfillInput(
                                storeId, reportDate, originalRow, changedRow, mappings);
                    })
            );
        });
    }
}
