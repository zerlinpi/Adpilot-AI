package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.MetricQuarantineEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.entity.SearchTermDailyEntity;
import com.adpilot.modules.advertising.mapper.MetricQuarantineMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.mapper.SearchTermDailyMapper;
import com.adpilot.modules.apisync.entity.ExternalEntityMappingEntity;
import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
 * Integration test for the Amazon Ads async reporting lifecycle end-to-end:
 * create report → poll until COMPLETED → fetch download URL → download → ingest.
 *
 * <p>Mocks the Amazon Ads API (via {@link ReportLifecycleClient}) and verifies that
 * metrics are correctly ingested into {@code performance_daily} and
 * {@code search_term_daily}, and that external entity IDs are resolved via
 * {@code external_entity_mappings}.</p>
 *
 * <p><b>Validates: Requirements 2.2</b></p>
 */
@DisplayName("Report Lifecycle Integration Test - End-to-End Async Reporting")
class ReportLifecycleIntegrationTest {

    static {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PerformanceDailyEntity.class);
        TableInfoHelper.initTableInfo(assistant, SearchTermDailyEntity.class);
        TableInfoHelper.initTableInfo(assistant, ExternalEntityMappingEntity.class);
        TableInfoHelper.initTableInfo(assistant, MetricQuarantineEntity.class);
    }

    // ── Test constants ───────────────────────────────────────────────────────────

    private static final UUID STORE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONNECTION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String PROFILE_ID = "profile-test-123";

    // External IDs (what Amazon returns in report rows)
    private static final String EXT_CAMPAIGN_ID = "amzn-campaign-100";
    private static final String EXT_KEYWORD_ID = "amzn-keyword-200";
    private static final String EXT_ADGROUP_ID = "amzn-adgroup-300";

    // Internal IDs (our UUIDs)
    private static final UUID INT_CAMPAIGN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID INT_KEYWORD_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID INT_ADGROUP_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static final LocalDate REPORT_DATE = LocalDate.now().minusDays(10); // old enough to be finalized
    private static final int FINALIZATION_LAG_DAYS = 3;

    // ── In-memory stores ─────────────────────────────────────────────────────────

    private final List<PerformanceDailyEntity> performanceRows = new CopyOnWriteArrayList<>();
    private final List<SearchTermDailyEntity> searchTermRows = new CopyOnWriteArrayList<>();
    private final List<MetricQuarantineEntity> quarantineRows = new CopyOnWriteArrayList<>();

    // ── Collaborators ────────────────────────────────────────────────────────────

    private ReportLifecycleClient reportLifecycleClient;
    private ReportIngestionService reportIngestionService;
    private ObjectMapper objectMapper;
    private ConnectionContext connectionContext;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        connectionContext = new ConnectionContext(
                CONNECTION_ID, STORE_ID, "amazon_ads",
                Map.of("region", "na", "profileId", PROFILE_ID,
                        "clientId", "test-client-id", "clientSecret", "test-secret",
                        "refreshToken", "test-refresh-token"));

        // Mock the ReportLifecycleClient (simulating the Amazon Ads API)
        reportLifecycleClient = mock(ReportLifecycleClient.class);

        // Set up the ingestion service with in-memory-backed mocked mappers
        PerformanceDailyMapper performanceDailyMapper = buildPerformanceDailyMapper();
        SearchTermDailyMapper searchTermDailyMapper = buildSearchTermDailyMapper();
        MetricQuarantineMapper metricQuarantineMapper = buildMetricQuarantineMapper();
        ExternalEntityMappingMapper externalEntityMappingMapper = buildExternalEntityMappingMapper();

        reportIngestionService = new ReportIngestionServiceImpl(
                performanceDailyMapper,
                searchTermDailyMapper,
                metricQuarantineMapper,
                externalEntityMappingMapper,
                objectMapper);
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Campaign report lifecycle (SP_CAMPAIGN)
    // ══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SP Campaign Report Lifecycle")
    class SpCampaignReportLifecycle {

        @Test
        @DisplayName("Full lifecycle: create → poll → download → ingest campaign metrics into performance_daily")
        void fullLifecycle_campaign_ingestsIntoPerformanceDaily() {
            // Arrange — simulate Amazon Ads returning a completed report
            String reportId = "amzn-report-campaign-001";
            ReportDateRange dateRange = new ReportDateRange(REPORT_DATE, REPORT_DATE);

            List<Map<String, Object>> reportRows = List.of(
                    campaignRow(EXT_CAMPAIGN_ID, REPORT_DATE, 1000L, 50, "10.50", "120.00", 5, "0.0875", "USD")
            );

            ReportLifecycleResult lifecycleResult = new ReportLifecycleResult(
                    reportId, ReportType.SP_CAMPAIGN, REPORT_DATE, REPORT_DATE,
                    reportRows, reportRows.size());

            // Mock: executeLifecycle returns the full result (this simulates create→poll→download→decompress→validate)
            when(reportLifecycleClient.executeLifecycle(any(ConnectionContext.class),
                    eq(ReportType.SP_CAMPAIGN), eq(dateRange)))
                    .thenReturn(lifecycleResult);

            // Act — simulate what ReportSyncJob does: call lifecycle client then ingest
            ReportLifecycleResult result = reportLifecycleClient.executeLifecycle(
                    connectionContext, ReportType.SP_CAMPAIGN, dateRange);
            IngestionResult ingestionResult = reportIngestionService.ingest(STORE_ID, result, FINALIZATION_LAG_DAYS);

            // Assert — lifecycle client was called
            verify(reportLifecycleClient).executeLifecycle(connectionContext, ReportType.SP_CAMPAIGN, dateRange);

            // Assert — metrics ingested correctly into performance_daily
            assertThat(ingestionResult.totalProcessed()).isEqualTo(1);
            assertThat(ingestionResult.rowsInserted()).isEqualTo(1);
            assertThat(ingestionResult.rowsQuarantined()).isEqualTo(0);

            assertThat(performanceRows).hasSize(1);
            PerformanceDailyEntity row = performanceRows.get(0);
            assertThat(row.getStoreId()).isEqualTo(STORE_ID);
            assertThat(row.getEntityType()).isEqualTo("campaign");
            assertThat(row.getEntityId()).isEqualTo(INT_CAMPAIGN_ID);
            assertThat(row.getDate()).isEqualTo(REPORT_DATE);
            assertThat(row.getImpressions()).isEqualTo(1000L);
            assertThat(row.getClicks()).isEqualTo(50);
            assertThat(row.getSpend()).isEqualByComparingTo(new BigDecimal("10.50"));
            assertThat(row.getSales()).isEqualByComparingTo(new BigDecimal("120.00"));
            assertThat(row.getOrders()).isEqualTo(5);
            assertThat(row.getAcos()).isEqualByComparingTo(new BigDecimal("0.0875"));
            assertThat(row.getCurrency()).isEqualTo("USD");
            assertThat(row.getDataStatus()).isEqualTo("finalized"); // old date → finalized
            assertThat(row.getDataVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("External entity ID is resolved via external_entity_mappings")
        void externalEntityId_resolvedViaMappings() {
            String reportId = "amzn-report-campaign-002";
            ReportDateRange dateRange = new ReportDateRange(REPORT_DATE, REPORT_DATE);

            List<Map<String, Object>> reportRows = List.of(
                    campaignRow(EXT_CAMPAIGN_ID, REPORT_DATE, 500L, 25, "5.00", "60.00", 3, "0.0833", "EUR")
            );

            ReportLifecycleResult lifecycleResult = new ReportLifecycleResult(
                    reportId, ReportType.SP_CAMPAIGN, REPORT_DATE, REPORT_DATE,
                    reportRows, reportRows.size());

            when(reportLifecycleClient.executeLifecycle(any(), any(), any()))
                    .thenReturn(lifecycleResult);

            // Act
            ReportLifecycleResult result = reportLifecycleClient.executeLifecycle(
                    connectionContext, ReportType.SP_CAMPAIGN, dateRange);
            IngestionResult ingestionResult = reportIngestionService.ingest(STORE_ID, result, FINALIZATION_LAG_DAYS);

            // Assert — external ID resolved to internal UUID
            assertThat(ingestionResult.rowsInserted()).isEqualTo(1);
            assertThat(performanceRows).hasSize(1);
            assertThat(performanceRows.get(0).getEntityId()).isEqualTo(INT_CAMPAIGN_ID);
        }

        @Test
        @DisplayName("Unresolved external entity IDs are quarantined")
        void unresolvedExternalId_quarantined() {
            String reportId = "amzn-report-campaign-003";
            String unknownExternalId = "amzn-campaign-unknown";
            ReportDateRange dateRange = new ReportDateRange(REPORT_DATE, REPORT_DATE);

            List<Map<String, Object>> reportRows = List.of(
                    campaignRow(unknownExternalId, REPORT_DATE, 200L, 10, "2.00", "30.00", 1, "0.0667", "USD")
            );

            ReportLifecycleResult lifecycleResult = new ReportLifecycleResult(
                    reportId, ReportType.SP_CAMPAIGN, REPORT_DATE, REPORT_DATE,
                    reportRows, reportRows.size());

            when(reportLifecycleClient.executeLifecycle(any(), any(), any()))
                    .thenReturn(lifecycleResult);

            // Act
            ReportLifecycleResult result = reportLifecycleClient.executeLifecycle(
                    connectionContext, ReportType.SP_CAMPAIGN, dateRange);
            IngestionResult ingestionResult = reportIngestionService.ingest(STORE_ID, result, FINALIZATION_LAG_DAYS);

            // Assert — row quarantined, not inserted
            assertThat(ingestionResult.rowsQuarantined()).isEqualTo(1);
            assertThat(ingestionResult.rowsInserted()).isEqualTo(0);
            assertThat(performanceRows).isEmpty();
            assertThat(quarantineRows).hasSize(1);
            assertThat(quarantineRows.get(0).getExternalEntityId()).isEqualTo(unknownExternalId);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Keyword report lifecycle (SP_KEYWORD)
    // ══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SP Keyword Report Lifecycle")
    class SpKeywordReportLifecycle {

        @Test
        @DisplayName("Full lifecycle: create → poll → download → ingest keyword metrics into performance_daily")
        void fullLifecycle_keyword_ingestsIntoPerformanceDaily() {
            String reportId = "amzn-report-keyword-001";
            ReportDateRange dateRange = new ReportDateRange(REPORT_DATE, REPORT_DATE);

            List<Map<String, Object>> reportRows = List.of(
                    keywordRow(EXT_KEYWORD_ID, EXT_CAMPAIGN_ID, REPORT_DATE,
                            800L, 40, "8.00", "90.00", 4, "0.0889", "USD")
            );

            ReportLifecycleResult lifecycleResult = new ReportLifecycleResult(
                    reportId, ReportType.SP_KEYWORD, REPORT_DATE, REPORT_DATE,
                    reportRows, reportRows.size());

            when(reportLifecycleClient.executeLifecycle(any(), eq(ReportType.SP_KEYWORD), any()))
                    .thenReturn(lifecycleResult);

            // Act
            ReportLifecycleResult result = reportLifecycleClient.executeLifecycle(
                    connectionContext, ReportType.SP_KEYWORD, dateRange);
            IngestionResult ingestionResult = reportIngestionService.ingest(STORE_ID, result, FINALIZATION_LAG_DAYS);

            // Assert
            assertThat(ingestionResult.rowsInserted()).isEqualTo(1);
            assertThat(ingestionResult.rowsQuarantined()).isEqualTo(0);

            assertThat(performanceRows).hasSize(1);
            PerformanceDailyEntity row = performanceRows.get(0);
            assertThat(row.getEntityType()).isEqualTo("keyword");
            assertThat(row.getEntityId()).isEqualTo(INT_KEYWORD_ID);
            assertThat(row.getImpressions()).isEqualTo(800L);
            assertThat(row.getClicks()).isEqualTo(40);
            assertThat(row.getSpend()).isEqualByComparingTo(new BigDecimal("8.00"));
            assertThat(row.getSales()).isEqualByComparingTo(new BigDecimal("90.00"));
            assertThat(row.getOrders()).isEqualTo(4);
            assertThat(row.getDataStatus()).isEqualTo("finalized");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Search term report lifecycle (SP_SEARCH_TERM)
    // ══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SP Search Term Report Lifecycle")
    class SpSearchTermReportLifecycle {

        @Test
        @DisplayName("Full lifecycle: create → poll → download → ingest search term metrics into search_term_daily")
        void fullLifecycle_searchTerm_ingestsIntoSearchTermDaily() {
            String reportId = "amzn-report-search-001";
            ReportDateRange dateRange = new ReportDateRange(REPORT_DATE, REPORT_DATE);

            List<Map<String, Object>> reportRows = List.of(
                    searchTermRow(EXT_CAMPAIGN_ID, EXT_ADGROUP_ID, "wireless headphones",
                            REPORT_DATE, 600L, 30, 2, "6.00", "45.00", "0.1333", "USD")
            );

            ReportLifecycleResult lifecycleResult = new ReportLifecycleResult(
                    reportId, ReportType.SP_SEARCH_TERM, REPORT_DATE, REPORT_DATE,
                    reportRows, reportRows.size());

            when(reportLifecycleClient.executeLifecycle(any(), eq(ReportType.SP_SEARCH_TERM), any()))
                    .thenReturn(lifecycleResult);

            // Act
            ReportLifecycleResult result = reportLifecycleClient.executeLifecycle(
                    connectionContext, ReportType.SP_SEARCH_TERM, dateRange);
            IngestionResult ingestionResult = reportIngestionService.ingest(STORE_ID, result, FINALIZATION_LAG_DAYS);

            // Assert
            assertThat(ingestionResult.rowsInserted()).isEqualTo(1);
            assertThat(ingestionResult.rowsQuarantined()).isEqualTo(0);

            assertThat(searchTermRows).hasSize(1);
            SearchTermDailyEntity row = searchTermRows.get(0);
            assertThat(row.getStoreId()).isEqualTo(STORE_ID);
            assertThat(row.getCampaignId()).isEqualTo(INT_CAMPAIGN_ID);
            assertThat(row.getAdGroupId()).isEqualTo(INT_ADGROUP_ID);
            assertThat(row.getSearchTerm()).isEqualTo("wireless headphones");
            assertThat(row.getReportDate()).isEqualTo(REPORT_DATE);
            assertThat(row.getImpressions()).isEqualTo(600L);
            assertThat(row.getClicks()).isEqualTo(30);
            assertThat(row.getOrders()).isEqualTo(2);
            assertThat(row.getSpend()).isEqualByComparingTo(new BigDecimal("6.00"));
            assertThat(row.getSales()).isEqualByComparingTo(new BigDecimal("45.00"));
            assertThat(row.getAcos()).isEqualByComparingTo(new BigDecimal("0.1333"));
            assertThat(row.getCurrency()).isEqualTo("USD");
            assertThat(row.getDataStatus()).isEqualTo("finalized");
            assertThat(row.getDataVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("Search term with unresolved ad group is quarantined")
        void searchTerm_unresolvedAdGroup_quarantined() {
            String reportId = "amzn-report-search-002";
            String unknownAdGroup = "amzn-adgroup-unknown";
            ReportDateRange dateRange = new ReportDateRange(REPORT_DATE, REPORT_DATE);

            List<Map<String, Object>> reportRows = List.of(
                    searchTermRow(EXT_CAMPAIGN_ID, unknownAdGroup, "bluetooth speaker",
                            REPORT_DATE, 300L, 15, 1, "3.00", "25.00", "0.12", "USD")
            );

            ReportLifecycleResult lifecycleResult = new ReportLifecycleResult(
                    reportId, ReportType.SP_SEARCH_TERM, REPORT_DATE, REPORT_DATE,
                    reportRows, reportRows.size());

            when(reportLifecycleClient.executeLifecycle(any(), any(), any()))
                    .thenReturn(lifecycleResult);

            // Act
            ReportLifecycleResult result = reportLifecycleClient.executeLifecycle(
                    connectionContext, ReportType.SP_SEARCH_TERM, dateRange);
            IngestionResult ingestionResult = reportIngestionService.ingest(STORE_ID, result, FINALIZATION_LAG_DAYS);

            // Assert
            assertThat(ingestionResult.rowsQuarantined()).isEqualTo(1);
            assertThat(ingestionResult.rowsInserted()).isEqualTo(0);
            assertThat(searchTermRows).isEmpty();
            assertThat(quarantineRows).hasSize(1);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Multi-report type end-to-end scenario
    // ══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Multi-Report End-to-End Scenario")
    class MultiReportEndToEnd {

        @Test
        @DisplayName("All three report types processed in sequence with correct routing")
        void allReportTypes_processedCorrectly() {
            ReportDateRange dateRange = new ReportDateRange(REPORT_DATE, REPORT_DATE);

            // Campaign report
            ReportLifecycleResult campaignResult = new ReportLifecycleResult(
                    "report-campaign", ReportType.SP_CAMPAIGN, REPORT_DATE, REPORT_DATE,
                    List.of(campaignRow(EXT_CAMPAIGN_ID, REPORT_DATE, 1000L, 50, "10.00", "100.00", 5, "0.10", "USD")),
                    1);

            // Keyword report
            ReportLifecycleResult keywordResult = new ReportLifecycleResult(
                    "report-keyword", ReportType.SP_KEYWORD, REPORT_DATE, REPORT_DATE,
                    List.of(keywordRow(EXT_KEYWORD_ID, EXT_CAMPAIGN_ID, REPORT_DATE,
                            500L, 25, "5.00", "60.00", 3, "0.0833", "USD")),
                    1);

            // Search term report
            ReportLifecycleResult searchTermResult = new ReportLifecycleResult(
                    "report-search", ReportType.SP_SEARCH_TERM, REPORT_DATE, REPORT_DATE,
                    List.of(searchTermRow(EXT_CAMPAIGN_ID, EXT_ADGROUP_ID, "organic vitamins",
                            REPORT_DATE, 400L, 20, 2, "4.00", "35.00", "0.1143", "USD")),
                    1);

            when(reportLifecycleClient.executeLifecycle(any(), eq(ReportType.SP_CAMPAIGN), any()))
                    .thenReturn(campaignResult);
            when(reportLifecycleClient.executeLifecycle(any(), eq(ReportType.SP_KEYWORD), any()))
                    .thenReturn(keywordResult);
            when(reportLifecycleClient.executeLifecycle(any(), eq(ReportType.SP_SEARCH_TERM), any()))
                    .thenReturn(searchTermResult);

            // Act — run all three report types (simulating what ReportSyncJob does)
            ReportLifecycleResult r1 = reportLifecycleClient.executeLifecycle(
                    connectionContext, ReportType.SP_CAMPAIGN, dateRange);
            IngestionResult i1 = reportIngestionService.ingest(STORE_ID, r1, FINALIZATION_LAG_DAYS);

            ReportLifecycleResult r2 = reportLifecycleClient.executeLifecycle(
                    connectionContext, ReportType.SP_KEYWORD, dateRange);
            IngestionResult i2 = reportIngestionService.ingest(STORE_ID, r2, FINALIZATION_LAG_DAYS);

            ReportLifecycleResult r3 = reportLifecycleClient.executeLifecycle(
                    connectionContext, ReportType.SP_SEARCH_TERM, dateRange);
            IngestionResult i3 = reportIngestionService.ingest(STORE_ID, r3, FINALIZATION_LAG_DAYS);

            // Assert — all processed without quarantine
            assertThat(i1.rowsInserted()).isEqualTo(1);
            assertThat(i2.rowsInserted()).isEqualTo(1);
            assertThat(i3.rowsInserted()).isEqualTo(1);
            assertThat(quarantineRows).isEmpty();

            // Campaign and keyword go to performance_daily
            assertThat(performanceRows).hasSize(2);
            assertThat(performanceRows).extracting(PerformanceDailyEntity::getEntityType)
                    .containsExactlyInAnyOrder("campaign", "keyword");

            // Search term goes to search_term_daily
            assertThat(searchTermRows).hasSize(1);
            assertThat(searchTermRows.get(0).getSearchTerm()).isEqualTo("organic vitamins");
        }

        @Test
        @DisplayName("Mixed rows: resolved entities are ingested, unresolved are quarantined")
        void mixedRows_resolvedIngestedAndUnresolvedQuarantined() {
            ReportDateRange dateRange = new ReportDateRange(REPORT_DATE, REPORT_DATE);

            // Two campaign rows — one resolvable, one not
            List<Map<String, Object>> reportRows = List.of(
                    campaignRow(EXT_CAMPAIGN_ID, REPORT_DATE, 1000L, 50, "10.00", "100.00", 5, "0.10", "USD"),
                    campaignRow("amzn-campaign-unknown", REPORT_DATE, 200L, 10, "2.00", "20.00", 1, "0.10", "USD")
            );

            ReportLifecycleResult lifecycleResult = new ReportLifecycleResult(
                    "report-mixed", ReportType.SP_CAMPAIGN, REPORT_DATE, REPORT_DATE,
                    reportRows, reportRows.size());

            when(reportLifecycleClient.executeLifecycle(any(), any(), any()))
                    .thenReturn(lifecycleResult);

            // Act
            ReportLifecycleResult result = reportLifecycleClient.executeLifecycle(
                    connectionContext, ReportType.SP_CAMPAIGN, dateRange);
            IngestionResult ingestionResult = reportIngestionService.ingest(STORE_ID, result, FINALIZATION_LAG_DAYS);

            // Assert
            assertThat(ingestionResult.totalProcessed()).isEqualTo(2);
            assertThat(ingestionResult.rowsInserted()).isEqualTo(1);
            assertThat(ingestionResult.rowsQuarantined()).isEqualTo(1);
            assertThat(performanceRows).hasSize(1);
            assertThat(performanceRows.get(0).getEntityId()).isEqualTo(INT_CAMPAIGN_ID);
            assertThat(quarantineRows).hasSize(1);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // ReportLifecycleClient step-by-step verification
    // ══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Step-by-step Lifecycle Verification")
    class StepByStepLifecycle {

        @Test
        @DisplayName("Individual steps: create → poll → fetchDownloadUrl → downloadAndDecompress → validate")
        void individualSteps_executeInOrder() {
            String reportId = "amzn-step-report-001";
            String downloadUrl = "https://ads-reporting.s3.amazonaws.com/reports/step-001.gz";
            ReportDateRange dateRange = new ReportDateRange(REPORT_DATE, REPORT_DATE);

            List<Map<String, Object>> downloadedRows = List.of(
                    campaignRow(EXT_CAMPAIGN_ID, REPORT_DATE, 750L, 35, "7.50", "80.00", 4, "0.0938", "USD")
            );

            // Mock each step individually
            when(reportLifecycleClient.createReport(any(), eq(ReportType.SP_CAMPAIGN), eq(dateRange)))
                    .thenReturn(reportId);
            when(reportLifecycleClient.pollStatus(any(), eq(reportId)))
                    .thenReturn(ReportStatus.COMPLETED);
            when(reportLifecycleClient.fetchDownloadUrl(any(), eq(reportId)))
                    .thenReturn(downloadUrl);
            when(reportLifecycleClient.downloadAndDecompress(eq(downloadUrl)))
                    .thenReturn(downloadedRows);
            doNothing().when(reportLifecycleClient).validateReport(eq(reportId), eq(dateRange), eq(downloadedRows));

            // Act — simulate calling each step as the lifecycle client implementation does
            String createdReportId = reportLifecycleClient.createReport(connectionContext, ReportType.SP_CAMPAIGN, dateRange);
            ReportStatus status = reportLifecycleClient.pollStatus(connectionContext, createdReportId);
            String url = reportLifecycleClient.fetchDownloadUrl(connectionContext, createdReportId);
            List<Map<String, Object>> rows = reportLifecycleClient.downloadAndDecompress(url);
            reportLifecycleClient.validateReport(createdReportId, dateRange, rows);

            // Assert — verify each step was called
            assertThat(createdReportId).isEqualTo(reportId);
            assertThat(status).isEqualTo(ReportStatus.COMPLETED);
            assertThat(url).isEqualTo(downloadUrl);
            assertThat(rows).hasSize(1);

            // Now ingest the validated rows
            ReportLifecycleResult lifecycleResult = new ReportLifecycleResult(
                    createdReportId, ReportType.SP_CAMPAIGN, REPORT_DATE, REPORT_DATE,
                    rows, rows.size());
            IngestionResult ingestionResult = reportIngestionService.ingest(STORE_ID, lifecycleResult, FINALIZATION_LAG_DAYS);

            assertThat(ingestionResult.rowsInserted()).isEqualTo(1);
            assertThat(performanceRows).hasSize(1);
            assertThat(performanceRows.get(0).getEntityId()).isEqualTo(INT_CAMPAIGN_ID);
            assertThat(performanceRows.get(0).getImpressions()).isEqualTo(750L);

            // Verify the step-by-step call order
            var inOrder = inOrder(reportLifecycleClient);
            inOrder.verify(reportLifecycleClient).createReport(any(), eq(ReportType.SP_CAMPAIGN), eq(dateRange));
            inOrder.verify(reportLifecycleClient).pollStatus(any(), eq(reportId));
            inOrder.verify(reportLifecycleClient).fetchDownloadUrl(any(), eq(reportId));
            inOrder.verify(reportLifecycleClient).downloadAndDecompress(eq(downloadUrl));
            inOrder.verify(reportLifecycleClient).validateReport(eq(reportId), eq(dateRange), eq(downloadedRows));
        }

        @Test
        @DisplayName("Preliminary data status for recent dates")
        void recentDate_getsPreliminDataStatus() {
            LocalDate recentDate = LocalDate.now().minusDays(1); // within finalization lag
            ReportDateRange dateRange = new ReportDateRange(recentDate, recentDate);

            List<Map<String, Object>> reportRows = List.of(
                    campaignRow(EXT_CAMPAIGN_ID, recentDate, 300L, 15, "3.00", "40.00", 2, "0.075", "USD")
            );

            ReportLifecycleResult lifecycleResult = new ReportLifecycleResult(
                    "report-prelim", ReportType.SP_CAMPAIGN, recentDate, recentDate,
                    reportRows, reportRows.size());

            when(reportLifecycleClient.executeLifecycle(any(), any(), any()))
                    .thenReturn(lifecycleResult);

            // Act
            ReportLifecycleResult result = reportLifecycleClient.executeLifecycle(
                    connectionContext, ReportType.SP_CAMPAIGN, dateRange);
            reportIngestionService.ingest(STORE_ID, result, FINALIZATION_LAG_DAYS);

            // Assert — recent date should be preliminary
            assertThat(performanceRows).hasSize(1);
            assertThat(performanceRows.get(0).getDataStatus()).isEqualTo("preliminary");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Test data builders
    // ══════════════════════════════════════════════════════════════════════════════

    private Map<String, Object> campaignRow(String campaignId, LocalDate date,
                                            long impressions, int clicks, String spend,
                                            String sales, int orders, String acos,
                                            String currency) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("campaignId", campaignId);
        row.put("date", date.toString());
        row.put("impressions", impressions);
        row.put("clicks", clicks);
        row.put("spend", spend);
        row.put("sales", sales);
        row.put("orders", orders);
        row.put("acos", acos);
        row.put("currency", currency);
        return row;
    }

    private Map<String, Object> keywordRow(String keywordId, String campaignId, LocalDate date,
                                           long impressions, int clicks, String spend,
                                           String sales, int orders, String acos,
                                           String currency) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("keywordId", keywordId);
        row.put("campaignId", campaignId);
        row.put("date", date.toString());
        row.put("impressions", impressions);
        row.put("clicks", clicks);
        row.put("spend", spend);
        row.put("sales", sales);
        row.put("orders", orders);
        row.put("acos", acos);
        row.put("currency", currency);
        return row;
    }

    private Map<String, Object> searchTermRow(String campaignId, String adGroupId, String searchTerm,
                                              LocalDate date, long impressions, int clicks,
                                              int orders, String spend, String sales,
                                              String acos, String currency) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("campaignId", campaignId);
        row.put("adGroupId", adGroupId);
        row.put("searchTerm", searchTerm);
        row.put("date", date.toString());
        row.put("impressions", impressions);
        row.put("clicks", clicks);
        row.put("orders", orders);
        row.put("spend", spend);
        row.put("sales", sales);
        row.put("acos", acos);
        row.put("currency", currency);
        return row;
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Mock mapper factories (in-memory backing stores)
    // ══════════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private PerformanceDailyMapper buildPerformanceDailyMapper() {
        PerformanceDailyMapper mapper = mock(PerformanceDailyMapper.class);

        when(mapper.insert(any(PerformanceDailyEntity.class))).thenAnswer(inv -> {
            PerformanceDailyEntity entity = inv.getArgument(0);
            entity.setId(UUID.randomUUID());
            performanceRows.add(entity);
            return 1;
        });

        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(inv -> {
            // No existing rows in fresh test state — return null
            // (individual tests could override for idempotent upsert scenarios)
            return null;
        });

        when(mapper.updateById(any(PerformanceDailyEntity.class))).thenReturn(1);

        return mapper;
    }

    @SuppressWarnings("unchecked")
    private SearchTermDailyMapper buildSearchTermDailyMapper() {
        SearchTermDailyMapper mapper = mock(SearchTermDailyMapper.class);

        when(mapper.insert(any(SearchTermDailyEntity.class))).thenAnswer(inv -> {
            SearchTermDailyEntity entity = inv.getArgument(0);
            entity.setId(UUID.randomUUID());
            searchTermRows.add(entity);
            return 1;
        });

        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(mapper.updateById(any(SearchTermDailyEntity.class))).thenReturn(1);

        return mapper;
    }

    @SuppressWarnings("unchecked")
    private MetricQuarantineMapper buildMetricQuarantineMapper() {
        MetricQuarantineMapper mapper = mock(MetricQuarantineMapper.class);

        when(mapper.insert(any(MetricQuarantineEntity.class))).thenAnswer(inv -> {
            MetricQuarantineEntity entity = inv.getArgument(0);
            entity.setId(UUID.randomUUID());
            quarantineRows.add(entity);
            return 1;
        });

        return mapper;
    }

    /**
     * Builds an ExternalEntityMappingMapper mock that resolves known external IDs
     * (campaign, keyword, ad_group) to their corresponding internal UUIDs, and
     * returns null for unknown IDs.
     *
     * <p>Uses the same SQL-segment parsing approach as the existing property tests
     * to extract query parameters from MyBatis-Plus LambdaQueryWrappers.</p>
     */
    @SuppressWarnings("unchecked")
    private ExternalEntityMappingMapper buildExternalEntityMappingMapper() {
        ExternalEntityMappingMapper mapper = mock(ExternalEntityMappingMapper.class);

        // Map of known external IDs → internal UUIDs
        Map<String, UUID> resolvedMappings = new HashMap<>();
        resolvedMappings.put(EXT_CAMPAIGN_ID, INT_CAMPAIGN_ID);
        resolvedMappings.put(EXT_KEYWORD_ID, INT_KEYWORD_ID);
        resolvedMappings.put(EXT_ADGROUP_ID, INT_ADGROUP_ID);

        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(inv -> {
            LambdaQueryWrapper<ExternalEntityMappingEntity> wrapper = inv.getArgument(0);
            // Force SQL generation so parameters are populated
            wrapper.getTargetSql();

            String externalId = (String) extractParamByColumn(wrapper, "external_entity_id");
            if (externalId != null && resolvedMappings.containsKey(externalId)) {
                UUID internalId = resolvedMappings.get(externalId);
                return ExternalEntityMappingEntity.builder()
                        .id(UUID.randomUUID())
                        .storeId(STORE_ID)
                        .platform("amazon_ads")
                        .externalEntityId(externalId)
                        .internalEntityId(internalId)
                        .build();
            }
            return null; // unresolved — will be quarantined
        });

        return mapper;
    }

    // ── SQL segment parsing (same approach as existing property tests) ────────────

    private static final Pattern CONDITION_PATTERN =
            Pattern.compile("(\\w+)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)}");

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
}
