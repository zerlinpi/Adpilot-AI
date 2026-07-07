package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationSource;
import com.adpilot.modules.advertising.service.HostingAdjustmentType;
import com.adpilot.modules.apisync.service.GoogleAdsReadService;
import com.adpilot.modules.apisync.vo.GoogleAdsCampaignVo;
import com.adpilot.modules.apisync.vo.GoogleAdsReadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@link GoogleAdsHostingService} wiring to the coordinator and Operation pipeline
 * (platform-workspace-rbac Req 8.2, 8.3, 8.4, 8.8).
 */
@DisplayName("GoogleAdsHostingService")
class GoogleAdsHostingServiceTest {

    private static final UUID STORE_ID = UUID.randomUUID();

    private GoogleAdsReadService readService;
    private GoogleAdsHostingEngine engine;
    private OptimizationCoordinator coordinator;
    private ExecutionModeResolver executionModeResolver;
    private OperationService operationService;
    private GoogleAdsHostingService service;

    @BeforeEach
    void setUp() {
        readService = mock(GoogleAdsReadService.class);
        engine = new GoogleAdsHostingEngine(new com.adpilot.modules.advertising.support.RiskScoreCalculator());
        coordinator = mock(OptimizationCoordinator.class);
        executionModeResolver = mock(ExecutionModeResolver.class);
        operationService = mock(OperationService.class);

        service = new GoogleAdsHostingService(
                readService, engine, coordinator, executionModeResolver, operationService);

        ReflectionTestUtils.setField(service, "defaultTargetAcos", new BigDecimal("0.30"));
        ReflectionTestUtils.setField(service, "lookbackDays", 14);
        ReflectionTestUtils.setField(service, "minDailyBudget", new BigDecimal("1.00"));
        ReflectionTestUtils.setField(service, "maxDailyBudget", new BigDecimal("100000.00"));
        ReflectionTestUtils.setField(service, "maxBudgetIncreaseRatio", new BigDecimal("0.20"));
        ReflectionTestUtils.setField(service, "maxBudgetDecreaseRatio", new BigDecimal("0.30"));

        when(executionModeResolver.resolveForCampaign(any(), any(), any()))
                .thenReturn(ExecutionMode.AUTO_EXECUTE);
        // operationService.createOperation returns null by default; the service ignores the result.
    }

    @Test
    @DisplayName("Skips a campaign that fails the data-quality check and records the reason (Req 8.8)")
    void skipsOnDataQuality() {
        // No impressions and no clicks → data-quality failure.
        GoogleAdsCampaignVo campaign = GoogleAdsCampaignVo.builder()
                .campaignId("c1").name("c1").status("ENABLED")
                .budget(new BigDecimal("100.00")).cost(BigDecimal.ZERO)
                .impressions(0).clicks(0).conversions(0d).build();
        when(readService.getCampaigns(STORE_ID))
                .thenReturn(GoogleAdsReadResult.ok(List.of(campaign)));

        GoogleAdsHostingService.HostingRunSummary summary = service.optimizeStore(STORE_ID);

        assertThat(summary.getSkipped()).isEqualTo(1);
        assertThat(summary.getSkips()).hasSize(1);
        assertThat(summary.getSkips().get(0).reason()).isEqualTo("DATA_QUALITY_INSUFFICIENT");
        verify(operationService, never()).createOperation(any());
    }

