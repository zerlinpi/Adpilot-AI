package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Feature: amazon-ads-ai-hosting-system, Property 37: Observed change and honest
 * incremental impact.
 *
 * <p><b>Validates: Requirements 8.2</b></p>
 *
 * <p>Requirement 8.2 demands that, for each tracked metric, the {@link AttributionWorker}
 * compute the {@code observed_change} as a raw before/after delta, and — when no reliable
 * baseline/control is available — set {@code estimated_incremental_impact} to {@code null}
 * rather than presenting an unjustified incremental figure. Every attribution row also
 * carries an {@code attribution_confidence} in {@code [0.0, 1.0]}.</p>
 *
 * <p>This property exercises {@link AttributionWorker#processAttribution} over arbitrary
 * pre-/post-window performance data and verifies, across all six tracked metrics:</p>
 * <ol>
 *   <li><b>Observed change</b> — {@code observed_change} equals
 *       {@code (post-window metric average) − (pre-window metric average)}.</li>
 *   <li><b>Honest admission</b> — {@code estimated_incremental_impact} is always
 *       {@code null} (the worker has no reliable baseline/control).</li>
 *   <li><b>Confidence bounds</b> — {@code attribution_confidence} is always within
 *       {@code [0.0, 1.0]}.</li>
 *   <li><b>Determinism</b> — the same inputs always produce the same attribution rows.</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 37: Observed change and honest incremental impact")
class ObservedChangeAndHonestIncrementalImpactPropertyTest {

    private static final int WINDOW_DAYS = 7;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 37: Observed change and honest
     * incremental impact.
     *
     * <p><b>Validates: Requirements 8.2</b></p>
     */
    @Property(tries = 200)
    void observedChangeIsBeforeAfterDeltaWithHonestIncrementalImpact(
            @ForAll("scenario") AttributionScenario scenario) {

        List<EffectAttributionEntity> rows = runProcessAttribution(scenario);

        // One row per tracked metric (Req 8.2 covers each metric).
        assertThat(rows)
                .as("one attribution row per tracked metric")
                .hasSize(AttributionWorker.TRACKED_METRICS.size());
        assertThat(rows.stream().map(EffectAttributionEntity::getMetricType).toList())
                .as("rows cover exactly the tracked metrics")
                .containsExactlyInAnyOrderElementsOf(AttributionWorker.TRACKED_METRICS);

        for (EffectAttributionEntity row : rows) {
            String metric = row.getMetricType();

            // (1) observed_change == post-window avg − pre-window avg
            BigDecimal expectedPreAvg = average(scenario.preRows(), metric);
            BigDecimal expectedPostAvg = average(scenario.postRows(), metric);
            BigDecimal expectedObservedChange = expectedPostAvg.subtract(expectedPreAvg);
            assertThat(row.getObservedChange())
                    .as("observed_change for %s equals post avg − pre avg", metric)
                    .isEqualByComparingTo(expectedObservedChange);

            // (2) estimated_incremental_impact is always null (honest admission, Req 8.2)
            assertThat(row.getEstimatedIncrementalImpact())
                    .as("estimated_incremental_impact is null for %s (no reliable baseline)", metric)
                    .isNull();

            // (3) attribution_confidence in [0.0, 1.0]
            assertThat(row.getAttributionConfidence())
                    .as("attribution_confidence for %s is within [0.0, 1.0]", metric)
                    .isNotNull();
            assertThat(row.getAttributionConfidence().compareTo(ZERO) >= 0
                    && row.getAttributionConfidence().compareTo(ONE) <= 0)
                    .as("attribution_confidence %s for %s within [0,1]",
                            row.getAttributionConfidence(), metric)
                    .isTrue();
        }
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 37: Observed change and honest
     * incremental impact.
     *
     * <p><b>Validates: Requirements 8.2</b></p>
     *
     * <p>The attribution computation is deterministic: identical inputs yield identical
     * attribution rows (same observed_change, null incremental impact, and confidence).</p>
     */
    @Property(tries = 100)
    void attributionComputationIsDeterministic(
            @ForAll("scenario") AttributionScenario scenario) {

        List<EffectAttributionEntity> first = sortedByMetric(runProcessAttribution(scenario));
        List<EffectAttributionEntity> second = sortedByMetric(runProcessAttribution(scenario));

        assertThat(first).hasSameSizeAs(second);
        for (int i = 0; i < first.size(); i++) {
            EffectAttributionEntity a = first.get(i);
            EffectAttributionEntity b = second.get(i);
            assertThat(a.getMetricType()).isEqualTo(b.getMetricType());
            assertThat(a.getObservedChange())
                    .as("deterministic observed_change for %s", a.getMetricType())
                    .isEqualByComparingTo(b.getObservedChange());
            assertThat(a.getEstimatedIncrementalImpact())
                    .as("deterministic (null) incremental impact for %s", a.getMetricType())
                    .isNull();
            assertThat(b.getEstimatedIncrementalImpact()).isNull();
            assertThat(a.getAttributionConfidence())
                    .as("deterministic confidence for %s", a.getMetricType())
                    .isEqualByComparingTo(b.getAttributionConfidence());
        }
    }

    // ── Test harness ──────────────────────────────────────────────────────────────

    /**
     * Runs {@link AttributionWorker#processAttribution} against mocked mappers backed by
     * the scenario's pre-/post-window data and captures the persisted attribution rows.
     * No overlapping operations are returned, so confidence depends only on data quality.
     */
    @SuppressWarnings("unchecked")
    private List<EffectAttributionEntity> runProcessAttribution(AttributionScenario scenario) {
        OperationMapper operationMapper = mock(OperationMapper.class);
        PerformanceDailyMapper performanceDailyMapper = mock(PerformanceDailyMapper.class);
        EffectAttributionMapper effectAttributionMapper = mock(EffectAttributionMapper.class);
        CampaignMapper campaignMapper = mock(CampaignMapper.class);
        KeywordMapper keywordMapper = mock(KeywordMapper.class);

        // No overlapping operations on the same campaign.
        when(operationMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        // processAttribution fetches pre-window data first, then post-window data.
        when(performanceDailyMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(new ArrayList<>(scenario.preRows()))
                .thenReturn(new ArrayList<>(scenario.postRows()));

        when(effectAttributionMapper.insert(any())).thenReturn(1);

        AttributionWorker worker = new AttributionWorker(
                operationMapper, performanceDailyMapper, effectAttributionMapper,
                campaignMapper, keywordMapper, WINDOW_DAYS);

        OperationEntity op = OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(scenario.storeId())
                .operationSource("ai_hosting")
                .operationScope("platform_mutation")
                .entityType("campaign")
                .entityId(scenario.campaignId())
                .syncState("effective")
                .updatedAt(LocalDateTime.now().minusDays(WINDOW_DAYS + 1L))
                .build();

        worker.processAttribution(op);

        ArgumentCaptor<EffectAttributionEntity> captor =
                ArgumentCaptor.forClass(EffectAttributionEntity.class);
        verify(effectAttributionMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }

    private static List<EffectAttributionEntity> sortedByMetric(List<EffectAttributionEntity> rows) {
        List<EffectAttributionEntity> copy = new ArrayList<>(rows);
        copy.sort((a, b) -> a.getMetricType().compareTo(b.getMetricType()));
        return copy;
    }

    /**
     * Independent reference implementation of the metric average: the arithmetic mean of
     * the metric value across the rows, rounded to scale 6 (HALF_UP), or ZERO when empty.
     * Deliberately re-derives the value rather than calling the worker, so the property
     * verifies the worker's computation rather than tautologically restating it.
     */
    private static BigDecimal average(List<PerformanceDailyEntity> rows, String metric) {
        if (rows == null || rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (PerformanceDailyEntity row : rows) {
            sum = sum.add(metricValue(row, metric));
        }
        return sum.divide(BigDecimal.valueOf(rows.size()), 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal metricValue(PerformanceDailyEntity row, String metric) {
        return switch (metric) {
            case "impressions" -> BigDecimal.valueOf(row.getImpressions());
            case "clicks" -> BigDecimal.valueOf(row.getClicks());
            case "spend" -> row.getSpend();
            case "sales" -> row.getSales();
            case "orders" -> BigDecimal.valueOf(row.getOrders());
            case "acos" -> row.getAcos();
            default -> BigDecimal.ZERO;
        };
    }

    // ── Generators ──────────────────────────────────────────────────────────────

    record AttributionScenario(
            UUID storeId,
            UUID campaignId,
            List<PerformanceDailyEntity> preRows,
            List<PerformanceDailyEntity> postRows) {
    }

    @Provide
    Arbitrary<AttributionScenario> scenario() {
        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        // 0..14 rows each window; includes empty windows (zero-average edge cases) and
        // windows both below and above the measurement window size (affects confidence).
        Arbitrary<List<PerformanceDailyEntity>> preRows = performanceRow(storeId, campaignId).list().ofMaxSize(14);
        Arbitrary<List<PerformanceDailyEntity>> postRows = performanceRow(storeId, campaignId).list().ofMaxSize(14);
        return Combinators.combine(preRows, postRows)
                .as((pre, post) -> new AttributionScenario(storeId, campaignId, pre, post));
    }

    private Arbitrary<PerformanceDailyEntity> performanceRow(UUID storeId, UUID campaignId) {
        Arbitrary<Long> impressions = Arbitraries.longs().between(0L, 1_000_000L);
        Arbitrary<Integer> clicks = Arbitraries.integers().between(0, 100_000);
        Arbitrary<Integer> orders = Arbitraries.integers().between(0, 10_000);
        Arbitrary<BigDecimal> spend = money(0, 100_000);
        Arbitrary<BigDecimal> sales = money(0, 1_000_000);
        Arbitrary<BigDecimal> acos = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, BigDecimal.valueOf(5)).ofScale(4);

        return Combinators.combine(impressions, clicks, orders, spend, sales, acos)
                .as((imp, clk, ord, sp, sl, ac) -> PerformanceDailyEntity.builder()
                        .id(UUID.randomUUID())
                        .storeId(storeId)
                        .campaignId(campaignId)
                        .entityType("campaign")
                        .entityId(campaignId)
                        .impressions(imp)
                        .clicks(clk)
                        .orders(ord)
                        .spend(sp)
                        .sales(sl)
                        .acos(ac)
                        .dataStatus("finalized")
                        .build());
    }

    private Arbitrary<BigDecimal> money(int min, int max) {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.valueOf(min), BigDecimal.valueOf(max))
                .ofScale(2);
    }
}
