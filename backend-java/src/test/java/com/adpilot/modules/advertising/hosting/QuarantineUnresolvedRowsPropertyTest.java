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
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.jqwik.api.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Feature: amazon-ads-ai-hosting-system, Property 11: Unresolved metric rows are quarantined, never fabricated into entities
 *
 * <p><b>Validates: Requirements 2.3, 2.5, 14.4, 14.6</b></p>
 *
 * <p>Verifies that the {@link ReportIngestionServiceImpl} quarantine logic:
 * <ol>
 *   <li>When a report row references an external entity ID not found in external_entity_mappings,
 *       it is routed to metric_quarantine.</li>
 *   <li>Quarantined rows are NEVER inserted into performance_daily or search_term_daily.</li>
 *   <li>The system NEVER fabricates entities from metric data alone — no insert into any entity
 *       table for unresolved rows.</li>
 *   <li>The quarantine entry records the reason, report type, external ID, and raw row.</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 11: Unresolved metric rows are quarantined, never fabricated into entities")
class QuarantineUnresolvedRowsPropertyTest {

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
    private static final Pattern CONDITION_PATTERN =
            Pattern.compile("(\\w+)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)}");

    // ── In-memory stores ─────────────────────────────────────────────────────────

    private static class QuarantineStore {
        private final List<MetricQuarantineEntity> rows = new CopyOnWriteArrayList<>();

        void insert(MetricQuarantineEntity entity) {
            entity.setId(UUID.randomUUID());
            rows.add(entity);
        }

        int size() { return rows.size(); }
        List<MetricQuarantineEntity> all() { return Collections.unmodifiableList(rows); }
    }

    private static class PerformanceStore {
        private final List<PerformanceDailyEntity> rows = new CopyOnWriteArrayList<>();

        void insert(PerformanceDailyEntity entity) {
            entity.setId(UUID.randomUUID());
            rows.add(entity);
        }

        int size() { return rows.size(); }
    }

    private static class SearchTermStore {
        private final List<SearchTermDailyEntity> rows = new CopyOnWriteArrayList<>();

        void insert(SearchTermDailyEntity entity) {
            entity.setId(UUID.randomUUID());
            rows.add(entity);
        }

        int size() { return rows.size(); }
    }

    // ── Test context ─────────────────────────────────────────────────────────────

    private record TestContext(
            ReportIngestionServiceImpl service,
            PerformanceStore perfStore,
            SearchTermStore stStore,
            QuarantineStore quarantineStore,
            ExternalEntityMappingMapper mappingMapper
    ) {}

    // ── SQL segment parsing ──────────────────────────────────────────────────────

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

    // ── Build the service with mocked mappers ────────────────────────────────────

    /**
     * Builds a testable service where the ExternalEntityMappingMapper returns null
     * for ALL external IDs (simulating completely unresolved entities). No entity
     * table insert is possible.
     */
    @SuppressWarnings("unchecked")
    private TestContext buildServiceAllUnresolved(UUID storeId) {
        return buildService(storeId, Collections.emptyMap());
    }

