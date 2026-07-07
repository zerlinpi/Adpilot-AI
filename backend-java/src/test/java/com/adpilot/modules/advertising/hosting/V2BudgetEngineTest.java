package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.service.AiPersonality;
import com.adpilot.modules.advertising.service.HostingAdjustmentType;
import com.adpilot.modules.advertising.service.HostingPhase;
import com.adpilot.modules.advertising.service.PersonalityPolicyService;
import com.adpilot.modules.advertising.service.PersonalityResolver;
import com.adpilot.modules.advertising.support.RiskScoreCalculator;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the V2BudgetEngine (Requirement 4).
 *
 * <p>Validates: Requirements 4.1, 4.2, 4.3, 4.5, 4.6, 4.7, 4.8, 4.9.</p>
 */
@DisplayName("V2BudgetEngine")
class V2BudgetEngineTest {

    private PersonalityResolver personalityResolver;
    private PersonalityPolicyService personalityPolicyService;
    private RiskScoreCalculator riskScoreCalculator;
    private OperationMapper operationMapper;
    private LearningPeriodService learningPeriodService;
    private V2BudgetEngineImpl engine;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CAMPAIGN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        personalityResolver = mock(PersonalityResolver.class);
        personalityPolicyService = mock(PersonalityPolicyService.class);
        riskScoreCalculator = new RiskScoreCalculator();
        operationMapper = mock(OperationMapper.class);
        learningPeriodService = mock(LearningPeriodService.class);

        engine = new V2BudgetEngineImpl(
                personalityResolver, personalityPolicyService,
                riskScoreCalculator, operationMapper, learningPeriodService);

        // Set phase to V2 so the engine is active
        ReflectionTestUtils.setField(engine, "phaseConfig", "V2");

