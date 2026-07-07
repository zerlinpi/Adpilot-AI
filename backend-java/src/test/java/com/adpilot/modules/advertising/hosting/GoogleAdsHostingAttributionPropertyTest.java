package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationSource;
import com.adpilot.modules.advertising.service.HostingAdjustmentType;
import com.adpilot.modules.apisync.service.GoogleAdsReadService;
import com.adpilot.modules.apisync.vo.GoogleAdsCampaignVo;
import com.adpilot.modules.apisync.vo.GoogleAdsReadResult;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the Google Ads AI-hosting attribution seam in
 * {@link GoogleAdsHostingService} (platform-workspace-rbac Req 8.3).
 *
 * <p>Feature: platform-workspace-rbac, Property 14: AI-hosting Google Ads decision produces an
 * attributed platform-mutation Operation
 *
 * <p><b>Validates: Requirements 8.3</b>
 *
 * <p>For any {@link GoogleAdsHostingEngine} candidate the {@link OptimizationCoordinator} accepts
 * (routes to {@link RoutingOutcome#PENDING_OPERATION} or
 * {@link RoutingOutcome#AWAITING_APPROVAL_OPERATION}), the resulting {@link CreateOperationCommand}
 * must carry {@link OperationScope#PLATFORM_MUTATION} and {@link OperationSource#AI_HOSTING}, and
 * record the before value, the after value, and the decision snapshot. The engine and coordinator
 * are modelled as seams: the engine yields generated candidates and the coordinator echoes each as
 * an accepted survivor, so the property exercises the real
 * {@code createOperations}/{@code buildCommand} attribution path over arbitrary accepted candidates.
 */
@Label("Feature: platform-workspace-rbac, Property 14: AI-hosting Google Ads decision produces an attributed platform-mutation Operation")
class GoogleAdsHostingAttributionPropertyTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CAMPAIGN_UUID = GoogleAdsHostingEngine.campaignUuid("c1");

    /**
     * Feature: platform-workspace-rbac, Property 14: AI-hosting Google Ads decision produces an
     * attributed platform-mutation Operation.
     *
     * <p><b>Validates: Requirements 8.3</b>
     */
    @Property(tries = 100)
    void acceptedCandidateProducesAttributedPlatformMutationOperation(
            @ForAll("acceptedCandidateLists") List<CandidateDecision> candidates) {

        // Fresh mocks per invocation so verification/capture never accumulates across tries.
        GoogleAdsReadService readService = mock(GoogleAdsReadService.class);
        GoogleAdsHostingEngine engine = mock(GoogleAdsHostingEngine.class);
        OptimizationCoordinator coordinator = mock(OptimizationCoordinator.class);
        ExecutionModeResolver executionModeResolver = mock(ExecutionModeResolver.class);
        OperationService operationService = mock(OperationService.class);

        GoogleAdsHostingService service = new GoogleAdsHostingService(
                readService, engine, coordinator, executionModeResolver, operationService);
        ReflectionTestUtils.setField(service, "defaultTargetAcos", new BigDecimal("0.30"));
        ReflectionTestUtils.setField(service, "lookbackDays", 14);
        ReflectionTestUtils.setField(service, "minDailyBudget", new BigDecimal("1.00"));
        ReflectionTestUtils.setField(service, "maxDailyBudget", new BigDecimal("100000.00"));
        ReflectionTestUtils.setField(service, "maxBudgetIncreaseRatio", new BigDecimal("0.20"));
        ReflectionTestUtils.setField(service, "maxBudgetDecreaseRatio", new BigDecimal("0.30"));

        // One campaign that passes the data-quality check (has measurable activity).
        GoogleAdsCampaignVo campaign = GoogleAdsCampaignVo.builder()
                .campaignId("c1").name("c1").status("ENABLED")
                .budget(new BigDecimal("100.00")).cost(new BigDecimal("50.00"))
                .conversionValue(new BigDecimal("500.00"))
                .impressions(2000).clicks(200).conversions(10d).build();
        when(readService.getCampaigns(any())).thenReturn(GoogleAdsReadResult.ok(List.of(campaign)));

        // The engine yields the generated candidates (it never creates Operations directly, Req 8.2).
        when(engine.produceCandidates(any(), any(), any(), any(), any())).thenReturn(candidates);

        when(executionModeResolver.resolveForCampaign(any(), any(), any()))
                .thenReturn(ExecutionMode.AUTO_EXECUTE);

        // The coordinator accepts every candidate: each becomes a routed survivor whose outcome is an
        // Operation-creating outcome (PENDING or AWAITING_APPROVAL), alternating to cover both.
        when(coordinator.coordinate(any(), any(), any(), any(),
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<CandidateDecision> passed = invocation.getArgument(0, List.class);
                    List<CoordinationResult.CoordinatedCandidate> survivors = new ArrayList<>();
                    for (int i = 0; i < passed.size(); i++) {
                        CandidateDecision c = passed.get(i);
                        BigDecimal clipped = c.proposedValue(); // null for state changes
                        RoutingResult routing = (i % 2 == 0)
                                ? RoutingResult.pending("auto_execute_below_threshold")
                                : RoutingResult.awaitingApproval("risk_above_threshold");
                        survivors.add(new CoordinationResult.CoordinatedCandidate(c, clipped, routing));
                    }
                    return new CoordinationResult(survivors, List.of());
                });

        GoogleAdsHostingService.HostingRunSummary summary = service.optimizeStore(STORE_ID);

        // Every accepted candidate produces exactly one Operation (Req 8.3).
        assertThat(summary.getOperationsCreated()).isEqualTo(candidates.size());

        ArgumentCaptor<CreateOperationCommand> captor = ArgumentCaptor.forClass(CreateOperationCommand.class);
        Mockito.verify(operationService, Mockito.times(candidates.size())).createOperation(captor.capture());

        Set<String> generatedSnapshots = new HashSet<>();
        for (CandidateDecision c : candidates) {
            generatedSnapshots.add(c.decisionSnapshot());
        }

        for (CreateOperationCommand cmd : captor.getAllValues()) {
            // Attribution: platform-mutation Operation sourced from AI hosting (Req 8.3).
            assertThat(cmd.getOperationScope()).isEqualTo(OperationScope.PLATFORM_MUTATION);
            assertThat(cmd.getOperationSource()).isEqualTo(OperationSource.AI_HOSTING);
            assertThat(cmd.getStoreId()).isEqualTo(STORE_ID);

            // Records the before value and the after value.
            assertThat(cmd.getBeforeValue()).isNotNull();
            assertThat(cmd.getAfterValue()).isNotNull();

            // Records the decision snapshot (the candidate's snapshot is carried on the AI decision)
            // and the Personality_Rule_Version required for ai_hosting Operations.
            assertThat(cmd.getAiDecision()).isNotNull();
            assertThat(cmd.getAiDecision().getDecisionReason()).isIn(generatedSnapshots);
            assertThat(cmd.getPersonalityRuleVersion()).isNotBlank();
        }
    }

    // ── generators ──────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<List<CandidateDecision>> acceptedCandidateLists() {
        return googleAdsCandidate().list().ofMinSize(1).ofMaxSize(5);
    }

    /**
     * Generates Google Ads engine candidates of the two in-place mutation change types that carry a
     * before value: {@code budget} (numeric daily-budget change) and {@code state} (enable→pause).
     */
    @Provide
    Arbitrary<CandidateDecision> googleAdsCandidate() {
        Arbitrary<String> changeTypes = Arbitraries.of(
                GoogleAdsHostingEngine.CHANGE_TYPE_BUDGET, GoogleAdsHostingEngine.CHANGE_TYPE_STATE);
        Arbitrary<BigDecimal> before = money(1.0, 1000.0);
        Arbitrary<BigDecimal> delta = money(1.0, 200.0);
        Arbitrary<Boolean> increase = Arbitraries.of(true, false);
        Arbitrary<Integer> snapId = Arbitraries.integers().between(1, 1_000_000);

        return Combinators.combine(changeTypes, before, delta, increase, snapId)
                .as((changeType, baseBudget, d, inc, sid) -> {
                    String snapshot = "{\"engine\":\"GOOGLE_ADS\",\"changeType\":\""
                            + changeType + "\",\"snap\":" + sid + "}";
                    if (GoogleAdsHostingEngine.CHANGE_TYPE_STATE.equals(changeType)) {
                        // Status change: no in-place numeric before/after on the candidate.
                        return new CandidateDecision(
                                UUID.randomUUID(), STORE_ID, CAMPAIGN_UUID,
                                "campaign", CAMPAIGN_UUID, "status",
                                HostingAdjustmentType.BUDGET, GoogleAdsHostingEngine.CHANGE_TYPE_STATE,
                                GoogleAdsHostingEngine.ENGINE_GOOGLE_ADS,
                                null, null, new BigDecimal("0.40"), null, new BigDecimal("0.50"), snapshot);
                    }
                    BigDecimal proposed = inc
                            ? baseBudget.add(d)
                            : baseBudget.subtract(d).max(new BigDecimal("0.01"));
                    if (proposed.compareTo(baseBudget) == 0) {
                        proposed = baseBudget.add(new BigDecimal("1.00"));
                    }
                    return new CandidateDecision(
                            UUID.randomUUID(), STORE_ID, CAMPAIGN_UUID,
                            "campaign", CAMPAIGN_UUID, "daily_budget",
                            HostingAdjustmentType.BUDGET, GoogleAdsHostingEngine.CHANGE_TYPE_BUDGET,
                            GoogleAdsHostingEngine.ENGINE_GOOGLE_ADS,
                            baseBudget, proposed, new BigDecimal("0.10"), null, new BigDecimal("0.80"), snapshot);
                });
    }

    private static Arbitrary<BigDecimal> money(double min, double max) {
        return Arbitraries.doubles().between(min, max)
                .map(v -> BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP));
    }
}
