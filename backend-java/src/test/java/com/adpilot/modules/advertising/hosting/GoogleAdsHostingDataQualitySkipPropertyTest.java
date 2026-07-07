package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.apisync.service.GoogleAdsReadService;
import com.adpilot.modules.apisync.vo.GoogleAdsCampaignVo;
import com.adpilot.modules.apisync.vo.GoogleAdsReadResult;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the Google Ads AI-hosting data-quality skip rule.
 *
 * <p>Feature: platform-workspace-rbac, Property 17: AI hosting skips on data-quality failure.
 *
 * <p>Validates: Requirements 8.8.
 *
 * <p>For any Google Ads performance dataset that fails the data-quality freshness or completeness
 * check, the {@link GoogleAdsHostingService} (wiring the GoogleAds_Hosting_Engine) produces no
 * candidate for the affected campaign and records a skip reason. A campaign fails the check when it
 * shows no measurable activity (no impressions and no clicks) over the lookback window, leaving the
 * engine no signal to optimize on. This property asserts that across many such datasets:
 * <ul>
 *   <li>the hosting engine is never asked to produce candidates for a failing campaign;</li>
 *   <li>no Operation is created (the coordinator and Operation pipeline are never invoked); and</li>
 *   <li>every failing campaign is counted as skipped with the recorded
 *       {@code DATA_QUALITY_INSUFFICIENT} reason.</li>
 * </ul>
 */
class GoogleAdsHostingDataQualitySkipPropertyTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final String SKIP_REASON = "DATA_QUALITY_INSUFFICIENT";

    /**
     * Feature: platform-workspace-rbac, Property 17: AI hosting skips on data-quality failure.
     *
     * <p>Validates: Requirements 8.8.
     */
    @Property(tries = 200)
    void skipsEveryDataQualityFailureWithoutProducingCandidatesOrOperations(
            @ForAll("dataQualityFailingCampaigns") List<GoogleAdsCampaignVo> campaigns) {

        GoogleAdsReadService readService = Mockito.mock(GoogleAdsReadService.class);
        GoogleAdsHostingEngine engine = Mockito.mock(GoogleAdsHostingEngine.class);
        OptimizationCoordinator coordinator = Mockito.mock(OptimizationCoordinator.class);
        ExecutionModeResolver executionModeResolver = Mockito.mock(ExecutionModeResolver.class);
        OperationService operationService = Mockito.mock(OperationService.class);

        GoogleAdsHostingService service = new GoogleAdsHostingService(
                readService, engine, coordinator, executionModeResolver, operationService);

        ReflectionTestUtils.setField(service, "defaultTargetAcos", new BigDecimal("0.30"));
        ReflectionTestUtils.setField(service, "lookbackDays", 14);
        ReflectionTestUtils.setField(service, "minDailyBudget", new BigDecimal("1.00"));
        ReflectionTestUtils.setField(service, "maxDailyBudget", new BigDecimal("100000.00"));
        ReflectionTestUtils.setField(service, "maxBudgetIncreaseRatio", new BigDecimal("0.20"));
        ReflectionTestUtils.setField(service, "maxBudgetDecreaseRatio", new BigDecimal("0.30"));

        when(readService.getCampaigns(STORE_ID))
                .thenReturn(GoogleAdsReadResult.ok(campaigns));

        GoogleAdsHostingService.HostingRunSummary summary = service.optimizeStore(STORE_ID);

        // Every failing campaign is skipped with the recorded reason and none produce a candidate.
        assertThat(summary.getProcessed()).isEqualTo(campaigns.size());
        assertThat(summary.getSkipped()).isEqualTo(campaigns.size());
        assertThat(summary.getFailed()).isZero();
        assertThat(summary.getOperationsCreated()).isZero();
        assertThat(summary.getSkips()).hasSize(campaigns.size());
        assertThat(summary.getSkips())
                .allSatisfy(skip -> assertThat(skip.reason()).isEqualTo(SKIP_REASON));

        // The engine is never asked to produce candidates and the Operation pipeline is never invoked.
        verify(engine, never()).produceCandidates(any(), any(), any(), any(), any());
        verify(coordinator, never()).coordinate(
                any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
        verify(operationService, never()).createOperation(any());
    }

    // --- generators --------------------------------------------------------

    /**
     * Campaigns that fail the data-quality check: each has zero impressions and zero clicks (the
     * freshness/completeness failure condition), while other fields vary freely so the property
     * exercises the rule independent of budget, cost, status, or conversion value.
     */
    @Provide
    Arbitrary<List<GoogleAdsCampaignVo>> dataQualityFailingCampaigns() {
        return failingCampaign().list().ofMinSize(1).ofMaxSize(8);
    }

    private Arbitrary<GoogleAdsCampaignVo> failingCampaign() {
        Arbitrary<String> ids = Arbitraries.strings()
                .withCharRange('a', 'z').numeric()
                .ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> statuses = Arbitraries.of("ENABLED", "PAUSED", "REMOVED");
        Arbitrary<BigDecimal> budgets = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("100000"))
                .ofScale(2);
        Arbitrary<BigDecimal> costs = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("100000"))
                .ofScale(2);
        Arbitrary<Double> conversions = Arbitraries.doubles().between(0d, 1000d);

        return Combinators.combine(ids, statuses, budgets, costs, conversions)
                .as((id, status, budget, cost, conv) -> GoogleAdsCampaignVo.builder()
                        .campaignId(id)
                        .name(id)
                        .status(status)
                        .budget(budget)
                        .cost(cost)
                        // The data-quality failure condition: no measurable activity.
                        .impressions(0L)
                        .clicks(0L)
                        .conversions(conv)
                        .conversionValue(null)
                        .build());
    }
}