        // Default mock behavior
        when(personalityResolver.resolveForCampaign(any(CampaignEntity.class)))
                .thenReturn(AiPersonality.SYSTEM_FALLBACK);
        when(personalityPolicyService.resolvePolicy(anyString()))
                .thenReturn(defaultPolicy());
        when(operationMapper.selectOne(any())).thenReturn(null);
        when(learningPeriodService.shouldBlockBudgetChanges(any())).thenReturn(false);
    }

    // =========================================================================
    // Phase gating (Req 4.8)
    // =========================================================================

    @Nested
    @DisplayName("Phase gating (Req 4.8)")
    class PhaseGating {

        @Test
        @DisplayName("Skips when phase is V1 (does not support BUDGET)")
        void skipsWhenPhaseIsV1() {
            ReflectionTestUtils.setField(engine, "phaseConfig", "V1");

            CampaignEntity campaign = campaign(new BigDecimal("0.30"), new BigDecimal("100.00"));
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("20"), new BigDecimal("100")));
            SafetyBoundary boundary = defaultBoundary();

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 60);

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason()).isEqualTo("PHASE_DISABLED");
            assertThat(result.candidates()).isEmpty();
        }

        @Test
        @DisplayName("Runs when phase is V2")
        void runsWhenPhaseIsV2() {
            ReflectionTestUtils.setField(engine, "phaseConfig", "V2");

            CampaignEntity campaign = campaign(new BigDecimal("0.30"), new BigDecimal("100.00"));
            // ACoS = 15/100 = 0.15, below target 0.30 → should propose increase
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("15"), new BigDecimal("100")));
            SafetyBoundary boundary = defaultBoundary();

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 60);

            assertThat(result.isSkipped()).isFalse();
            assertThat(result.candidates()).isNotEmpty();
        }

        @Test
        @DisplayName("Runs when phase is V3 (V3 also supports BUDGET)")
        void runsWhenPhaseIsV3() {
            ReflectionTestUtils.setField(engine, "phaseConfig", "V3");

            CampaignEntity campaign = campaign(new BigDecimal("0.30"), new BigDecimal("100.00"));
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("15"), new BigDecimal("100")));
            SafetyBoundary boundary = defaultBoundary();

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 60);

            assertThat(result.isSkipped()).isFalse();
            assertThat(result.candidates()).isNotEmpty();
        }
    }

    // =========================================================================
    // NO_TARGET_ACOS skip (Req 4.9)
    // =========================================================================

    @Nested
    @DisplayName("NO_TARGET_ACOS skip (Req 4.9)")
    class NoTargetAcos {

        @Test
        @DisplayName("Skips campaign with null target ACoS")
        void skipsNullTargetAcos() {
            CampaignEntity campaign = campaign(null, new BigDecimal("100.00"));
            DataSnapshot snapshot = emptySnapshot();
            SafetyBoundary boundary = defaultBoundary();

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 60);

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason()).isEqualTo("NO_TARGET_ACOS");
        }

        @Test
        @DisplayName("Skips campaign with zero target ACoS")
        void skipsZeroTargetAcos() {
            CampaignEntity campaign = campaign(BigDecimal.ZERO, new BigDecimal("100.00"));
            DataSnapshot snapshot = emptySnapshot();
            SafetyBoundary boundary = defaultBoundary();

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 60);

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason()).isEqualTo("NO_TARGET_ACOS");
        }
    }

    // =========================================================================
    // Learning period skip (Req 19.2)
    // =========================================================================

    @Nested
    @DisplayName("Learning period skip")
    class LearningPeriod {

        @Test
        @DisplayName("Skips campaign in learning period")
        void skipsCampaignInLearningPeriod() {
            when(learningPeriodService.shouldBlockBudgetChanges(CAMPAIGN_ID)).thenReturn(true);

            CampaignEntity campaign = campaign(new BigDecimal("0.30"), new BigDecimal("100.00"));
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("15"), new BigDecimal("100")));
            SafetyBoundary boundary = defaultBoundary();

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 60);

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason()).isEqualTo("LEARNING_PERIOD");
        }
    }

    // =========================================================================
    // Budget increase direction (Req 4.2)
    // =========================================================================

    @Nested
    @DisplayName("Budget increase (Req 4.2)")
    class BudgetIncrease {

        @Test
        @DisplayName("Proposes increase when actual ACoS < target AND inventory > inventoryHealthyDays")
        void proposesIncreaseWhenAcosBelowTargetAndInventoryHealthy() {
            CampaignEntity campaign = campaign(new BigDecimal("0.40"), new BigDecimal("100.00"));
            // ACoS = 20/100 = 0.20, below target 0.40
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("20"), new BigDecimal("100")));
            SafetyBoundary boundary = defaultBoundary(); // inventoryHealthyDays = 30

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 60); // 60 days inventory > 30

            assertThat(result.isSkipped()).isFalse();
            assertThat(result.candidates()).hasSize(1);
            CandidateDecision candidate = result.candidates().get(0);
            assertThat(candidate.proposedValue()).isGreaterThan(candidate.beforeValue());
        }

        @Test
        @DisplayName("Does NOT increase when inventory <= inventoryHealthyDays")
        void doesNotIncreaseWhenInventoryInsufficient() {
            CampaignEntity campaign = campaign(new BigDecimal("0.40"), new BigDecimal("100.00"));
            // ACoS below target
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("20"), new BigDecimal("100")));
            SafetyBoundary boundary = defaultBoundary(); // inventoryHealthyDays = 30

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 20); // 20 days inventory < 30

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason()).isEqualTo("NO_CHANGE");
        }

        @Test
        @DisplayName("Does NOT increase when inventory is null")
        void doesNotIncreaseWhenInventoryNull() {
            CampaignEntity campaign = campaign(new BigDecimal("0.40"), new BigDecimal("100.00"));
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("20"), new BigDecimal("100")));
            SafetyBoundary boundary = defaultBoundary();

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, null);

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason()).isEqualTo("NO_CHANGE");
        }

        @Test
        @DisplayName("Increase is capped by maxDailyBudgetIncreaseRatio")
        void increaseCappedByMaxRatio() {
            // maxIncreaseRatio = 0.10 (10%)
            PersonalityPolicyEntity policy = defaultPolicy();
            policy.setMaxDailyBudgetIncreaseRatio(new BigDecimal("0.10"));
            when(personalityPolicyService.resolvePolicy(anyString())).thenReturn(policy);

            CampaignEntity campaign = campaign(new BigDecimal("0.50"), new BigDecimal("100.00"));
            // ACoS = 10/100 = 0.10, well below target 0.50 → wants a big increase
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("10"), new BigDecimal("100")));
            SafetyBoundary boundary = boundaryWith(
                    SafetyBoundaryLimit.MAX_DAILY_BUDGET_INCREASE_RATIO, new BigDecimal("0.10"));

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 60);

            assertThat(result.isSkipped()).isFalse();
            CandidateDecision candidate = result.candidates().get(0);
            BigDecimal maxAllowed = new BigDecimal("100.00").multiply(
                    BigDecimal.ONE.add(new BigDecimal("0.10")));
            // Proposed should not exceed 110.00 (current * (1 + 0.10))
            assertThat(candidate.proposedValue()).isLessThanOrEqualTo(maxAllowed);
        }
    }

    // =========================================================================
    // Budget decrease direction (Req 4.3)
    // =========================================================================

    @Nested
    @DisplayName("Budget decrease (Req 4.3)")
    class BudgetDecrease {

        @Test
        @DisplayName("Proposes decrease when actual ACoS > target * (1 + acosToleranceRatio)")
        void proposesDecreaseWhenAcosExceedsThreshold() {
            PersonalityPolicyEntity policy = defaultPolicy();
            policy.setAcosToleranceRatio(new BigDecimal("0.10")); // 10% tolerance
            when(personalityPolicyService.resolvePolicy(anyString())).thenReturn(policy);

            CampaignEntity campaign = campaign(new BigDecimal("0.30"), new BigDecimal("100.00"));
            // ACoS = 50/100 = 0.50, threshold = 0.30 * 1.10 = 0.33 → exceeds
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("50"), new BigDecimal("100")));
            SafetyBoundary boundary = defaultBoundary();

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 60);

            assertThat(result.isSkipped()).isFalse();
            assertThat(result.candidates()).hasSize(1);
            CandidateDecision candidate = result.candidates().get(0);
            assertThat(candidate.proposedValue()).isLessThan(candidate.beforeValue());
        }

        @Test
        @DisplayName("Does NOT decrease when actual ACoS is within tolerance")
        void doesNotDecreaseWithinTolerance() {
            PersonalityPolicyEntity policy = defaultPolicy();
            policy.setAcosToleranceRatio(new BigDecimal("0.20")); // 20% tolerance
            when(personalityPolicyService.resolvePolicy(anyString())).thenReturn(policy);

            CampaignEntity campaign = campaign(new BigDecimal("0.30"), new BigDecimal("100.00"));
            // ACoS = 35/100 = 0.35, threshold = 0.30 * 1.20 = 0.36 → within tolerance
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("35"), new BigDecimal("100")));
            SafetyBoundary boundary = defaultBoundary();

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 60);

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason()).isEqualTo("NO_CHANGE");
        }

        @Test
        @DisplayName("Decrease is bounded by maxDailyBudgetDecreaseRatio")
        void decreaseBoundedByMaxRatio() {
            PersonalityPolicyEntity policy = defaultPolicy();
            policy.setAcosToleranceRatio(BigDecimal.ZERO);
            when(personalityPolicyService.resolvePolicy(anyString())).thenReturn(policy);

            CampaignEntity campaign = campaign(new BigDecimal("0.10"), new BigDecimal("100.00"));
            // ACoS = 80/100 = 0.80, far above target 0.10 → wants a large decrease
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("80"), new BigDecimal("100")));
            // maxDecreaseRatio = 0.15 (15%)
            SafetyBoundary boundary = boundaryWith(
                    SafetyBoundaryLimit.MAX_DAILY_BUDGET_DECREASE_RATIO, new BigDecimal("0.15"));

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 60);

            assertThat(result.isSkipped()).isFalse();
            CandidateDecision candidate = result.candidates().get(0);
            BigDecimal minAllowed = new BigDecimal("100.00").multiply(
                    BigDecimal.ONE.subtract(new BigDecimal("0.15")));
            // Proposed should not go below 85.00 (current * (1 - 0.15))
            assertThat(candidate.proposedValue()).isGreaterThanOrEqualTo(minAllowed);
        }
    }

    // =========================================================================
    // Safety boundary clamping (Req 4.5)
    // =========================================================================

    @Nested
    @DisplayName("Safety boundary clamping (Req 4.5)")
    class SafetyBoundaryClamping {

        @Test
        @DisplayName("Clamps proposed budget to minDailyBudget")
        void clampsToMinDailyBudget() {
            BigDecimal proposed = new BigDecimal("5.00");
            SafetyBoundary boundary = boundaryWith(
                    SafetyBoundaryLimit.MIN_DAILY_BUDGET, new BigDecimal("10.00"));

            BigDecimal result = engine.clampToSafetyBoundary(proposed, boundary);
            assertThat(result).isEqualByComparingTo(new BigDecimal("10.00"));
        }

        @Test
        @DisplayName("Clamps proposed budget to maxDailyBudget")
        void clampsToMaxDailyBudget() {
            BigDecimal proposed = new BigDecimal("500.00");
            SafetyBoundary boundary = boundaryWith(
                    SafetyBoundaryLimit.MAX_DAILY_BUDGET, new BigDecimal("200.00"));

            BigDecimal result = engine.clampToSafetyBoundary(proposed, boundary);
            assertThat(result).isEqualByComparingTo(new BigDecimal("200.00"));
        }

        @Test
        @DisplayName("Does not clamp when within bounds")
        void doesNotClampWithinBounds() {
            BigDecimal proposed = new BigDecimal("50.00");
            SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                    .minDailyBudget(new BigDecimal("10.00"))
                    .maxDailyBudget(new BigDecimal("200.00"))
                    .build();
            SafetyBoundary boundary = SafetyBoundaryResolver.resolve(null, null, null, limits);

            BigDecimal result = engine.clampToSafetyBoundary(proposed, boundary);
            assertThat(result).isEqualByComparingTo(new BigDecimal("50.00"));
        }
    }

    // =========================================================================
    // Cooldown enforcement (Req 4.6)
    // =========================================================================

    @Nested
    @DisplayName("Cooldown enforcement (Req 4.6)")
    class CooldownEnforcement {

        @Test
        @DisplayName("Skips campaign within cooldown window")
        void skipsCampaignWithinCooldown() {
            PersonalityPolicyEntity policy = defaultPolicy();
            policy.setAdjustmentCooldownHours(24);
            when(personalityPolicyService.resolvePolicy(anyString())).thenReturn(policy);

            // Mock a recent budget operation
            OperationEntity recentOp = OperationEntity.builder()
                    .id(UUID.randomUUID())
                    .createdAt(LocalDateTime.now().minusHours(6))
                    .build();
            when(operationMapper.selectOne(any())).thenReturn(recentOp);

            CampaignEntity campaign = campaign(new BigDecimal("0.30"), new BigDecimal("100.00"));
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("15"), new BigDecimal("100")));
            SafetyBoundary boundary = defaultBoundary();

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 60);

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason()).isEqualTo("COOLDOWN");
        }

        @Test
        @DisplayName("Does not skip when past cooldown window")
        void doesNotSkipPastCooldown() {
            PersonalityPolicyEntity policy = defaultPolicy();
            policy.setAdjustmentCooldownHours(24);
            when(personalityPolicyService.resolvePolicy(anyString())).thenReturn(policy);

            // No recent operations
            when(operationMapper.selectOne(any())).thenReturn(null);

            CampaignEntity campaign = campaign(new BigDecimal("0.40"), new BigDecimal("100.00"));
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("20"), new BigDecimal("100")));
            SafetyBoundary boundary = defaultBoundary();

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 60);

            assertThat(result.isSkipped()).isFalse();
        }

        @Test
        @DisplayName("isWithinCooldown returns true when recent op exists")
        void isWithinCooldownReturnsTrue() {
            OperationEntity recentOp = OperationEntity.builder()
                    .id(UUID.randomUUID())
                    .createdAt(LocalDateTime.now().minusHours(2))
                    .build();
            when(operationMapper.selectOne(any())).thenReturn(recentOp);

            CampaignEntity campaign = campaign(new BigDecimal("0.30"), new BigDecimal("100.00"));
            assertThat(engine.isWithinCooldown(campaign, 24)).isTrue();
        }

        @Test
        @DisplayName("isWithinCooldown returns false when no recent op")
        void isWithinCooldownReturnsFalse() {
            when(operationMapper.selectOne(any())).thenReturn(null);

            CampaignEntity campaign = campaign(new BigDecimal("0.30"), new BigDecimal("100.00"));
            assertThat(engine.isWithinCooldown(campaign, 24)).isFalse();
        }
    }

    // =========================================================================
    // ACoS computation from DataSnapshot (Req 4.1)
    // =========================================================================

    @Nested
    @DisplayName("Actual ACoS computation (Req 4.1)")
    class ActualAcosComputation {

        @Test
        @DisplayName("Computes ACoS from campaign performance data")
        void computesAcosFromPerformanceData() {
            // Total spend = 30, total sales = 100 → ACoS = 0.30
            List<PerformanceDailyEntity> data = List.of(
                    perfRow(new BigDecimal("10"), new BigDecimal("40")),
                    perfRow(new BigDecimal("20"), new BigDecimal("60"))
            );
            DataSnapshot snapshot = snapshotWithPerformance(data);

            BigDecimal acos = engine.computeActualAcos(CAMPAIGN_ID, snapshot, 14);
            assertThat(acos).isEqualByComparingTo(new BigDecimal("0.3"));
        }

        @Test
        @DisplayName("Returns null when no performance data exists")
        void returnsNullWhenNoData() {
            DataSnapshot snapshot = emptySnapshot();

            BigDecimal acos = engine.computeActualAcos(CAMPAIGN_ID, snapshot, 14);
            assertThat(acos).isNull();
        }

        @Test
        @DisplayName("Returns high sentinel when spend but no sales")
        void returnsHighSentinelWhenNoSales() {
            List<PerformanceDailyEntity> data = List.of(
                    perfRow(new BigDecimal("50"), BigDecimal.ZERO)
            );
            DataSnapshot snapshot = snapshotWithPerformance(data);

            BigDecimal acos = engine.computeActualAcos(CAMPAIGN_ID, snapshot, 14);
            assertThat(acos).isEqualByComparingTo(new BigDecimal("99999"));
        }

        @Test
        @DisplayName("Returns null when zero spend and zero sales")
        void returnsNullWhenZeroSpendAndSales() {
            List<PerformanceDailyEntity> data = List.of(
                    perfRow(BigDecimal.ZERO, BigDecimal.ZERO)
            );
            DataSnapshot snapshot = snapshotWithPerformance(data);

            BigDecimal acos = engine.computeActualAcos(CAMPAIGN_ID, snapshot, 14);
            assertThat(acos).isNull();
        }
    }

    // =========================================================================
    // Spend velocity computation
    // =========================================================================

    @Nested
    @DisplayName("Spend velocity computation")
    class SpendVelocity {

        @Test
        @DisplayName("Computes average daily spend from performance data")
        void computesAverageDailySpend() {
            List<PerformanceDailyEntity> data = List.of(
                    perfRow(new BigDecimal("30"), new BigDecimal("100")),
                    perfRow(new BigDecimal("20"), new BigDecimal("80")),
                    perfRow(new BigDecimal("10"), new BigDecimal("50"))
            );
            DataSnapshot snapshot = snapshotWithPerformance(data);

            BigDecimal velocity = engine.computeSpendVelocity(CAMPAIGN_ID, snapshot, 14);
            // Average = (30 + 20 + 10) / 3 = 20
            assertThat(velocity).isEqualByComparingTo(new BigDecimal("20"));
        }

        @Test
        @DisplayName("Returns zero when no spend data")
        void returnsZeroWhenNoSpend() {
            DataSnapshot snapshot = emptySnapshot();

            BigDecimal velocity = engine.computeSpendVelocity(CAMPAIGN_ID, snapshot, 14);
            assertThat(velocity).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // =========================================================================
    // CandidateDecision emission (Req 4.7)
    // =========================================================================

    @Nested
    @DisplayName("CandidateDecision emission (Req 4.7)")
    class CandidateEmission {

        @Test
        @DisplayName("Emits candidate with ENGINE_V2_BUDGET and correct fields")
        void emitsCandidateWithCorrectFields() {
            CampaignEntity campaign = campaign(new BigDecimal("0.40"), new BigDecimal("100.00"));
            // ACoS = 20/100 = 0.20, below target → increase
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("20"), new BigDecimal("100")));
            SafetyBoundary boundary = defaultBoundary();

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 60);

            assertThat(result.candidates()).hasSize(1);
            CandidateDecision candidate = result.candidates().get(0);

            assertThat(candidate.engineType()).isEqualTo(CandidateDecision.ENGINE_V2_BUDGET);
            assertThat(candidate.adjustmentType()).isEqualTo(HostingAdjustmentType.BUDGET);
            assertThat(candidate.changeType()).isEqualTo("budget");
            assertThat(candidate.entityType()).isEqualTo("campaign");
            assertThat(candidate.entityId()).isEqualTo(CAMPAIGN_ID);
            assertThat(candidate.field()).isEqualTo("daily_budget");
            assertThat(candidate.storeId()).isEqualTo(STORE_ID);
            assertThat(candidate.campaignId()).isEqualTo(CAMPAIGN_ID);
            assertThat(candidate.beforeValue()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(candidate.riskScore()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
            assertThat(candidate.decisionSnapshot()).isNotNull().contains("V2_BUDGET");
        }

        @Test
        @DisplayName("DecisionSnapshot JSON contains metric inputs and boundaries")
        void decisionSnapshotContainsMetrics() {
            CampaignEntity campaign = campaign(new BigDecimal("0.40"), new BigDecimal("100.00"));
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("20"), new BigDecimal("100")));
            SafetyBoundary boundary = defaultBoundary();

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 60);

            CandidateDecision candidate = result.candidates().get(0);
            String json = candidate.decisionSnapshot();
            assertThat(json).contains("\"actualAcos\"");
            assertThat(json).contains("\"targetAcos\"");
            assertThat(json).contains("\"currentBudget\"");
            assertThat(json).contains("\"proposedBudget\"");
            assertThat(json).contains("\"riskScore\"");
            assertThat(json).contains("\"direction\"");
        }
    }

    // =========================================================================
    // evaluate() convenience method
    // =========================================================================

    @Nested
    @DisplayName("evaluate() convenience method")
    class EvaluateConvenience {

        @Test
        @DisplayName("evaluate() returns candidates list from evaluateWithReason()")
        void evaluateReturnsCandidates() {
            CampaignEntity campaign = campaign(new BigDecimal("0.40"), new BigDecimal("100.00"));
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("20"), new BigDecimal("100")));
            SafetyBoundary boundary = defaultBoundary();

            List<CandidateDecision> candidates = engine.evaluate(campaign, snapshot, boundary, 60);

            assertThat(candidates).hasSize(1);
        }

        @Test
        @DisplayName("evaluate() returns empty list when skipped")
        void evaluateReturnsEmptyWhenSkipped() {
            CampaignEntity campaign = campaign(null, new BigDecimal("100.00"));
            DataSnapshot snapshot = emptySnapshot();
            SafetyBoundary boundary = defaultBoundary();

            List<CandidateDecision> candidates = engine.evaluate(campaign, snapshot, boundary, 60);

            assertThat(candidates).isEmpty();
        }
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Skips when campaign has no budget set")
        void skipsNoBudget() {
            CampaignEntity campaign = campaign(new BigDecimal("0.30"), null);
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("20"), new BigDecimal("100")));
            SafetyBoundary boundary = defaultBoundary();

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 60);

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason()).isEqualTo("NO_DATA");
        }

        @Test
        @DisplayName("Skips when proposed equals current after clamping")
        void skipsWhenNoEffectiveChange() {
            // Set up a situation where ACoS is slightly above target but decrease is so small
            // it rounds to same value after boundary clamping
            PersonalityPolicyEntity policy = defaultPolicy();
            policy.setAcosToleranceRatio(BigDecimal.ZERO);
            when(personalityPolicyService.resolvePolicy(anyString())).thenReturn(policy);

            CampaignEntity campaign = campaign(new BigDecimal("0.30"), new BigDecimal("10.00"));
            // ACoS = 31/100 = 0.31, just barely above target
            DataSnapshot snapshot = snapshotWithPerformance(performanceData(
                    new BigDecimal("31"), new BigDecimal("100")));
            // minBudget = 10, so even a tiny decrease gets clamped back up
            SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                    .minDailyBudget(new BigDecimal("10.00"))
                    .maxDailyBudget(new BigDecimal("200.00"))
                    .maxDailyBudgetDecreaseRatio(new BigDecimal("0.30"))
                    .inventoryHealthyDays(new BigDecimal("30"))
                    .build();
            SafetyBoundary boundary = SafetyBoundaryResolver.resolve(null, null, null, limits);

            V2BudgetEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, boundary, 60);

            // The tiny decrease from 10.00 clamped back to minDailyBudget=10.00 → no change
            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason()).isEqualTo("NO_CHANGE");
        }

        @Test
        @DisplayName("Data confidence is computed proportionally")
        void dataConfidenceIsProportional() {
            // 7 days of data out of 14 lookback → confidence ~ 0.5
            List<PerformanceDailyEntity> data = List.of(
                    perfRow(new BigDecimal("10"), new BigDecimal("50")),
                    perfRow(new BigDecimal("10"), new BigDecimal("50")),
                    perfRow(new BigDecimal("10"), new BigDecimal("50")),
                    perfRow(new BigDecimal("10"), new BigDecimal("50")),
                    perfRow(new BigDecimal("10"), new BigDecimal("50")),
                    perfRow(new BigDecimal("10"), new BigDecimal("50")),
                    perfRow(new BigDecimal("10"), new BigDecimal("50"))
            );
            DataSnapshot snapshot = snapshotWithPerformance(data);

            BigDecimal confidence = engine.computeDataConfidence(CAMPAIGN_ID, snapshot, 14);
            assertThat(confidence).isEqualByComparingTo(new BigDecimal("0.5"));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private CampaignEntity campaign(BigDecimal targetAcos, BigDecimal budget) {
        CampaignEntity c = new CampaignEntity();
        c.setId(CAMPAIGN_ID);
        c.setStoreId(STORE_ID);
        c.setTargetAcos(targetAcos);
        c.setBudget(budget);
        return c;
    }

    private PerformanceDailyEntity perfRow(BigDecimal spend, BigDecimal sales) {
        return PerformanceDailyEntity.builder()
                .id(UUID.randomUUID())
                .storeId(STORE_ID)
                .campaignId(CAMPAIGN_ID)
                .entityType("campaign")
                .entityId(CAMPAIGN_ID)
                .date(LocalDate.now().minusDays(1))
                .spend(spend)
                .sales(sales)
                .clicks(10)
                .impressions(100L)
                .build();
    }

    private List<PerformanceDailyEntity> performanceData(BigDecimal spend, BigDecimal sales) {
        return List.of(
                perfRow(spend, sales)
        );
    }

    private PersonalityPolicyEntity defaultPolicy() {
        return PersonalityPolicyEntity.builder()
                .id(UUID.randomUUID())
                .scope("system")
                .personality("balanced")
                .ruleVersion("v1.0")
                .lookbackDays(14)
                .adjustmentCooldownHours(0)
                .acosToleranceRatio(BigDecimal.ZERO)
                .maxDailyBudgetIncreaseRatio(new BigDecimal("0.20"))
                .build();
    }

    private DataSnapshot emptySnapshot() {
        return new DataSnapshot(
                Instant.now(), STORE_ID, 14, "Asia/Shanghai", "CNY",
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
    }

    private DataSnapshot snapshotWithPerformance(List<PerformanceDailyEntity> perfData) {
        return new DataSnapshot(
                Instant.now(), STORE_ID, 14, "Asia/Shanghai", "CNY",
                perfData, Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
    }

    private SafetyBoundary defaultBoundary() {
        SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                .minDailyBudget(new BigDecimal("5.00"))
                .maxDailyBudget(new BigDecimal("10000.00"))
                .maxDailyBudgetIncreaseRatio(new BigDecimal("0.20"))
                .maxDailyBudgetDecreaseRatio(new BigDecimal("0.30"))
                .inventoryHealthyDays(new BigDecimal("30"))
                .build();
        return SafetyBoundaryResolver.resolve(null, null, null, limits);
    }

    private SafetyBoundary boundaryWith(SafetyBoundaryLimit limit, BigDecimal value) {
        SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                .minDailyBudget(new BigDecimal("5.00"))
                .maxDailyBudget(new BigDecimal("10000.00"))
                .maxDailyBudgetIncreaseRatio(new BigDecimal("0.20"))
                .maxDailyBudgetDecreaseRatio(new BigDecimal("0.30"))
                .inventoryHealthyDays(new BigDecimal("30"))
                .limit(limit, value)
                .build();
        return SafetyBoundaryResolver.resolve(null, null, null, limits);
    }
}
