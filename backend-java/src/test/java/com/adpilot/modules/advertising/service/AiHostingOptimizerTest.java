package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the refactored {@link AiHostingOptimizer} (Req 9.3, 22, 49, 54): recent-ACoS
 * computation from {@code performance_daily}, clamped bid adjustment via the shared
 * {@link HostingBidOptimizer}, and emission of {@code ai_hosting} {@code platform_mutation}
 * Operations through {@link OperationService#createOperation} rather than direct
 * {@code bid_changes}/{@code keyword.bid} writes.
 *
 * <p>The exhaustive bid-clamp guarantees themselves are covered by the {@link HostingBidOptimizer}
 * property tests; these tests verify the optimizer's orchestration around that pure helper and the
 * Operation-path wiring.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiHostingOptimizerTest {

    @Mock private CampaignMapper campaignMapper;
    @Mock private KeywordMapper keywordMapper;
    @Mock private PerformanceDailyMapper performanceDailyMapper;
    @Mock private OperationService operationService;
    @Mock private PersonalityResolver personalityResolver;
    @Mock private PersonalityPolicyService personalityPolicyService;
    @Mock private OperationMapper operationMapper;

    private AiHostingOptimizer optimizer;

    @BeforeEach
    void setUp() {
        optimizer = new AiHostingOptimizer(campaignMapper, keywordMapper, performanceDailyMapper,
                operationService, personalityResolver, personalityPolicyService, operationMapper,
                new com.adpilot.modules.advertising.hosting.ReversibilityClassifier());
        ReflectionTestUtils.setField(optimizer, "lookbackDays", 14);
        ReflectionTestUtils.setField(optimizer, "minBid", new BigDecimal("0.02"));
        ReflectionTestUtils.setField(optimizer, "maxBid", new BigDecimal("1000"));
        ReflectionTestUtils.setField(optimizer, "phaseConfig", "V1");
        ReflectionTestUtils.setField(optimizer, "globalPause", false);

        when(personalityResolver.resolveForCampaign(any(CampaignEntity.class)))
                .thenReturn(AiPersonality.BALANCED);
        when(personalityPolicyService.resolvePolicy(any())).thenReturn(balancedPolicy());
    }

    private PersonalityPolicyEntity balancedPolicy() {
        return PersonalityPolicyEntity.builder()
                .scope("system")
                .personality("balanced")
                .maxBidIncreaseRatio(new BigDecimal("0.10"))
                .maxBidDecreaseRatio(new BigDecimal("0.15"))
                .approvalBidChangeRatio(new BigDecimal("0.07"))
                .ruleVersion("balanced-v1")
                .build();
    }

    private CampaignEntity hostedCampaign(BigDecimal targetAcos) {
        return CampaignEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .name("Camp")
                .hostingEnabled(true)
                .targetAcos(targetAcos)
                .build();
    }

    private KeywordEntity keyword(BigDecimal bid) {
        return KeywordEntity.builder()
                .id(UUID.randomUUID())
                .campaignId(UUID.randomUUID())
                .adGroupId(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .keywordText("kw")
                .status("enabled")
                .bid(bid)
                .version(0L)
                .build();
    }

    private PerformanceDailyEntity perf(double spend, double sales) {
        return PerformanceDailyEntity.builder()
                .date(LocalDate.now())
                .spend(BigDecimal.valueOf(spend))
                .sales(BigDecimal.valueOf(sales))
                .build();
    }

    @Test
    void emitsBidDecreaseOperationWhenRecentAcosAboveTarget() {
        CampaignEntity campaign = hostedCampaign(new BigDecimal("25"));
        KeywordEntity kw = keyword(new BigDecimal("1.00"));
        // ACoS = 50 / 100 * 100 = 50% > 25% target -> bid should decrease.
        when(performanceDailyMapper.selectList(any())).thenReturn(List.of(perf(50, 100)));
        when(keywordMapper.selectList(any())).thenReturn(List.of(kw));

        int created = optimizer.optimizeCampaign(campaign, HostingPhase.V1);

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<CreateOperationCommand> captor = ArgumentCaptor.forClass(CreateOperationCommand.class);
        verify(operationService).createOperation(captor.capture());
        CreateOperationCommand cmd = captor.getValue();
        assertThat(cmd.getOperationSource()).isEqualTo(OperationSource.AI_HOSTING);
        assertThat(cmd.getOperationScope()).isEqualTo(OperationScope.PLATFORM_MUTATION);
        assertThat(cmd.getEntityType()).isEqualTo("keyword");
        assertThat(cmd.getEntityId()).isEqualTo(kw.getId());
        assertThat(cmd.getField()).isEqualTo("bid");
        assertThat(cmd.getPersonalityRuleVersion()).isEqualTo("balanced-v1");
        assertThat(cmd.getAiDecision()).isNotNull();
        assertThat((BigDecimal) cmd.getAfterValue()).isLessThan(new BigDecimal("1.00"));
        // never below the -15% band (balanced decrease ratio) of 1.00 = 0.85
        assertThat((BigDecimal) cmd.getAfterValue()).isGreaterThanOrEqualTo(new BigDecimal("0.85"));

        // The confirmed value (keyword bid) is NOT written directly anymore.
        verify(keywordMapper, never()).updateById(any());
    }

    @Test
    void emitsBidIncreaseOperationWhenRecentAcosBelowTarget() {
        CampaignEntity campaign = hostedCampaign(new BigDecimal("25"));
        KeywordEntity kw = keyword(new BigDecimal("1.00"));
        // ACoS = 10 / 100 * 100 = 10% < 25% target -> bid should increase.
        when(performanceDailyMapper.selectList(any())).thenReturn(List.of(perf(10, 100)));
        when(keywordMapper.selectList(any())).thenReturn(List.of(kw));

        optimizer.optimizeCampaign(campaign, HostingPhase.V1);

        ArgumentCaptor<CreateOperationCommand> captor = ArgumentCaptor.forClass(CreateOperationCommand.class);
        verify(operationService).createOperation(captor.capture());
        BigDecimal after = (BigDecimal) captor.getValue().getAfterValue();
        assertThat(after).isGreaterThan(new BigDecimal("1.00"));
        // never above the +10% band (balanced increase ratio) of 1.00 = 1.10
        assertThat(after).isLessThanOrEqualTo(new BigDecimal("1.10"));
    }

    @Test
    void skipsWhenNoPerformanceSignal() {
        CampaignEntity campaign = hostedCampaign(new BigDecimal("25"));
        when(performanceDailyMapper.selectList(any())).thenReturn(List.of());

        int created = optimizer.optimizeCampaign(campaign, HostingPhase.V1);

        assertThat(created).isZero();
        verify(keywordMapper, never()).selectList(any());
        verify(operationService, never()).createOperation(any());
    }

    @Test
    void noOperationWhenRecentAcosEqualsTarget() {
        CampaignEntity campaign = hostedCampaign(new BigDecimal("25"));
        KeywordEntity kw = keyword(new BigDecimal("1.00"));
        // ACoS = 25 / 100 * 100 = 25% == target -> fixpoint, no Operation.
        when(performanceDailyMapper.selectList(any())).thenReturn(List.of(perf(25, 100)));
        when(keywordMapper.selectList(any())).thenReturn(List.of(kw));

        int created = optimizer.optimizeCampaign(campaign, HostingPhase.V1);

        assertThat(created).isZero();
        verify(operationService, never()).createOperation(any());
    }

    @Test
    void globalPauseGeneratesNoNewOperations() {
        ReflectionTestUtils.setField(optimizer, "globalPause", true);

        AiHostingOptimizer.OptimizationSummary summary = optimizer.runOnce();

        assertThat(summary.isPaused()).isTrue();
        assertThat(summary.getOperationsCreated()).isZero();
        verify(campaignMapper, never()).selectList(any());
        verify(operationService, never()).createOperation(any());
    }

    @Test
    void isolatesPerCampaignFailures() {
        CampaignEntity ok = hostedCampaign(new BigDecimal("25"));
        CampaignEntity broken = hostedCampaign(new BigDecimal("25"));
        when(campaignMapper.selectList(any())).thenReturn(List.of(broken, ok));
        when(performanceDailyMapper.selectList(any())).thenReturn(List.of(perf(50, 100)));
        // First campaign's keyword lookup blows up; second succeeds.
        when(keywordMapper.selectList(any()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(List.of(keyword(new BigDecimal("1.00"))));

        AiHostingOptimizer.OptimizationSummary summary = optimizer.runOnce();

        assertThat(summary.getCampaignsProcessed()).isEqualTo(2);
        assertThat(summary.getCampaignsFailed()).isEqualTo(1);
        assertThat(summary.getOperationsCreated()).isEqualTo(1);
        assertThat(summary.getBidsChanged()).isEqualTo(1);
    }
}
