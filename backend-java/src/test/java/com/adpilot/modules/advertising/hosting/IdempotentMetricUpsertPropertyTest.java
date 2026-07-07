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
import net.jqwik.api.constraints.IntRange;
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
 * Feature: amazon-ads-ai-hosting-system, Property 8: Idempotent metric upsert
 *
 * <p><b>Validates: Requirements 2.4, 31.1, 32.1</b></p>
 *
 * <p>Verifies that the {@link ReportIngestionServiceImpl} idempotent upsert logic:
 * <ol>
 *   <li>Ingesting the same report rows twice produces the same state as ingesting once.</li>
 *   <li>The upsert key for performance_daily is (store_id, entity_type, entity_id, report_date).</li>
 *   <li>The upsert key for search_term_daily is (store_id, campaign_id, ad_group_id, search_term, report_date).</li>
 *   <li>No duplicate rows are created regardless of how many times the same data is ingested.</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 8: Idempotent metric upsert")
class IdempotentMetricUpsertPropertyTest {

    static {
        // Register entity metadata so MyBatis-Plus can resolve lambda columns
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PerformanceDailyEntity.class);
        TableInfoHelper.initTableInfo(assistant, SearchTermDailyEntity.class);
        TableInfoHelper.initTableInfo(assistant, ExternalEntityMappingEntity.class);
        TableInfoHelper.initTableInfo(assistant, MetricQuarantineEntity.class);
    }

    // ── Regex for parsing SQL WHERE clause param references ───────────────────────
    // Matches patterns like: column_name = #{ew.paramNameValuePairs.MPGENVAL1}
    private static final Pattern CONDITION_PATTERN =
            Pattern.compile("`?(\\w+)`?\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)}");

    // ── In-memory stores simulating the database ──────────────────────────────────

    private static class InMemoryPerformanceStore {
        private final List<PerformanceDailyEntity> rows = new CopyOnWriteArrayList<>();
        private final AtomicInteger insertCount = new AtomicInteger(0);

        void insert(PerformanceDailyEntity entity) {
            entity.setId(UUID.randomUUID());
            rows.add(entity);
            insertCount.incrementAndGet();
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

        int size() { return rows.size(); }
        int totalInserts() { return insertCount.get(); }
        List<PerformanceDailyEntity> all() { return Collections.unmodifiableList(rows); }
    }

    private static class InMemorySearchTermStore {
        private final List<SearchTermDailyEntity> rows = new CopyOnWriteArrayList<>();
        private final AtomicInteger insertCount = new AtomicInteger(0);

        void insert(SearchTermDailyEntity entity) {
            entity.setId(UUID.randomUUID());
            rows.add(entity);
            insertCount.incrementAndGet();
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

        int size() { return rows.size(); }
        int totalInserts() { return insertCount.get(); }
        List<SearchTermDailyEntity> all() { return Collections.unmodifiableList(rows); }
    }

    // ── Test result holder ────────────────────────────────────────────────────────

    private record TestableResult(
            ReportIngestionServiceImpl service,
            InMemoryPerformanceStore perfStore,
            InMemorySearchTermStore stStore,
            AtomicInteger quarantineCount
    ) {}

    // ── SQL segment parsing to extract param values by column name ─────────────────

    /**
     * Extracts a named column's bound value from a MyBatis-Plus LambdaQueryWrapper.
     * Parses the SQL segment to find which parameter key corresponds to the column,
     * then looks it up in the param map.
     */
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

    // ── Build the service with mocked mappers backed by in-memory stores ──────────

    @SuppressWarnings("unchecked")
    private TestableResult buildTestableService(UUID storeId, Map<String, UUID> entityMappings) {
        InMemoryPerformanceStore perfStore = new InMemoryPerformanceStore();
        InMemorySearchTermStore stStore = new InMemorySearchTermStore();
        AtomicInteger quarantineCount = new AtomicInteger(0);

        PerformanceDailyMapper perfMapper = mock(PerformanceDailyMapper.class);
        SearchTermDailyMapper stMapper = mock(SearchTermDailyMapper.class);
        MetricQuarantineMapper quarantineMapper = mock(MetricQuarantineMapper.class);
        ExternalEntityMappingMapper mappingMapper = mock(ExternalEntityMappingMapper.class);

        // Wire performance daily mapper - insert
        doAnswer(inv -> {
            perfStore.insert(inv.getArgument(0));
            return 1;
        }).when(perfMapper).insert(any(PerformanceDailyEntity.class));

        // Wire performance daily mapper - updateById
        doAnswer(inv -> {
            perfStore.updateById(inv.getArgument(0));
            return 1;
        }).when(perfMapper).updateById(any(PerformanceDailyEntity.class));

        // Wire performance daily selectOne: parse SQL to extract column values.
        when(perfMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(inv -> {
            LambdaQueryWrapper<PerformanceDailyEntity> wrapper = inv.getArgument(0);
            wrapper.getTargetSql(); // force lazy param materialization

            UUID qStoreId = (UUID) extractParamByColumn(wrapper, "store_id");
            String qEntityType = (String) extractParamByColumn(wrapper, "entity_type");
            UUID qEntityId = (UUID) extractParamByColumn(wrapper, "entity_id");
            LocalDate qReportDate = (LocalDate) extractParamByColumn(wrapper, "report_date");

            if (qStoreId != null && qEntityType != null && qEntityId != null && qReportDate != null) {
                return perfStore.findByKey(qStoreId, qEntityType, qEntityId, qReportDate);
            }
            return null;
        });

        // Wire search term daily mapper - insert
        doAnswer(inv -> {
            stStore.insert(inv.getArgument(0));
            return 1;
        }).when(stMapper).insert(any(SearchTermDailyEntity.class));

        // Wire search term daily mapper - updateById
        doAnswer(inv -> {
            stStore.updateById(inv.getArgument(0));
            return 1;
        }).when(stMapper).updateById(any(SearchTermDailyEntity.class));

        // Wire search term daily selectOne: parse SQL to extract column values
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

        // Wire external entity mapping resolution: parse SQL to find externalEntityId
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

        // Wire quarantine mapper
        doAnswer(inv -> {
            quarantineCount.incrementAndGet();
            return 1;
        }).when(quarantineMapper).insert(any(MetricQuarantineEntity.class));

        ReportIngestionServiceImpl service = new ReportIngestionServiceImpl(
                perfMapper, stMapper, quarantineMapper, mappingMapper, new ObjectMapper());

        return new TestableResult(service, perfStore, stStore, quarantineCount);
    }

    // ── Property 1: Performance daily ingestion is idempotent ─────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 8: Idempotent metric upsert
     *
     * <p><b>Validates: Requirements 2.4, 31.1, 32.1</b></p>
     *
     * <p>Ingesting the same campaign/keyword report rows twice produces the same
     * final state as ingesting once. The second ingestion inserts zero new rows.</p>
     */
    @Property(tries = 120)
    void performanceDailyIngestionIsIdempotent(
            @ForAll("performanceReportData") PerformanceReportData data) {

        TestableResult ctx = buildTestableService(data.storeId, data.entityMappings);

        ReportLifecycleResult result = new ReportLifecycleResult(
                "report-001", data.reportType, data.reportDate, data.reportDate,
                data.rows, data.rows.size());

        // First ingestion
        IngestionResult firstResult = ctx.service().ingest(data.storeId, result, 3);

        // Snapshot state after first ingestion
        int rowsAfterFirst = ctx.perfStore().size();

        // Second ingestion (same data)
        IngestionResult secondResult = ctx.service().ingest(data.storeId, result, 3);

        // After second ingestion, row count must be unchanged (no duplicates)
        assertThat(ctx.perfStore().size())
                .as("No duplicate rows created on re-ingestion")
                .isEqualTo(rowsAfterFirst);

        // Second ingestion should insert zero new rows
        assertThat(secondResult.rowsInserted())
                .as("Second ingestion inserts zero rows")
                .isEqualTo(0);

        // Data version should not bump (same data re-ingested)
        assertThat(secondResult.rowsVersionBumped())
                .as("Same data does not trigger version bump")
                .isEqualTo(0);
    }

    // ── Property 2: Search-term daily ingestion is idempotent ─────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 8: Idempotent metric upsert
     *
     * <p><b>Validates: Requirements 2.4, 31.1, 32.1</b></p>
     *
     * <p>Ingesting the same search-term report rows twice produces the same final
     * state as ingesting once. The second ingestion inserts zero new rows.</p>
     */
    @Property(tries = 120)
    void searchTermDailyIngestionIsIdempotent(
            @ForAll("searchTermReportData") SearchTermReportData data) {

        TestableResult ctx = buildTestableService(data.storeId, data.entityMappings);

        ReportLifecycleResult result = new ReportLifecycleResult(
                "report-002", ReportType.SP_SEARCH_TERM, data.reportDate, data.reportDate,
                data.rows, data.rows.size());

        // First ingestion
        IngestionResult firstResult = ctx.service().ingest(data.storeId, result, 3);

        // Snapshot state after first ingestion
        int rowsAfterFirst = ctx.stStore().size();

        // Second ingestion (same data)
        IngestionResult secondResult = ctx.service().ingest(data.storeId, result, 3);

        // After second ingestion, row count must be unchanged (no duplicates)
        assertThat(ctx.stStore().size())
                .as("No duplicate search-term rows created on re-ingestion")
                .isEqualTo(rowsAfterFirst);

        // Second ingestion should insert zero new rows
        assertThat(secondResult.rowsInserted())
                .as("Second ingestion inserts zero search-term rows")
                .isEqualTo(0);

        // Data version should not bump (same data re-ingested)
        assertThat(secondResult.rowsVersionBumped())
                .as("Same search-term data does not trigger version bump")
                .isEqualTo(0);
    }

    // ── Property 3: Performance daily upsert key is correct ──────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 8: Idempotent metric upsert
     *
     * <p><b>Validates: Requirements 2.4, 31.1, 32.1</b></p>
     *
     * <p>Two performance rows with the same (store_id, entity_type, entity_id, report_date)
     * result in exactly one stored row (the second is an update, not an insert).
     * Two rows differing in any key component result in two stored rows.</p>
     */
    @Property(tries = 120)
    void performanceDailyUpsertKeyIsCorrect(
            @ForAll("performanceUpsertKeyData") PerformanceUpsertKeyData data) {

        TestableResult ctx = buildTestableService(data.storeId, data.entityMappings);

        ReportLifecycleResult result = new ReportLifecycleResult(
                "report-003", data.reportType, data.reportDate, data.reportDate,
                List.of(data.row1, data.row2), 2);

        ctx.service().ingest(data.storeId, result, 3);

        if (data.sameKey) {
            // Same key → only one row in the store
            assertThat(ctx.perfStore().size())
                    .as("Same upsert key produces exactly one row")
                    .isEqualTo(1);
        } else {
            // Different key → two distinct rows
            assertThat(ctx.perfStore().size())
                    .as("Different upsert keys produce distinct rows")
                    .isEqualTo(2);
        }
    }

    // ── Property 4: Search-term daily upsert key is correct ──────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 8: Idempotent metric upsert
     *
     * <p><b>Validates: Requirements 2.4, 31.1, 32.1</b></p>
     *
     * <p>Two search-term rows with the same (store_id, campaign_id, ad_group_id, search_term,
     * report_date) result in exactly one stored row. Two rows differing in any key
     * component result in two stored rows.</p>
     */
    @Property(tries = 120)
    void searchTermDailyUpsertKeyIsCorrect(
            @ForAll("searchTermUpsertKeyData") SearchTermUpsertKeyData data) {

        TestableResult ctx = buildTestableService(data.storeId, data.entityMappings);

        ReportLifecycleResult result = new ReportLifecycleResult(
                "report-004", ReportType.SP_SEARCH_TERM, data.reportDate, data.reportDate,
                List.of(data.row1, data.row2), 2);

        ctx.service().ingest(data.storeId, result, 3);

        if (data.sameKey) {
            assertThat(ctx.stStore().size())
                    .as("Same search-term upsert key produces exactly one row")
                    .isEqualTo(1);
        } else {
            assertThat(ctx.stStore().size())
                    .as("Different search-term upsert keys produce distinct rows")
                    .isEqualTo(2);
        }
    }

    // ── Property 5: Multiple ingestions never create duplicates ───────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 8: Idempotent metric upsert
     *
     * <p><b>Validates: Requirements 2.4, 31.1, 32.1</b></p>
     *
     * <p>Regardless of how many times the same data is ingested (1 to N times),
     * the number of rows in the store equals the number of distinct upsert keys
     * in the input data — never more.</p>
     */
    @Property(tries = 100)
    void multipleIngestionsNeverCreateDuplicatePerformanceRows(
            @ForAll("performanceReportData") PerformanceReportData data,
            @ForAll @IntRange(min = 2, max = 5) int ingestionCount) {

        TestableResult ctx = buildTestableService(data.storeId, data.entityMappings);

        ReportLifecycleResult result = new ReportLifecycleResult(
                "report-005", data.reportType, data.reportDate, data.reportDate,
                data.rows, data.rows.size());

        // Ingest N times
        for (int i = 0; i < ingestionCount; i++) {
            ctx.service().ingest(data.storeId, result, 3);
        }

        // Count distinct keys in the resolved rows (excluding quarantined)
        int expectedDistinctRows = countDistinctPerformanceKeys(data);

        assertThat(ctx.perfStore().size())
                .as("Row count equals distinct key count, never more (after %d ingestions)", ingestionCount)
                .isEqualTo(expectedDistinctRows);
    }

    // ── Helpers for counting distinct keys ────────────────────────────────────────

    private int countDistinctPerformanceKeys(PerformanceReportData data) {
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> row : data.rows) {
            String entityType = data.reportType == ReportType.SP_KEYWORD ? "keyword" : "campaign";
            String externalId;
            if ("keyword".equals(entityType)) {
                externalId = row.get("keywordId") != null ? row.get("keywordId").toString() : null;
            } else {
                externalId = row.get("campaignId") != null ? row.get("campaignId").toString() : null;
            }
            if (externalId == null || !data.entityMappings.containsKey(externalId)) {
                continue; // would be quarantined
            }
            UUID internalId = data.entityMappings.get(externalId);
            LocalDate reportDate = (LocalDate) row.get("date");
            String key = data.storeId + "|" + entityType + "|" + internalId + "|" + reportDate;
            keys.add(key);
        }
        return keys.size();
    }

    // ── Data holders ──────────────────────────────────────────────────────────────

    record PerformanceReportData(
            UUID storeId,
            ReportType reportType,
            LocalDate reportDate,
            List<Map<String, Object>> rows,
            Map<String, UUID> entityMappings
    ) {}

    record SearchTermReportData(
            UUID storeId,
            LocalDate reportDate,
            List<Map<String, Object>> rows,
            Map<String, UUID> entityMappings
    ) {}

    record PerformanceUpsertKeyData(
            UUID storeId,
            ReportType reportType,
            LocalDate reportDate,
            Map<String, Object> row1,
            Map<String, Object> row2,
            boolean sameKey,
            Map<String, UUID> entityMappings
    ) {}

    record SearchTermUpsertKeyData(
            UUID storeId,
            LocalDate reportDate,
            Map<String, Object> row1,
            Map<String, Object> row2,
            boolean sameKey,
            Map<String, UUID> entityMappings
    ) {}

    // ── Generators ────────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<PerformanceReportData> performanceReportData() {
        return Arbitraries.lazyOf(() -> {
            UUID storeId = UUID.randomUUID();
            LocalDate reportDate = LocalDate.now().minusDays(10); // finalized

            return Arbitraries.of(ReportType.SP_CAMPAIGN, ReportType.SP_KEYWORD)
                    .flatMap(reportType -> {
                        String idField = reportType == ReportType.SP_KEYWORD ? "keywordId" : "campaignId";
                        String entityType = reportType == ReportType.SP_KEYWORD ? "keyword" : "campaign";

                        return Arbitraries.integers().between(1, 3).flatMap(entityCount -> {
                            Map<String, UUID> mappings = new LinkedHashMap<>();
                            List<String> externalIds = new ArrayList<>();
                            for (int i = 0; i < entityCount; i++) {
                                String extId = "ext-" + entityType + "-" + i;
                                mappings.put(extId, UUID.randomUUID());
                                externalIds.add(extId);
                            }

                            // Add a campaign mapping for keyword reports
                            if (reportType == ReportType.SP_KEYWORD) {
                                mappings.put("ext-campaign-0", UUID.randomUUID());
                            }

                            return Arbitraries.integers().between(1, 5).map(rowCount -> {
                                List<Map<String, Object>> rows = new ArrayList<>();
                                // Track metrics by key to ensure rows with the same key
                                // have identical metrics (true idempotency test)
                                Map<String, Map<String, Object>> metricsByKey = new LinkedHashMap<>();

                                for (int i = 0; i < rowCount; i++) {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    // Use rotating entity IDs so some rows may share the same key
                                    String extId = externalIds.get(i % externalIds.size());
                                    row.put(idField, extId);
                                    if (reportType == ReportType.SP_KEYWORD) {
                                        row.put("campaignId", "ext-campaign-0");
                                    }
                                    row.put("date", reportDate);

                                    // Reuse same metrics for same key
                                    Map<String, Object> existingMetrics = metricsByKey.get(extId);
                                    if (existingMetrics != null) {
                                        row.put("impressions", existingMetrics.get("impressions"));
                                        row.put("clicks", existingMetrics.get("clicks"));
                                        row.put("spend", existingMetrics.get("spend"));
                                        row.put("sales", existingMetrics.get("sales"));
                                        row.put("orders", existingMetrics.get("orders"));
                                        row.put("acos", existingMetrics.get("acos"));
                                        row.put("roas", existingMetrics.get("roas"));
                                    } else {
                                        row.put("impressions", 1000L + i);
                                        row.put("clicks", 50 + i);
                                        row.put("spend", BigDecimal.valueOf(25.50 + i));
                                        row.put("sales", BigDecimal.valueOf(100.00 + i));
                                        row.put("orders", 5 + i);
                                        row.put("acos", BigDecimal.valueOf(0.25));
                                        row.put("roas", BigDecimal.valueOf(4.0));
                                        metricsByKey.put(extId, row);
                                    }
                                    row.put("currency", "USD");
                                    rows.add(row);
                                }
                                return new PerformanceReportData(storeId, reportType, reportDate, rows, mappings);
                            });
                        });
                    });
        });
    }

    @Provide
    Arbitrary<SearchTermReportData> searchTermReportData() {
        return Arbitraries.lazyOf(() -> {
            UUID storeId = UUID.randomUUID();
            LocalDate reportDate = LocalDate.now().minusDays(10);

            return Arbitraries.integers().between(1, 3).flatMap(campaignCount -> {
                Map<String, UUID> mappings = new LinkedHashMap<>();

                List<String> campaignExtIds = new ArrayList<>();
                List<String> adGroupExtIds = new ArrayList<>();

                for (int i = 0; i < campaignCount; i++) {
                    String campExt = "ext-campaign-" + i;
                    String agExt = "ext-adgroup-" + i;
                    mappings.put(campExt, UUID.randomUUID());
                    mappings.put(agExt, UUID.randomUUID());
                    campaignExtIds.add(campExt);
                    adGroupExtIds.add(agExt);
                }

                return Arbitraries.integers().between(1, 5).map(rowCount -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    // Track metrics by composite key to ensure rows with the same key
                    // have the same metrics (idempotency test needs identical data)
                    Map<String, Map<String, Object>> metricsByKey = new LinkedHashMap<>();

                    for (int i = 0; i < rowCount; i++) {
                        int idx = i % campaignCount;
                        String campId = campaignExtIds.get(idx);
                        String agId = adGroupExtIds.get(idx);
                        String searchTerm = "search term " + (i % 2);
                        String compositeKey = campId + "|" + agId + "|" + searchTerm;

                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("campaignId", campId);
                        row.put("adGroupId", agId);
                        row.put("searchTerm", searchTerm);
                        row.put("date", reportDate);

                        // Reuse same metrics for same key to test true idempotency
                        Map<String, Object> existingMetrics = metricsByKey.get(compositeKey);
                        if (existingMetrics != null) {
                            row.put("impressions", existingMetrics.get("impressions"));
                            row.put("clicks", existingMetrics.get("clicks"));
                            row.put("orders", existingMetrics.get("orders"));
                            row.put("spend", existingMetrics.get("spend"));
                            row.put("sales", existingMetrics.get("sales"));
                            row.put("acos", existingMetrics.get("acos"));
                        } else {
                            row.put("impressions", 500L + i);
                            row.put("clicks", 20 + i);
                            row.put("orders", 2 + i);
                            row.put("spend", BigDecimal.valueOf(10.00 + i));
                            row.put("sales", BigDecimal.valueOf(50.00 + i));
                            row.put("acos", BigDecimal.valueOf(0.20));
                            metricsByKey.put(compositeKey, row);
                        }
                        row.put("currency", "USD");
                        rows.add(row);
                    }
                    return new SearchTermReportData(storeId, reportDate, rows, mappings);
                });
            });
        });
    }

    @Provide
    Arbitrary<PerformanceUpsertKeyData> performanceUpsertKeyData() {
        return Arbitraries.of(true, false).flatMap(sameKey -> {
            UUID storeId = UUID.randomUUID();
            LocalDate reportDate = LocalDate.now().minusDays(10);

            return Arbitraries.of(ReportType.SP_CAMPAIGN, ReportType.SP_KEYWORD).map(reportType -> {
                String entityType = reportType == ReportType.SP_KEYWORD ? "keyword" : "campaign";
                String idField = reportType == ReportType.SP_KEYWORD ? "keywordId" : "campaignId";

                String extId1 = "ext-" + entityType + "-1";
                String extId2 = sameKey ? extId1 : "ext-" + entityType + "-2";

                Map<String, UUID> mappings = new LinkedHashMap<>();
                UUID internalId1 = UUID.randomUUID();
                mappings.put(extId1, internalId1);
                if (!sameKey) {
                    mappings.put(extId2, UUID.randomUUID());
                }
                // Add campaign mapping for keyword reports
                if (reportType == ReportType.SP_KEYWORD) {
                    mappings.put("ext-campaign-shared", UUID.randomUUID());
                }

                Map<String, Object> row1 = new LinkedHashMap<>();
                row1.put(idField, extId1);
                row1.put("date", reportDate);
                row1.put("impressions", 1000L);
                row1.put("clicks", 50);
                row1.put("spend", BigDecimal.valueOf(25.50));
                row1.put("sales", BigDecimal.valueOf(100.00));
                row1.put("orders", 5);
                row1.put("acos", BigDecimal.valueOf(0.25));
                row1.put("roas", BigDecimal.valueOf(4.0));
                row1.put("currency", "USD");
                if (reportType == ReportType.SP_KEYWORD) {
                    row1.put("campaignId", "ext-campaign-shared");
                }

                Map<String, Object> row2 = new LinkedHashMap<>();
                row2.put(idField, extId2);
                row2.put("date", reportDate);
                row2.put("impressions", 2000L);
                row2.put("clicks", 80);
                row2.put("spend", BigDecimal.valueOf(35.00));
                row2.put("sales", BigDecimal.valueOf(150.00));
                row2.put("orders", 8);
                row2.put("acos", BigDecimal.valueOf(0.23));
                row2.put("roas", BigDecimal.valueOf(4.3));
                row2.put("currency", "USD");
                if (reportType == ReportType.SP_KEYWORD) {
                    row2.put("campaignId", "ext-campaign-shared");
                }

                return new PerformanceUpsertKeyData(storeId, reportType, reportDate, row1, row2, sameKey, mappings);
            });
        });
    }

    @Provide
    Arbitrary<SearchTermUpsertKeyData> searchTermUpsertKeyData() {
        return Arbitraries.of(true, false).map(sameKey -> {
            UUID storeId = UUID.randomUUID();
            LocalDate reportDate = LocalDate.now().minusDays(10);

            String campExt = "ext-campaign-1";
            String agExt1 = "ext-adgroup-1";
            String agExt2 = sameKey ? agExt1 : "ext-adgroup-2";
            String searchTerm1 = "running shoes";
            String searchTerm2 = sameKey ? searchTerm1 : "hiking boots";

            Map<String, UUID> mappings = new LinkedHashMap<>();
            mappings.put(campExt, UUID.randomUUID());
            mappings.put(agExt1, UUID.randomUUID());
            if (!sameKey) {
                mappings.put(agExt2, UUID.randomUUID());
            }

            Map<String, Object> row1 = new LinkedHashMap<>();
            row1.put("campaignId", campExt);
            row1.put("adGroupId", agExt1);
            row1.put("searchTerm", searchTerm1);
            row1.put("date", reportDate);
            row1.put("impressions", 500L);
            row1.put("clicks", 20);
            row1.put("orders", 3);
            row1.put("spend", BigDecimal.valueOf(10.00));
            row1.put("sales", BigDecimal.valueOf(45.00));
            row1.put("acos", BigDecimal.valueOf(0.22));
            row1.put("currency", "USD");

            Map<String, Object> row2 = new LinkedHashMap<>();
            row2.put("campaignId", campExt);
            row2.put("adGroupId", agExt2);
            row2.put("searchTerm", searchTerm2);
            row2.put("date", reportDate);
            row2.put("impressions", 800L);
            row2.put("clicks", 35);
            row2.put("orders", 5);
            row2.put("spend", BigDecimal.valueOf(15.00));
            row2.put("sales", BigDecimal.valueOf(60.00));
            row2.put("acos", BigDecimal.valueOf(0.25));
            row2.put("currency", "USD");

            return new SearchTermUpsertKeyData(storeId, reportDate, row1, row2, sameKey, mappings);
        });
    }
}
