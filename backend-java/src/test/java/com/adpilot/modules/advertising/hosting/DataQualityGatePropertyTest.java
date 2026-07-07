package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.feishu.service.FeishuService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based test for the {@link DataQualityGateImpl#check} method.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 13: Data quality gate decision
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.5, 6.6, 25.5, 30.2</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>Fresh data (within window) + complete finalized coverage → passes</li>
 *   <li>Stale data (beyond freshness window) → fails with DATA_STALE</li>
 *   <li>Missing/incomplete finalized coverage → fails with DATA_INCOMPLETE</li>
 *   <li>DB error or missing coverage info → fails (fail-closed, Req 3.5) — never passes on uncertain data</li>
 *   <li>Result is always one of: pass, DATA_STALE, or DATA_INCOMPLETE</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 13: Data quality gate decision")
class DataQualityGatePropertyTest {

    static {
        // Register entity metadata so MyBatis-Plus can resolve lambda columns
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PerformanceDailyEntity.class);
        TableInfoHelper.initTableInfo(assistant, ReportSyncRunEntity.class);
    }

    // ── Helper: build the gate with controlled mapper behavior ───────────────────

    /**
     * Builds a DataQualityGateImpl with mocked mappers.
     *
     * @param latestPerformanceDate the latest report_date returned by PerformanceDailyMapper
     *                              (null = no data found)
     * @param syncRuns              the list of finalized report sync runs returned
     * @param throwOnFreshness      if true, the PerformanceDailyMapper throws an exception
     * @param throwOnCompleteness   if true, the ReportSyncRunMapper throws an exception
     */
    @SuppressWarnings("unchecked")
    private DataQualityGateImpl buildGate(
            UUID storeId,
            UUID campaignId,
            LocalDate latestPerformanceDate,
            List<ReportSyncRunEntity> syncRuns,
            boolean throwOnFreshness,
            boolean throwOnCompleteness) {

        PerformanceDailyMapper perfMapper = mock(PerformanceDailyMapper.class);
        ReportSyncRunMapper syncRunMapper = mock(ReportSyncRunMapper.class);
        FeishuService feishuService = mock(FeishuService.class);

        // Configure freshness response
        if (throwOnFreshness) {
            when(perfMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenThrow(new RuntimeException("Simulated DB error on freshness check"));
        } else if (latestPerformanceDate == null) {
            when(perfMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(Collections.emptyList());
        } else {
            PerformanceDailyEntity entity = PerformanceDailyEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .campaignId(campaignId)
                    .date(latestPerformanceDate)
                    .build();
            when(perfMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(entity));
        }

        // Configure completeness response
        if (throwOnCompleteness) {
            when(syncRunMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenThrow(new RuntimeException("Simulated DB error on completeness check"));
        } else {
            when(syncRunMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(syncRuns != null ? syncRuns : Collections.emptyList());
        }

        // FeishuService is a no-op mock
        when(feishuService.pushAiNotification(any(), any(), any())).thenReturn(true);

        return new DataQualityGateImpl(perfMapper, syncRunMapper, feishuService);
    }

    /**
     * Build a list of finalized report sync runs that cover the entire lookback range.
     */
    private List<ReportSyncRunEntity> buildCompleteCoverage(UUID storeId, int lookbackDays) {
        LocalDate today = LocalDate.now();
        LocalDate lookbackStart = today.minusDays(lookbackDays);

        // A single run covering the entire lookback
        ReportSyncRunEntity run = ReportSyncRunEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .reportType("SP_CAMPAIGN")
                .requestedDateStart(lookbackStart)
                .requestedDateEnd(today)
                .reportStatus("completed")
                .dataStatus("finalized")
                .rowCount(lookbackDays * 10)
                .build();

        return List.of(run);
    }

    /**
     * Build a list of finalized report sync runs that have a gap in coverage.
     * Covers [lookbackStart, gapStart-1] and [gapEnd+1, today], missing the middle.
     */
    private List<ReportSyncRunEntity> buildIncompleteCoverage(UUID storeId, int lookbackDays, int gapStartOffset) {
        LocalDate today = LocalDate.now();
        LocalDate lookbackStart = today.minusDays(lookbackDays);
        LocalDate gapStart = lookbackStart.plusDays(gapStartOffset);

        // Only cover [lookbackStart, gapStart-1], leaving a gap from gapStart onward
        if (gapStartOffset <= 0) {
            return Collections.emptyList();
        }

        ReportSyncRunEntity run = ReportSyncRunEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .reportType("SP_CAMPAIGN")
                .requestedDateStart(lookbackStart)
                .requestedDateEnd(gapStart.minusDays(1))
                .reportStatus("completed")
                .dataStatus("finalized")
                .rowCount(gapStartOffset * 5)
                .build();

        return List.of(run);
    }

    // ── Property 1: Fresh data + complete coverage → passes ──────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 13: Data quality gate decision
     *
     * <p><b>Validates: Requirements 3.1, 3.2</b></p>
     *
     * <p>When the latest performance data is within the freshness window AND finalized
     * report coverage covers the entire lookback range, the gate passes.</p>
     */
    @Property(tries = 150)
    void freshDataWithCompleteCoveragePasses(
            @ForAll @IntRange(min = 24, max = 168) int freshnessHours,
            @ForAll @IntRange(min = 3, max = 30) int lookbackDays,
            @ForAll @IntRange(min = 0, max = 1) int daysAgoForLatest) {

        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();

        // Latest data is within the freshness window
        // freshnessHours / 24 = max allowed days old. daysAgoForLatest is 0 or 1,
        // always within a 24h+ window.
        LocalDate latestDate = LocalDate.now().minusDays(daysAgoForLatest);

        List<ReportSyncRunEntity> completeCoverage = buildCompleteCoverage(storeId, lookbackDays);

        DataQualityGateImpl gate = buildGate(storeId, campaignId, latestDate, completeCoverage, false, false);

        DataQualityResult result = gate.check(storeId, campaignId, freshnessHours, lookbackDays);

        assertThat(result.passed())
                .as("Fresh data within %dh window (latest %d days ago) + complete coverage should pass",
                        freshnessHours, daysAgoForLatest)
                .isTrue();
        assertThat(result.reason())
                .as("Passing result must have null reason")
                .isNull();
    }

    // ── Property 2: Stale data → fails with DATA_STALE ──────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 13: Data quality gate decision
     *
     * <p><b>Validates: Requirements 3.1, 3.2, 6.6, 25.5</b></p>
     *
     * <p>When the latest performance data is older than the freshness window,
     * the gate fails with reason DATA_STALE.</p>
     */
    @Property(tries = 150)
    void staleDataFailsWithDataStale(
            @ForAll @IntRange(min = 24, max = 168) int freshnessHours,
            @ForAll @IntRange(min = 3, max = 30) int lookbackDays,
            @ForAll @IntRange(min = 1, max = 60) int extraDaysBeyondWindow) {

        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();

        // The freshness threshold in days (integer division = number of full days allowed)
        int freshnessDays = (int) (freshnessHours / 24);

        // Make the latest data strictly older than the freshness window
        LocalDate latestDate = LocalDate.now().minusDays(freshnessDays + extraDaysBeyondWindow);

        // Even with complete coverage, stale data should fail
        List<ReportSyncRunEntity> completeCoverage = buildCompleteCoverage(storeId, lookbackDays);

        DataQualityGateImpl gate = buildGate(storeId, campaignId, latestDate, completeCoverage, false, false);

        DataQualityResult result = gate.check(storeId, campaignId, freshnessHours, lookbackDays);

        assertThat(result.passed())
                .as("Stale data (latest %s, window %dh = %d days, extra %d) must not pass",
                        latestDate, freshnessHours, freshnessDays, extraDaysBeyondWindow)
                .isFalse();
        assertThat(result.reason())
                .as("Stale data rejection must have reason DATA_STALE")
                .isEqualTo(DataQualityResult.DATA_STALE);
    }

    // ── Property 3: Missing/incomplete finalized coverage → DATA_INCOMPLETE ──────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 13: Data quality gate decision
     *
     * <p><b>Validates: Requirements 3.2, 30.2</b></p>
     *
     * <p>When fresh data exists but the finalized report coverage does not cover
     * the entire lookback range, the gate fails with reason DATA_INCOMPLETE.</p>
     */
    @Property(tries = 150)
    void incompleteCoverageFailsWithDataIncomplete(
            @ForAll @IntRange(min = 48, max = 168) int freshnessHours,
            @ForAll @IntRange(min = 3, max = 30) int lookbackDays,
            @ForAll @IntRange(min = 1, max = 20) int gapOffsetFromStart) {

        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();

        // Ensure the gap offset is within the lookback window
        int effectiveGapOffset = Math.min(gapOffsetFromStart, lookbackDays - 1);

        // Latest data is fresh (today)
        LocalDate latestDate = LocalDate.now();

        // Coverage has a gap starting at effectiveGapOffset days from lookback start
        List<ReportSyncRunEntity> incompleteCoverage =
                buildIncompleteCoverage(storeId, lookbackDays, effectiveGapOffset);

        DataQualityGateImpl gate = buildGate(storeId, campaignId, latestDate, incompleteCoverage, false, false);

        DataQualityResult result = gate.check(storeId, campaignId, freshnessHours, lookbackDays);

        assertThat(result.passed())
                .as("Incomplete coverage (gap at offset %d in %d-day lookback) must not pass",
                        effectiveGapOffset, lookbackDays)
                .isFalse();
        assertThat(result.reason())
                .as("Incomplete coverage rejection must have reason DATA_INCOMPLETE")
                .isEqualTo(DataQualityResult.DATA_INCOMPLETE);
    }

    // ── Property 4: DB error / missing coverage → fail-closed (never passes) ────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 13: Data quality gate decision
     *
     * <p><b>Validates: Requirements 3.5</b></p>
     *
     * <p>When a database error occurs during the freshness check, the gate operates
     * fail-closed: it never returns a passing result on uncertain data.</p>
     */
    @Property(tries = 120)
    void dbErrorOnFreshnessCheckFailsClosed(
            @ForAll @IntRange(min = 24, max = 168) int freshnessHours,
            @ForAll @IntRange(min = 3, max = 30) int lookbackDays) {

        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();

        // DB throws on freshness check
        DataQualityGateImpl gate = buildGate(storeId, campaignId, null, null, true, false);

        DataQualityResult result = gate.check(storeId, campaignId, freshnessHours, lookbackDays);

        assertThat(result.passed())
                .as("DB error on freshness check must fail-closed (never pass)")
                .isFalse();
        // Reason should be DATA_STALE (the fail-closed path returns stale())
        assertThat(result.reason())
                .as("Fail-closed reason must not be null")
                .isNotNull();
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 13: Data quality gate decision
     *
     * <p><b>Validates: Requirements 3.5</b></p>
     *
     * <p>When a database error occurs during the completeness check (after freshness
     * passes), the gate operates fail-closed: it never returns a passing result.</p>
     */
    @Property(tries = 120)
    void dbErrorOnCompletenessCheckFailsClosed(
            @ForAll @IntRange(min = 48, max = 168) int freshnessHours,
            @ForAll @IntRange(min = 3, max = 30) int lookbackDays) {

        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();

        // Fresh data is available (today) so freshness passes, but completeness DB throws
        LocalDate latestDate = LocalDate.now();
        DataQualityGateImpl gate = buildGate(storeId, campaignId, latestDate, null, false, true);

        DataQualityResult result = gate.check(storeId, campaignId, freshnessHours, lookbackDays);

        assertThat(result.passed())
                .as("DB error on completeness check must fail-closed (never pass)")
                .isFalse();
        assertThat(result.reason())
                .as("Fail-closed reason must not be null")
                .isNotNull();
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 13: Data quality gate decision
     *
     * <p><b>Validates: Requirements 3.5</b></p>
     *
     * <p>When no performance data exists at all (empty result), the gate fails-closed
     * with DATA_STALE. Missing data is never treated as "pass".</p>
     */
    @Property(tries = 120)
    void noDataAtAllFailsClosedWithDataStale(
            @ForAll @IntRange(min = 24, max = 168) int freshnessHours,
            @ForAll @IntRange(min = 3, max = 30) int lookbackDays) {

        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();

        // No performance data returned (null latestPerformanceDate = empty list)
        DataQualityGateImpl gate = buildGate(storeId, campaignId, null, Collections.emptyList(), false, false);

        DataQualityResult result = gate.check(storeId, campaignId, freshnessHours, lookbackDays);

        assertThat(result.passed())
                .as("No data at all must fail-closed (never pass)")
                .isFalse();
        assertThat(result.reason())
                .as("No data rejection must be DATA_STALE")
                .isEqualTo(DataQualityResult.DATA_STALE);
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 13: Data quality gate decision
     *
     * <p><b>Validates: Requirements 3.5</b></p>
     *
     * <p>When no finalized coverage runs exist at all (empty sync runs), the gate
     * fails-closed with DATA_INCOMPLETE. Missing coverage info is never treated as "pass".</p>
     */
    @Property(tries = 120)
    void noCoverageRunsFailsClosedWithDataIncomplete(
            @ForAll @IntRange(min = 48, max = 168) int freshnessHours,
            @ForAll @IntRange(min = 3, max = 30) int lookbackDays) {

        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();

        // Fresh data is available (today)
        LocalDate latestDate = LocalDate.now();

        // No sync runs at all (empty list)
        DataQualityGateImpl gate = buildGate(storeId, campaignId, latestDate, Collections.emptyList(), false, false);

        DataQualityResult result = gate.check(storeId, campaignId, freshnessHours, lookbackDays);

        assertThat(result.passed())
                .as("No coverage runs must fail-closed (never pass)")
                .isFalse();
        assertThat(result.reason())
                .as("No coverage runs rejection must be DATA_INCOMPLETE")
                .isEqualTo(DataQualityResult.DATA_INCOMPLETE);
    }

    // ── Property 5: Result is always one of the valid outcomes ───────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 13: Data quality gate decision
     *
     * <p><b>Validates: Requirements 3.1, 3.2, 3.5, 6.6, 25.5, 30.2</b></p>
     *
     * <p>Regardless of input conditions, the result is always one of:
     * pass (reason=null), DATA_STALE, or DATA_INCOMPLETE. No other outcomes are possible.</p>
     */
    @Property(tries = 150)
    void resultIsAlwaysAValidOutcome(
            @ForAll("gateScenarios") GateScenario scenario) {

        DataQualityGateImpl gate = buildGate(
                scenario.storeId, scenario.campaignId,
                scenario.latestDate, scenario.syncRuns,
                scenario.throwOnFreshness, scenario.throwOnCompleteness);

        DataQualityResult result = gate.check(
                scenario.storeId, scenario.campaignId,
                scenario.freshnessHours, scenario.lookbackDays);

        if (result.passed()) {
            assertThat(result.reason())
                    .as("Passing result must have null reason")
                    .isNull();
        } else {
            assertThat(result.reason())
                    .as("Failing result must have a non-null reason that is DATA_STALE or DATA_INCOMPLETE")
                    .isNotNull()
                    .isIn(DataQualityResult.DATA_STALE, DataQualityResult.DATA_INCOMPLETE);
        }
    }

    // ── Data holders ─────────────────────────────────────────────────────────────

    record GateScenario(
            UUID storeId,
            UUID campaignId,
            LocalDate latestDate,
            List<ReportSyncRunEntity> syncRuns,
            boolean throwOnFreshness,
            boolean throwOnCompleteness,
            long freshnessHours,
            int lookbackDays
    ) {}

    // ── Generators ───────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<GateScenario> gateScenarios() {
        Arbitrary<UUID> storeIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<UUID> campaignIds = Arbitraries.create(UUID::randomUUID);
        Arbitrary<Long> freshnessHrs = Arbitraries.longs().between(24, 168);
        Arbitrary<Integer> lookbackDs = Arbitraries.integers().between(3, 30);
        Arbitrary<Boolean> throwFreshness = Arbitraries.of(true, false, false, false);
        Arbitrary<Boolean> throwCompleteness = Arbitraries.of(true, false, false, false);

        // Generate latestDate: null (no data), stale, or fresh
        Arbitrary<LocalDate> latestDates = Arbitraries.oneOf(
                Arbitraries.just(null),
                // Fresh: 0–1 days ago
                Arbitraries.integers().between(0, 1).map(d -> LocalDate.now().minusDays(d)),
                // Stale: 5–60 days ago
                Arbitraries.integers().between(5, 60).map(d -> LocalDate.now().minusDays(d))
        );

        return Combinators.combine(storeIds, campaignIds, latestDates, freshnessHrs, lookbackDs, throwFreshness, throwCompleteness)
                .as((storeId, campaignId, latestDate, freshHours, lookback, throwF, throwC) -> {
                    // Build sync runs: either complete, incomplete, or empty
                    List<ReportSyncRunEntity> syncRuns;
                    int choice = new Random().nextInt(3);
                    if (choice == 0) {
                        syncRuns = buildCompleteCoverage(storeId, lookback);
                    } else if (choice == 1) {
                        int gapOffset = Math.max(1, lookback / 2);
                        syncRuns = buildIncompleteCoverage(storeId, lookback, gapOffset);
                    } else {
                        syncRuns = Collections.emptyList();
                    }

                    return new GateScenario(storeId, campaignId, latestDate, syncRuns,
                            throwF, throwC, freshHours, lookback);
                });
    }
}