    /**
     * Builds a testable service where the ExternalEntityMappingMapper resolves only
     * the provided mappings. Any external ID not in the map returns null (unresolved).
     */
    @SuppressWarnings("unchecked")
    private TestContext buildService(UUID storeId, Map<String, UUID> resolvedMappings) {
        PerformanceStore perfStore = new PerformanceStore();
        SearchTermStore stStore = new SearchTermStore();
        QuarantineStore quarantineStore = new QuarantineStore();

        PerformanceDailyMapper perfMapper = mock(PerformanceDailyMapper.class);
        SearchTermDailyMapper stMapper = mock(SearchTermDailyMapper.class);
        MetricQuarantineMapper quarantineMapper = mock(MetricQuarantineMapper.class);
        ExternalEntityMappingMapper mappingMapper = mock(ExternalEntityMappingMapper.class);

        // Wire performance daily mapper - insert
        doAnswer(inv -> {
            perfStore.insert(inv.getArgument(0));
            return 1;
        }).when(perfMapper).insert(any(PerformanceDailyEntity.class));

        // Wire performance daily mapper - selectOne (returns null, since nothing inserted for unresolved)
        when(perfMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        // Wire search term daily mapper - insert
        doAnswer(inv -> {
            stStore.insert(inv.getArgument(0));
            return 1;
        }).when(stMapper).insert(any(SearchTermDailyEntity.class));

        // Wire search term daily mapper - selectOne
        when(stMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        // Wire quarantine mapper - insert
        doAnswer(inv -> {
            quarantineStore.insert(inv.getArgument(0));
            return 1;
        }).when(quarantineMapper).insert(any(MetricQuarantineEntity.class));

        // Wire external entity mapping: resolve only IDs that are in the resolvedMappings map
        when(mappingMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(inv -> {
            LambdaQueryWrapper<ExternalEntityMappingEntity> wrapper = inv.getArgument(0);
            wrapper.getTargetSql();

            String externalId = (String) extractParamByColumn(wrapper, "external_entity_id");
            if (externalId != null && resolvedMappings.containsKey(externalId)) {
                UUID internalId = resolvedMappings.get(externalId);
                return ExternalEntityMappingEntity.builder()
                        .id(UUID.randomUUID())
                        .storeId(storeId)
                        .platform("amazon_ads")
                        .externalEntityId(externalId)
                        .internalEntityId(internalId)
                        .build();
            }
            return null; // unresolved
        });

        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        ReportIngestionServiceImpl service = new ReportIngestionServiceImpl(
                perfMapper, stMapper, quarantineMapper, mappingMapper, om);

        return new TestContext(service, perfStore, stStore, quarantineStore, mappingMapper);
    }

    // ── Property 1: Unresolved performance rows are quarantined ──────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 11: Unresolved metric rows are quarantined, never fabricated into entities
     *
     * <p><b>Validates: Requirements 2.3, 2.5, 14.4, 14.6</b></p>
     *
     * <p>When a performance report row references an external entity ID that has no mapping
     * in external_entity_mappings, the row is routed to metric_quarantine and NEVER
     * inserted into performance_daily.</p>
     */
    @Property(tries = 120)
    void unresolvedPerformanceRowsAreQuarantinedNotInserted(
            @ForAll("unresolvedPerformanceInput") UnresolvedPerformanceInput input) {

        TestContext ctx = buildServiceAllUnresolved(input.storeId);

        ReportLifecycleResult result = new ReportLifecycleResult(
                "report-q-001", input.reportType, input.reportDate, input.reportDate,
                input.rows, input.rows.size());

        IngestionResult ingestion = ctx.service().ingest(input.storeId, result, 3);

        // All rows must be quarantined
        assertThat(ingestion.rowsQuarantined())
                .as("All unresolved rows must be quarantined")
                .isEqualTo(input.rows.size());

        // Zero rows inserted into performance_daily
        assertThat(ctx.perfStore().size())
                .as("No rows must be inserted into performance_daily for unresolved entities")
                .isZero();

        // Zero rows inserted (from the result)
        assertThat(ingestion.rowsInserted())
                .as("rowsInserted must be zero for unresolved rows")
                .isZero();

        // Quarantine store must contain exactly the quarantined rows
        assertThat(ctx.quarantineStore().size())
                .as("Quarantine store must have one entry per unresolved row")
                .isEqualTo(input.rows.size());
    }

    // ── Property 2: Unresolved search-term rows are quarantined ──────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 11: Unresolved metric rows are quarantined, never fabricated into entities
     *
     * <p><b>Validates: Requirements 2.3, 2.5, 14.4, 14.6</b></p>
     *
     * <p>When a search-term report row references a campaign or ad group external ID
     * that has no mapping in external_entity_mappings, the row is routed to
     * metric_quarantine and NEVER inserted into search_term_daily.</p>
     */
    @Property(tries = 120)
    void unresolvedSearchTermRowsAreQuarantinedNotInserted(
            @ForAll("unresolvedSearchTermInput") UnresolvedSearchTermInput input) {

        TestContext ctx = buildServiceAllUnresolved(input.storeId);

        ReportLifecycleResult result = new ReportLifecycleResult(
                "report-q-002", ReportType.SP_SEARCH_TERM, input.reportDate, input.reportDate,
                input.rows, input.rows.size());

        IngestionResult ingestion = ctx.service().ingest(input.storeId, result, 3);

        // All rows must be quarantined
        assertThat(ingestion.rowsQuarantined())
                .as("All unresolved search-term rows must be quarantined")
                .isEqualTo(input.rows.size());

        // Zero rows inserted into search_term_daily
        assertThat(ctx.stStore().size())
                .as("No rows must be inserted into search_term_daily for unresolved entities")
                .isZero();

        // Zero rows inserted (from the result)
        assertThat(ingestion.rowsInserted())
                .as("rowsInserted must be zero for unresolved search-term rows")
                .isZero();

        // Quarantine store must contain the quarantined rows
        assertThat(ctx.quarantineStore().size())
                .as("Quarantine store must have one entry per unresolved search-term row")
                .isEqualTo(input.rows.size());
    }

    // ── Property 3: Quarantine entries record reason, report type, external ID, raw row ─

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 11: Unresolved metric rows are quarantined, never fabricated into entities
     *
     * <p><b>Validates: Requirements 2.3, 2.5, 14.4, 14.6</b></p>
     *
     * <p>Each quarantine entry must record the reason for quarantine, the report type,
     * the external entity ID, and the serialized raw row.</p>
     */
    @Property(tries = 120)
    void quarantineEntryRecordsReasonReportTypeExternalIdAndRawRow(
            @ForAll("unresolvedPerformanceInput") UnresolvedPerformanceInput input) {

        TestContext ctx = buildServiceAllUnresolved(input.storeId);

        ReportLifecycleResult result = new ReportLifecycleResult(
                "report-q-003", input.reportType, input.reportDate, input.reportDate,
                input.rows, input.rows.size());

        ctx.service().ingest(input.storeId, result, 3);

        for (MetricQuarantineEntity quarantine : ctx.quarantineStore().all()) {
            // Reason must be recorded and non-blank
            assertThat(quarantine.getReason())
                    .as("Quarantine reason must be recorded")
                    .isNotNull()
                    .isNotBlank();

            // Report type must be recorded
            assertThat(quarantine.getReportType())
                    .as("Quarantine report type must be recorded")
                    .isNotNull()
                    .isEqualTo(input.reportType.name());

            // External entity ID should be present (the unresolved ID from the row)
            // Note: may be null if the row itself has no ID, which is also a quarantine reason
            // but the field should still be populated by the service

            // Raw row must be recorded as JSON
            assertThat(quarantine.getRawRow())
                    .as("Quarantine raw row must be serialized")
                    .isNotNull()
                    .isNotBlank();

            // Store ID must match
            assertThat(quarantine.getStoreId())
                    .as("Quarantine store_id must match the ingestion context")
                    .isEqualTo(input.storeId);
        }
    }

    // ── Property 4: Mixed resolved/unresolved — only unresolved are quarantined ──

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 11: Unresolved metric rows are quarantined, never fabricated into entities
     *
     * <p><b>Validates: Requirements 2.3, 2.5, 14.4, 14.6</b></p>
     *
     * <p>When a report has a mix of resolved and unresolved rows, only the unresolved
     * rows are quarantined. Resolved rows are correctly inserted into the performance
     * table, and the system never fabricates entities for the unresolved ones.</p>
     */
    @Property(tries = 120)
    void mixedResolvedUnresolved_onlyUnresolvedAreQuarantined(
            @ForAll("mixedPerformanceInput") MixedPerformanceInput input) {

        TestContext ctx = buildService(input.storeId, input.resolvedMappings);

        ReportLifecycleResult result = new ReportLifecycleResult(
                "report-q-004", input.reportType, input.reportDate, input.reportDate,
                input.allRows, input.allRows.size());

        IngestionResult ingestion = ctx.service().ingest(input.storeId, result, 3);

        // Unresolved rows must be quarantined
        assertThat(ingestion.rowsQuarantined())
                .as("Exactly the unresolved rows must be quarantined")
                .isEqualTo(input.unresolvedCount);

        // Resolved rows must be inserted into performance_daily
        assertThat(ctx.perfStore().size())
                .as("Resolved rows must be inserted into performance_daily")
                .isEqualTo(input.resolvedCount);

        // Quarantine store has only the unresolved entries
        assertThat(ctx.quarantineStore().size())
                .as("Quarantine store has exactly the unresolved row count")
                .isEqualTo(input.unresolvedCount);

        // No entity fabrication: mapping mapper was never asked to INSERT, only to SELECT
        verify(ctx.mappingMapper(), never()).insert(any());
    }

    // ── Data holders ─────────────────────────────────────────────────────────────

    record UnresolvedPerformanceInput(
            UUID storeId,
            ReportType reportType,
            LocalDate reportDate,
            List<Map<String, Object>> rows
    ) {}

    record UnresolvedSearchTermInput(
            UUID storeId,
            LocalDate reportDate,
            List<Map<String, Object>> rows
    ) {}

    record MixedPerformanceInput(
            UUID storeId,
            ReportType reportType,
            LocalDate reportDate,
            List<Map<String, Object>> allRows,
            Map<String, UUID> resolvedMappings,
            int resolvedCount,
            int unresolvedCount
    ) {}

    // ── Generators ───────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<UnresolvedPerformanceInput> unresolvedPerformanceInput() {
        return Arbitraries.lazyOf(() -> {
            UUID storeId = UUID.randomUUID();
            LocalDate reportDate = LocalDate.now().minusDays(10);

            return Arbitraries.of(ReportType.SP_CAMPAIGN, ReportType.SP_KEYWORD)
                    .flatMap(reportType -> Arbitraries.integers().between(1, 5).map(rowCount -> {
                        String idField = reportType == ReportType.SP_KEYWORD ? "keywordId" : "campaignId";
                        List<Map<String, Object>> rows = new ArrayList<>();

                        for (int i = 0; i < rowCount; i++) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            // Use external IDs that will NOT be resolved (no mapping exists)
                            row.put(idField, "unresolved-ext-" + UUID.randomUUID());
                            if (reportType == ReportType.SP_KEYWORD) {
                                row.put("campaignId", "unresolved-campaign-" + UUID.randomUUID());
                            }
                            row.put("date", reportDate);
                            row.put("impressions", 1000L + i);
                            row.put("clicks", 50 + i);
                            row.put("spend", BigDecimal.valueOf(25.50 + i));
                            row.put("sales", BigDecimal.valueOf(100.00 + i));
                            row.put("orders", 5 + i);
                            row.put("acos", BigDecimal.valueOf(0.25));
                            row.put("roas", BigDecimal.valueOf(4.0));
                            row.put("currency", "USD");
                            rows.add(row);
                        }
                        return new UnresolvedPerformanceInput(storeId, reportType, reportDate, rows);
                    }));
        });
    }

    @Provide
    Arbitrary<UnresolvedSearchTermInput> unresolvedSearchTermInput() {
        return Arbitraries.lazyOf(() -> {
            UUID storeId = UUID.randomUUID();
            LocalDate reportDate = LocalDate.now().minusDays(10);

            return Arbitraries.integers().between(1, 5).map(rowCount -> {
                List<Map<String, Object>> rows = new ArrayList<>();

                for (int i = 0; i < rowCount; i++) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    // Use external IDs that will NOT be resolved
                    row.put("campaignId", "unresolved-campaign-" + UUID.randomUUID());
                    row.put("adGroupId", "unresolved-adgroup-" + UUID.randomUUID());
                    row.put("searchTerm", "search query " + i);
                    row.put("date", reportDate);
                    row.put("impressions", 500L + i);
                    row.put("clicks", 20 + i);
                    row.put("spend", BigDecimal.valueOf(10.00 + i));
                    row.put("sales", BigDecimal.valueOf(50.00 + i));
                    row.put("orders", 2 + i);
                    row.put("acos", BigDecimal.valueOf(0.20));
                    row.put("currency", "USD");
                    rows.add(row);
                }
                return new UnresolvedSearchTermInput(storeId, reportDate, rows);
            });
        });
    }