    @Test
    @DisplayName("Creates an AI_HOSTING platform-mutation Operation for a routed survivor (Req 8.3)")
    void createsAiHostingOperation() {
        GoogleAdsCampaignVo campaign = GoogleAdsCampaignVo.builder()
                .campaignId("c1").name("c1").status("ENABLED")
                .budget(new BigDecimal("100.00")).cost(new BigDecimal("50.00"))
                .conversionValue(new BigDecimal("500.00"))
                .impressions(2000).clicks(200).conversions(10d).build();
        when(readService.getCampaigns(STORE_ID))
                .thenReturn(GoogleAdsReadResult.ok(List.of(campaign)));

        // The coordinator routes one budget candidate to a PENDING_OPERATION.
        UUID campaignUuid = GoogleAdsHostingEngine.campaignUuid("c1");
        CandidateDecision candidate = new CandidateDecision(
                UUID.randomUUID(), STORE_ID, campaignUuid,
                "campaign", campaignUuid, "daily_budget",
                HostingAdjustmentType.BUDGET, "budget", GoogleAdsHostingEngine.ENGINE_GOOGLE_ADS,
                new BigDecimal("100.00"), new BigDecimal("110.00"),
                new BigDecimal("0.1"), null, new BigDecimal("0.8"), "{}");
        CoordinationResult coordination = new CoordinationResult(
                List.of(new CoordinationResult.CoordinatedCandidate(
                        candidate, new BigDecimal("110.00"), RoutingResult.pending("auto_execute_below_threshold"))),
                List.of());
        when(coordinator.coordinate(any(), any(), eq(campaignUuid), eq(STORE_ID),
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(coordination);

        GoogleAdsHostingService.HostingRunSummary summary = service.optimizeStore(STORE_ID);

        assertThat(summary.getOperationsCreated()).isEqualTo(1);
        ArgumentCaptor<CreateOperationCommand> captor = ArgumentCaptor.forClass(CreateOperationCommand.class);
        verify(operationService).createOperation(captor.capture());
        CreateOperationCommand cmd = captor.getValue();
        assertThat(cmd.getOperationSource()).isEqualTo(OperationSource.AI_HOSTING);
        assertThat(cmd.getOperationScope()).isEqualTo(OperationScope.PLATFORM_MUTATION);
        assertThat(cmd.getStoreId()).isEqualTo(STORE_ID);
        assertThat(cmd.getBeforeValue()).isEqualTo(new BigDecimal("100.00"));
        assertThat(cmd.getAfterValue()).isEqualTo(new BigDecimal("110.00"));
        assertThat(cmd.getAiDecision()).isNotNull();
        assertThat(cmd.getPersonalityRuleVersion()).isNotBlank();
    }

    @Test
    @DisplayName("Does not create an Operation for an AI_DECISIONS_ONLY routing (Req 8.4)")
    void noOperationForObserveOnlyRouting() {
        GoogleAdsCampaignVo campaign = GoogleAdsCampaignVo.builder()
                .campaignId("c1").name("c1").status("ENABLED")
                .budget(new BigDecimal("100.00")).cost(new BigDecimal("50.00"))
                .conversionValue(new BigDecimal("500.00"))
                .impressions(2000).clicks(200).conversions(10d).build();
        when(readService.getCampaigns(STORE_ID))
                .thenReturn(GoogleAdsReadResult.ok(List.of(campaign)));

        UUID campaignUuid = GoogleAdsHostingEngine.campaignUuid("c1");
        CandidateDecision candidate = new CandidateDecision(
                UUID.randomUUID(), STORE_ID, campaignUuid,
                "campaign", campaignUuid, "daily_budget",
                HostingAdjustmentType.BUDGET, "budget", GoogleAdsHostingEngine.ENGINE_GOOGLE_ADS,
                new BigDecimal("100.00"), new BigDecimal("110.00"),
                new BigDecimal("0.1"), null, new BigDecimal("0.8"), "{}");
        CoordinationResult coordination = new CoordinationResult(
                List.of(new CoordinationResult.CoordinatedCandidate(
                        candidate, new BigDecimal("110.00"), RoutingResult.aiDecisionsOnly("observe_only"))),
                List.of());
        when(coordinator.coordinate(any(), any(), eq(campaignUuid), eq(STORE_ID),
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(coordination);

        GoogleAdsHostingService.HostingRunSummary summary = service.optimizeStore(STORE_ID);

        assertThat(summary.getOperationsCreated()).isZero();
        verify(operationService, never()).createOperation(any());
    }

    @Test
    @DisplayName("Returns an empty summary when the store has no active Google Ads connection")
    void emptyWhenConnectPrompt() {
        when(readService.getCampaigns(STORE_ID)).thenReturn(GoogleAdsReadResult.connectPrompt());

        GoogleAdsHostingService.HostingRunSummary summary = service.optimizeStore(STORE_ID);

        assertThat(summary.getProcessed()).isZero();
        verify(operationService, never()).createOperation(any());
    }
}
