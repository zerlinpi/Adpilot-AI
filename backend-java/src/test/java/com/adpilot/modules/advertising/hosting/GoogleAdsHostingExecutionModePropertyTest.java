package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.InFlightConflictLock;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationSource;
import com.adpilot.modules.advertising.support.RiskScoreCalculator;
import com.adpilot.modules.apisync.service.GoogleAdsReadService;
import com.adpilot.modules.apisync.vo.GoogleAdsCampaignVo;
import com.adpilot.modules.apisync.vo.GoogleAdsReadResult;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.ArgumentCaptor;
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
 * Property-based test for execution-mode gating of the Google Ads AI hosting pipeline.
 *
 * <p>Feature: platform-workspace-rbac, Property 15: AI hosting respects execution mode.
 *
 * <p>Validates: Requirements 8.4.
 *
 * <p>The property under test, wired end-to-end through the real
 * {@link GoogleAdsHostingEngine} → {@link OptimizationCoordinatorImpl} →
 * {@link DecisionRoutingPipelineImpl} → {@link GoogleAdsHostingService} chain
 * (only the I/O boundaries — {@link GoogleAdsReadService}, {@link OperationService},
 * {@link InFlightConflictLock}, and {@link ExecutionModeResolver} — are mocked):
 *
 * <ul>
 *   <li>For any Google Ads hosting candidate, while the resolved Execution_Mode is
 *       {@code observe_only} or {@code recommend_only}, <b>no Operation is created</b>,
 *       so nothing is ever submitted to Google Ads.</li>
 *   <li>A submission is only created under {@code approval_required} or {@code auto_execute}.
 *       Under {@code approval_required} every created Operation is marked
 *       {@code approvalRequired = true} (it routes through the approval pipeline and is only
 *       submitted after approval), never as a directly-submittable pending Operation.</li>
 *   <li>Every Operation that is created is an {@code AI_HOSTING} {@code PLATFORM_MUTATION}
 *       routed through the platform-generic Operation pipeline.</li>
 * </ul>
 */
class GoogleAdsHostingExecutionModePropertyTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final BigDecimal TARGET_ACOS = new BigDecimal("0.30");

    /**
     * Feature: platform-workspace-rbac, Property 15: AI hosting respects execution mode.
     *
     * <p>Validates: Requirements 8.4.
     */
    @Property(tries = 200)
    void aiHostingRespectsExecutionMode(
            @ForAll("campaigns") GoogleAdsCampaignVo campaign,
            @ForAll ExecutionMode mode) {

        // --- Wire the real engine + coordinator + routing pipeline; mock only the I/O edges. ---
        GoogleAdsReadService readService = Mockito.mock(GoogleAdsReadService.class);
        OperationService operationService = Mockito.mock(OperationService.class);
        InFlightConflictLock inFlightConflictLock = Mockito.mock(InFlightConflictLock.class);
        ExecutionModeResolver executionModeResolver = Mockito.mock(ExecutionModeResolver.class);

        // No campaign has an in-flight conflict, so candidates always reach the routing stage.
        when(inFlightConflictLock.hasInFlightOperation(any(), any(), any())).thenReturn(false);

        GoogleAdsHostingEngine engine = new GoogleAdsHostingEngine(new RiskScoreCalculator());
        DecisionRoutingPipeline routingPipeline = new DecisionRoutingPipelineImpl(new HighRiskClassifierImpl());
        OptimizationCoordinator coordinator =
                new OptimizationCoordinatorImpl(inFlightConflictLock, routingPipeline);

        GoogleAdsHostingService service = new GoogleAdsHostingService(
                readService, engine, coordinator, executionModeResolver, operationService);
        ReflectionTestUtils.setField(service, "defaultTargetAcos", TARGET_ACOS);
        ReflectionTestUtils.setField(service, "lookbackDays", 14);
        ReflectionTestUtils.setField(service, "minDailyBudget", new BigDecimal("1.00"));
        ReflectionTestUtils.setField(service, "maxDailyBudget", new BigDecimal("100000.00"));
        ReflectionTestUtils.setField(service, "maxBudgetIncreaseRatio", new BigDecimal("0.20"));
        ReflectionTestUtils.setField(service, "maxBudgetDecreaseRatio", new BigDecimal("0.30"));

        when(readService.getCampaigns(STORE_ID))
                .thenReturn(GoogleAdsReadResult.ok(List.of(campaign)));
        when(executionModeResolver.resolveForCampaign(any(), any(), any())).thenReturn(mode);

        // --- Act ---
        GoogleAdsHostingService.HostingRunSummary summary = service.optimizeStore(STORE_ID);

        // --- Assert the execution-mode gate ---
        if (mode == ExecutionMode.OBSERVE_ONLY || mode == ExecutionMode.RECOMMEND_ONLY) {
            // No submission to Google Ads under observe_only / recommend_only.
            assertThat(summary.getOperationsCreated())
                    .as("no Operation under %s", mode)
                    .isZero();
            verify(operationService, never()).createOperation(any());
        } else {
            // Only approval_required / auto_execute may route a submission. Capture whatever
            // was created and assert every created Operation is a properly routed AI_HOSTING
            // platform mutation.
            ArgumentCaptor<CreateOperationCommand> captor =
                    ArgumentCaptor.forClass(CreateOperationCommand.class);
            verify(operationService, Mockito.atLeast(0)).createOperation(captor.capture());
            List<CreateOperationCommand> created = captor.getAllValues();

            assertThat(created)
                    .as("operationsCreated counter matches captured commands")
                    .hasSize(summary.getOperationsCreated());

            for (CreateOperationCommand cmd : created) {
                assertThat(cmd.getOperationSource()).isEqualTo(OperationSource.AI_HOSTING);
                assertThat(cmd.getOperationScope()).isEqualTo(OperationScope.PLATFORM_MUTATION);
                assertThat(cmd.getStoreId()).isEqualTo(STORE_ID);

                if (mode == ExecutionMode.APPROVAL_REQUIRED) {
                    // Under approval_required, a submission is only routed AFTER approval:
                    // every created Operation must be awaiting approval, never directly pending.
                    assertThat(cmd.getApprovalRequired())
                            .as("approval_required Operation must await approval, not submit directly")
                            .isTrue();
                }
            }
        }
    }

    // --- generators --------------------------------------------------------

    /**
     * Google Ads campaigns that pass the data-quality gate (non-zero traffic) and exercise
     * candidate production across the engine's branches: budget increases (ACoS below target),
     * budget decreases (ACoS above target), and spend-without-sales pauses (high-risk state
     * change). All are ENABLED so the pause branch is reachable.
     */
    @Provide
    Arbitrary<GoogleAdsCampaignVo> campaigns() {
        Arbitrary<Long> budgetUnits = Arbitraries.longs().between(20L, 2_000L);
        Arbitrary<Long> impressions = Arbitraries.longs().between(100L, 100_000L);
        Arbitrary<Long> clicks = Arbitraries.longs().between(1L, 5_000L);
        // costUnits / valueUnits drive ACoS = cost/value across the full range around target.
        Arbitrary<Long> costUnits = Arbitraries.longs().between(10L, 1_900L);
        Arbitrary<Long> valueUnits = Arbitraries.longs().between(0L, 2_000L);

        return Combinators.combine(budgetUnits, impressions, clicks, costUnits, valueUnits)
                .as((budget, imp, clk, cost, value) -> {
                    BigDecimal conversionValue = BigDecimal.valueOf(value);
                    double conversions = value > 0 ? 5d : 0d;
                    return GoogleAdsCampaignVo.builder()
                            .campaignId("camp-" + Math.abs((budget * 31 + cost * 17 + value) % 100000))
                            .name("campaign")
                            .status("ENABLED")
                            .budget(BigDecimal.valueOf(budget))
                            .impressions(imp)
                            .clicks(clk)
                            .cost(BigDecimal.valueOf(cost))
                            .conversions(conversions)
                            .conversionValue(conversionValue)
                            .build();
                });
    }
}