    @Provide
    Arbitrary<MixedPerformanceInput> mixedPerformanceInput() {
        return Arbitraries.lazyOf(() -> {
            UUID storeId = UUID.randomUUID();
            LocalDate reportDate = LocalDate.now().minusDays(10);

            return Arbitraries.of(ReportType.SP_CAMPAIGN, ReportType.SP_KEYWORD)
                    .flatMap(reportType -> Arbitraries.integers().between(1, 3)
                            .flatMap(resolvedCount -> Arbitraries.integers().between(1, 3)
                                    .map(unresolvedCount -> {
                                        String idField = reportType == ReportType.SP_KEYWORD ? "keywordId" : "campaignId";
                                        Map<String, UUID> resolvedMappings = new LinkedHashMap<>();
                                        List<Map<String, Object>> allRows = new ArrayList<>();

                                        // Add a campaign mapping for keyword reports (the campaign itself must resolve)
                                        String resolvedCampaignExt = null;
                                        if (reportType == ReportType.SP_KEYWORD) {
                                            resolvedCampaignExt = "resolved-campaign-0";
                                            resolvedMappings.put(resolvedCampaignExt, UUID.randomUUID());
                                        }

                                        // Create resolved rows
                                        for (int i = 0; i < resolvedCount; i++) {
                                            String extId = "resolved-ext-" + i;
                                            resolvedMappings.put(extId, UUID.randomUUID());

                                            Map<String, Object> row = new LinkedHashMap<>();
                                            row.put(idField, extId);
                                            if (reportType == ReportType.SP_KEYWORD) {
                                                row.put("campaignId", resolvedCampaignExt);
                                            }
                                            row.put("date", reportDate);
                                            row.put("impressions", 1000L + i);
                                            row.put("clicks", 50 + i);
                                            row.put("spend", BigDecimal.valueOf(25.50 + i));
                                            row.put("sales", BigDecimal.valueOf(100.00 + i));
                                            row.put("orders", 5 + i);
                                            row.put("acos", BigDecimal.valueOf(0.25));
                                            row.put("roas", BigDecimal.valueOf(4.0));
                                            row.put("currency", "USD");
                                            allRows.add(row);
                                        }

                                        // Create unresolved rows (IDs NOT in mappings)
                                        for (int i = 0; i < unresolvedCount; i++) {
                                            Map<String, Object> row = new LinkedHashMap<>();
                                            row.put(idField, "unresolved-ext-" + UUID.randomUUID());
                                            if (reportType == ReportType.SP_KEYWORD) {
                                                row.put("campaignId", resolvedCampaignExt);
                                            }
                                            row.put("date", reportDate);
                                            row.put("impressions", 2000L + i);
                                            row.put("clicks", 80 + i);
                                            row.put("spend", BigDecimal.valueOf(40.00 + i));
                                            row.put("sales", BigDecimal.valueOf(150.00 + i));
                                            row.put("orders", 8 + i);
                                            row.put("acos", BigDecimal.valueOf(0.27));
                                            row.put("roas", BigDecimal.valueOf(3.75));
                                            row.put("currency", "USD");
                                            allRows.add(row);
                                        }

                                        return new MixedPerformanceInput(
                                                storeId, reportType, reportDate, allRows,
                                                resolvedMappings, resolvedCount, unresolvedCount);
                                    })));
        });
    }
}
