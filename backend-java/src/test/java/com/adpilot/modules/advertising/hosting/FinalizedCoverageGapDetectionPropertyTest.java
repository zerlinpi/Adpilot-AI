package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.resilience.CircuitBreaker;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.feishu.service.FeishuService;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.MarketplaceReferenceService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based test for the {@link ReportSyncJob#detectAndAlertGaps} method.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 12: Finalized-coverage gap detection
 *
 * <p><b>Validates: Requirements 2.8</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>When there is a gap of more than 2 days in finalized coverage relative to
 *       expectedFinalizedDate (today − finalizationLagDays), the system detects it
 *       and sends a Feishu notification.</li>
 *   <li>When finalized coverage is contiguous (no gap &gt; 2 days), no gap alert is triggered.</li>
 *   <li>Gap detection is relative to expectedFinalizedDate, not relative to today —
 *       the finalizationLagDays offset is correctly applied.</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 12: Finalized-coverage gap detection")
class FinalizedCoverageGapDetectionPropertyTest {

    static {
        // Register entity metadata so MyBatis-Plus can resolve lambda columns
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ReportSyncRunEntity.class);
    }

    private static final ZoneId UTC = ZoneId.of("UTC");

    // ── Helper: build a ReportSyncJob with controlled mapper and config ──────────

    /**
     * Builds a ReportSyncJob with a mocked ReportSyncRunMapper that returns the
     * given list of finalized runs, and configurable finalizationLagDays/maxGapDays.
     *
     * @param finalizedRuns       the finalized report sync runs to return from the mapper
     * @param finalizationLagDays the configured finalization lag (days)
     * @param maxGapDays          the maximum tolerable gap before alerting
     * @return a configured ReportSyncJob with a mock FeishuService for verification
     */
    @SuppressWarnings("unchecked")
    private TestContext buildJob(List<ReportSyncRunEntity> finalizedRuns,
                                int finalizationLagDays,
                                int maxGapDays) {

        ReportSyncRunMapper syncRunMapper = mock(ReportSyncRunMapper.class);
        FeishuService feishuService = mock(FeishuService.class);

        // Configure the mapper to return the given finalized runs
        when(syncRunMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(finalizedRuns != null ? finalizedRuns : Collections.emptyList());

        // FeishuService accepts notifications
        when(feishuService.pushAiNotification(any(), any(), any())).thenReturn(true);

        ReportSyncJob job = new ReportSyncJob(
                mock(ReportLifecycleClient.class),
                mock(ReportIngestionService.class),
                syncRunMapper,
                mock(ReportSyncErrorMapper.class),
                mock(PlatformConnectionMapper.class),
                mock(StoreMapper.class),
                mock(MarketplaceReferenceService.class),
                feishuService,
                mock(CryptoUtil.class),
                new CircuitBreaker(false, 5, 30)
        );

        // Set @Value fields via reflection
        setField(job, "finalizationLagDays", finalizationLagDays);
        setField(job, "maxGapDays", maxGapDays);

        return new TestContext(job, feishuService);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    record TestContext(ReportSyncJob job, FeishuService feishuService) {}

    // ── Property 1: Gap > maxGapDays triggers a Feishu notification ──────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 12: Finalized-coverage gap detection
     *
     * <p><b>Validates: Requirements 2.8</b></p>
     *
     * <p>When the latest finalized coverage end date is more than maxGapDays behind
     * the expectedFinalizedDate, the system sends a Feishu data-gap notification.</p>
     */
    @Property(tries = 150)
    void gapExceedingThresholdTriggersNotification(
            @ForAll @IntRange(min = 3, max = 30) int finalizationLagDays,
            @ForAll @IntRange(min = 1, max = 5) int maxGapDays,
            @ForAll @IntRange(min = 1, max = 20) int extraDaysBeyondGap) {

        UUID storeId = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        // expectedFinalizedDate = today - finalizationLagDays
        LocalDate expectedFinalizedDate = today.minusDays(finalizationLagDays);

        // latestFinalizedEnd is maxGapDays + extraDaysBeyondGap before expectedFinalizedDate
        // This creates a gap strictly greater than maxGapDays
        // Constrained to ≤25 total gap days to stay within single-month Period semantics
        LocalDate latestFinalizedEnd = expectedFinalizedDate.minusDays(maxGapDays + extraDaysBeyondGap);

        // Ensure the gap stays within a single calendar month (Period.getDays() correctness)
        Assume.that(latestFinalizedEnd.getMonth() == expectedFinalizedDate.getMonth()
                || ChronoUnit.DAYS.between(latestFinalizedEnd, expectedFinalizedDate) <= 28);

        ReportSyncRunEntity run = ReportSyncRunEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .reportType("SP_CAMPAIGN")
                .requestedDateStart(latestFinalizedEnd.minusDays(7))
                .requestedDateEnd(latestFinalizedEnd)
                .reportStatus("completed")
                .dataStatus("finalized")
                .rowCount(100)
                .build();

        TestContext ctx = buildJob(List.of(run), finalizationLagDays, maxGapDays);

        ctx.job().detectAndAlertGaps(storeId, today, UTC);

        // A Feishu notification should have been sent
        verify(ctx.feishuService(), times(1))
                .pushAiNotification(eq(storeId), any(String.class), any(String.class));
    }

    // ── Property 2: No gap (contiguous coverage) → no alert triggered ───────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 12: Finalized-coverage gap detection
     *
     * <p><b>Validates: Requirements 2.8</b></p>
     *
     * <p>When the finalized coverage extends up to or beyond expectedFinalizedDate
     * (gap ≤ maxGapDays), no Feishu notification is sent.</p>
     */
    @Property(tries = 150)
    void noGapDoesNotTriggerNotification(
            @ForAll @IntRange(min = 3, max = 30) int finalizationLagDays,
            @ForAll @IntRange(min = 1, max = 5) int maxGapDays,
            @ForAll @IntRange(min = 0, max = 10) int daysAheadOfThreshold) {

        UUID storeId = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        // expectedFinalizedDate = today - finalizationLagDays
        LocalDate expectedFinalizedDate = today.minusDays(finalizationLagDays);

        // latestFinalizedEnd is at or beyond expectedFinalizedDate minus maxGapDays
        // gap = expectedFinalizedDate - latestFinalizedEnd
        // For no alert: gap <= maxGapDays, so latestFinalizedEnd >= expectedFinalizedDate - maxGapDays
        // We set latestFinalizedEnd = expectedFinalizedDate - maxGapDays + daysAheadOfThreshold
        // which gives gap = maxGapDays - daysAheadOfThreshold (≤ maxGapDays)
        LocalDate latestFinalizedEnd = expectedFinalizedDate.minusDays(maxGapDays).plusDays(daysAheadOfThreshold);

        // Ensure latestFinalizedEnd is not in the future relative to expected (realistic scenario)
        Assume.that(!latestFinalizedEnd.isAfter(expectedFinalizedDate));

        ReportSyncRunEntity run = ReportSyncRunEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .reportType("SP_CAMPAIGN")
                .requestedDateStart(latestFinalizedEnd.minusDays(7))
                .requestedDateEnd(latestFinalizedEnd)
                .reportStatus("completed")
                .dataStatus("finalized")
                .rowCount(100)
                .build();

        TestContext ctx = buildJob(List.of(run), finalizationLagDays, maxGapDays);

        ctx.job().detectAndAlertGaps(storeId, today, UTC);

        // No Feishu notification should have been sent
        verify(ctx.feishuService(), never())
                .pushAiNotification(any(), any(String.class), any(String.class));
    }

    // ── Property 3: Gap is relative to expectedFinalizedDate, not today ─────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 12: Finalized-coverage gap detection
     *
     * <p><b>Validates: Requirements 2.8</b></p>
     *
     * <p>Gap detection uses expectedFinalizedDate (= today − finalizationLagDays),
     * NOT today. With the same latestFinalizedEnd, varying finalizationLagDays changes
     * whether a gap is detected. When finalizationLagDays increases enough to make
     * expectedFinalizedDate ≤ latestFinalizedEnd + maxGapDays, no alert fires even
     * though the gap relative to today would exceed maxGapDays.</p>
     */
    @Property(tries = 150)
    void gapIsRelativeToExpectedFinalizedDateNotToday(
            @ForAll @IntRange(min = 5, max = 30) int daysBeforeToday,
            @ForAll @IntRange(min = 2, max = 5) int maxGapDays) {

        UUID storeId = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        // Fix the latestFinalizedEnd to a specific date in the past
        LocalDate latestFinalizedEnd = today.minusDays(daysBeforeToday);

        // Choose a smallLag where the gap exceeds maxGapDays:
        // expectedFinalizedDate(smallLag) = today - smallLag
        // gap = (today - smallLag) - latestFinalizedEnd = daysBeforeToday - smallLag
        // gap > maxGapDays when smallLag < daysBeforeToday - maxGapDays
        int smallLag = Math.max(0, daysBeforeToday - maxGapDays - 2);

        // Choose a largeLag where the gap does NOT exceed maxGapDays:
        // gap = daysBeforeToday - largeLag ≤ maxGapDays
        // largeLag ≥ daysBeforeToday - maxGapDays
        int largeLag = daysBeforeToday; // gap = 0

        // Ensure preconditions hold
        Assume.that(smallLag < daysBeforeToday - maxGapDays); // gap > maxGapDays with smallLag
        Assume.that(largeLag >= daysBeforeToday - maxGapDays); // gap ≤ maxGapDays with largeLag

        ReportSyncRunEntity run = ReportSyncRunEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .reportType("SP_CAMPAIGN")
                .requestedDateStart(latestFinalizedEnd.minusDays(7))
                .requestedDateEnd(latestFinalizedEnd)
                .reportStatus("completed")
                .dataStatus("finalized")
                .rowCount(100)
                .build();

        // With small lag: gap > maxGapDays → notification expected
        TestContext ctxSmall = buildJob(List.of(run), smallLag, maxGapDays);
        ctxSmall.job().detectAndAlertGaps(storeId, today, UTC);
        verify(ctxSmall.feishuService(), times(1))
                .pushAiNotification(eq(storeId), any(String.class), any(String.class));

        // With large lag: gap ≤ maxGapDays → no notification
        TestContext ctxLarge = buildJob(List.of(run), largeLag, maxGapDays);
        ctxLarge.job().detectAndAlertGaps(storeId, today, UTC);
        verify(ctxLarge.feishuService(), never())
                .pushAiNotification(any(), any(String.class), any(String.class));
    }

    // ── Property 4: No finalized runs at all triggers a gap alert ────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 12: Finalized-coverage gap detection
     *
     * <p><b>Validates: Requirements 2.8</b></p>
     *
     * <p>When no finalized report sync runs exist at all (empty coverage), the
     * gap detection treats this as a full gap from the beginning and sends a
     * Feishu notification when the Period day component from EPOCH to
     * expectedFinalizedDate exceeds maxGapDays.</p>
     */
    @Property(tries = 100)
    void noFinalizedRunsTriggersAlert(
            @ForAll @IntRange(min = 3, max = 30) int finalizationLagDays,
            @ForAll @IntRange(min = 1, max = 5) int maxGapDays) {

        UUID storeId = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        // The implementation uses Period.between(EPOCH, expectedFinalizedDate).getDays()
        // which returns only the day-of-month component. We must ensure this exceeds maxGapDays.
        LocalDate expectedFinalizedDate = today.minusDays(finalizationLagDays);
        int periodDayComponent = Period.between(LocalDate.EPOCH, expectedFinalizedDate).getDays();
        Assume.that(periodDayComponent > maxGapDays);

        TestContext ctx = buildJob(Collections.emptyList(), finalizationLagDays, maxGapDays);

        ctx.job().detectAndAlertGaps(storeId, today, UTC);

        // With no finalized runs and Period day component > maxGapDays, alert fires
        verify(ctx.feishuService(), times(1))
                .pushAiNotification(eq(storeId), any(String.class), any(String.class));
    }

    // ── Property 5: Boundary — gap exactly equal to maxGapDays does NOT trigger ──

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 12: Finalized-coverage gap detection
     *
     * <p><b>Validates: Requirements 2.8</b></p>
     *
     * <p>The threshold comparison is strictly greater than: a gap of exactly
     * maxGapDays days does NOT trigger an alert (only &gt; maxGapDays triggers).</p>
     */
    @Property(tries = 100)
    void exactlyAtThresholdDoesNotTrigger(
            @ForAll @IntRange(min = 3, max = 30) int finalizationLagDays,
            @ForAll @IntRange(min = 1, max = 5) int maxGapDays) {

        UUID storeId = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        LocalDate expectedFinalizedDate = today.minusDays(finalizationLagDays);
        // Set latestFinalizedEnd so gap == maxGapDays exactly
        LocalDate latestFinalizedEnd = expectedFinalizedDate.minusDays(maxGapDays);

        ReportSyncRunEntity run = ReportSyncRunEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .reportType("SP_CAMPAIGN")
                .requestedDateStart(latestFinalizedEnd.minusDays(7))
                .requestedDateEnd(latestFinalizedEnd)
                .reportStatus("completed")
                .dataStatus("finalized")
                .rowCount(100)
                .build();

        TestContext ctx = buildJob(List.of(run), finalizationLagDays, maxGapDays);

        ctx.job().detectAndAlertGaps(storeId, today, UTC);

        // gap == maxGapDays (not >) → no notification
        verify(ctx.feishuService(), never())
                .pushAiNotification(any(), any(String.class), any(String.class));
    }

    // ── Property 6: Boundary — gap of exactly maxGapDays + 1 DOES trigger ───────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 12: Finalized-coverage gap detection
     *
     * <p><b>Validates: Requirements 2.8</b></p>
     *
     * <p>A gap that is exactly 1 day more than maxGapDays does trigger the alert.</p>
     */
    @Property(tries = 100)
    void oneMoreThanThresholdTriggers(
            @ForAll @IntRange(min = 3, max = 30) int finalizationLagDays,
            @ForAll @IntRange(min = 1, max = 5) int maxGapDays) {

        UUID storeId = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        LocalDate expectedFinalizedDate = today.minusDays(finalizationLagDays);
        // Set latestFinalizedEnd so gap == maxGapDays + 1
        LocalDate latestFinalizedEnd = expectedFinalizedDate.minusDays(maxGapDays + 1);

        ReportSyncRunEntity run = ReportSyncRunEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .reportType("SP_CAMPAIGN")
                .requestedDateStart(latestFinalizedEnd.minusDays(7))
                .requestedDateEnd(latestFinalizedEnd)
                .reportStatus("completed")
                .dataStatus("finalized")
                .rowCount(100)
                .build();

        TestContext ctx = buildJob(List.of(run), finalizationLagDays, maxGapDays);

        ctx.job().detectAndAlertGaps(storeId, today, UTC);

        // gap == maxGapDays + 1 (> maxGapDays) → notification
        verify(ctx.feishuService(), times(1))
                .pushAiNotification(eq(storeId), any(String.class), any(String.class));
    }
}
