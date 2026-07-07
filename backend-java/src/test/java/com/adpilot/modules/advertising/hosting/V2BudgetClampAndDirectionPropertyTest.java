package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.service.AiPersonality;
import com.adpilot.modules.advertising.service.PersonalityPolicyService;
import com.adpilot.modules.advertising.service.PersonalityResolver;
import com.adpilot.modules.advertising.support.RiskScoreCalculator;
import com.adpilot.modules.advertising.support.RiskScoreInput;
import com.adpilot.modules.advertising.support.RiskScoreResult;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryResolver;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based test for the V2 Budget Engine's budget clamp and direction logic.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 25: V2 budget clamp and direction
 *
 * <p><b>Validates: Requirements 4.2, 4.3, 4.4, 4.5, 25.2</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>When actual ACoS &lt; target AND inventory &gt; inventoryHealthyDays, proposed budget &gt;= current (increase direction)</li>
 *   <li>When actual ACoS &gt; target * (1 + tolerance), proposed budget &lt;= current (decrease direction)</li>
 *   <li>The proposed budget is always &gt;= minDailyBudget</li>
 *   <li>The proposed budget is always &lt;= maxDailyBudget</li>
 *   <li>The increase never exceeds maxDailyBudgetIncreaseRatio × current budget</li>
 *   <li>The decrease never exceeds maxDailyBudgetDecreaseRatio × current budget</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 25: V2 budget clamp and direction")
class V2BudgetClampAndDirectionPropertyTest {

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final int MIN_ITERATIONS = 200;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CAMPAIGN_ID = UUID.randomUUID();

    // ── Engine factory ──────────────────────────────────────────────────────────

    /**
     * Build a V2BudgetEngineImpl with mocked dependencies configured to pass through
     * phase, cooldown, and learning-period checks, allowing the engine to exercise
     * its core budget computation and clamping logic.
     */
    private V2BudgetEngineImpl buildEngine(PersonalityPolicyEntity policy) {
        PersonalityResolver personalityResolver = mock(PersonalityResolver.class);
        when(personalityResolver.resolveForCampaign(any(CampaignEntity.class))).thenReturn(AiPersonality.BALANCED);

        PersonalityPolicyService personalityPolicyService = mock(PersonalityPolicyService.class);
        when(personalityPolicyService.resolvePolicy(any())).thenReturn(policy);

        RiskScoreCalculator riskScoreCalculator = mock(RiskScoreCalculator.class);
        when(riskScoreCalculator.calculate(any(RiskScoreInput.class)))
                .thenReturn(new RiskScoreResult(new BigDecimal("0.3"), "v1"));

        OperationMapper operationMapper = mock(OperationMapper.class);
        when(operationMapper.selectList(any())).thenReturn(Collections.emptyList());

        LearningPeriodService learningPeriodService = mock(LearningPeriodService.class);
        when(learningPeriodService.shouldBlockBudgetChanges(any())).thenReturn(false);

        V2BudgetEngineImpl engine = new V2BudgetEngineImpl(
                personalityResolver, personalityPolicyService,
                riskScoreCalculator, operationMapper, learningPeriodService);

        // Set phase to V2 so the engine runs
        try {
            var field = V2BudgetEngineImpl.class.getDeclaredField("phaseConfig");
            field.setAccessible(true);
            field.set(engine, "V2");
        } catch (Exception e) {
            throw new RuntimeException("Failed to set phaseConfig", e);
        }

        return engine;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private CampaignEntity campaign(BigDecimal targetAcos, BigDecimal budget) {
        CampaignEntity c = new CampaignEntity();
        c.setId(CAMPAIGN_ID);
        c.setStoreId(STORE_ID);
        c.setTargetAcos(targetAcos);
        c.setBudget(budget);
        return c;
    }

    private DataSnapshot snapshotWithAcos(BigDecimal spend, BigDecimal sales) {
        PerformanceDailyEntity row = PerformanceDailyEntity.builder()
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
        return new DataSnapshot(
                Instant.now(), STORE_ID, 14, "Asia/Shanghai", "CNY",
                List.of(row), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
    }

    private PersonalityPolicyEntity policy(BigDecimal acosToleranceRatio,
                                           BigDecimal maxIncreaseRatio) {
        return PersonalityPolicyEntity.builder()
                .id(UUID.randomUUID())
                .scope("system")
                .personality("balanced")
                .ruleVersion("v1.0")
                .lookbackDays(14)
                .adjustmentCooldownHours(0)
                .acosToleranceRatio(acosToleranceRatio)
                .maxDailyBudgetIncreaseRatio(maxIncreaseRatio)
                .build();
    }

    private SafetyBoundary boundary(BigDecimal minBudget, BigDecimal maxBudget,
                                    BigDecimal maxIncreaseRatio, BigDecimal maxDecreaseRatio,
                                    BigDecimal inventoryHealthyDays) {
        SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                .minDailyBudget(minBudget)
                .maxDailyBudget(maxBudget)
                .maxDailyBudgetIncreaseRatio(maxIncreaseRatio)
                .maxDailyBudgetDecreaseRatio(maxDecreaseRatio)
                .inventoryHealthyDays(inventoryHealthyDays)
                .build();
        return SafetyBoundaryResolver.resolve(null, null, null, limits);
    }

    // ── Properties ──────────────────────────────────────────────────────────────

    /**
     * Property 1: When actual ACoS &lt; target AND inventory &gt; inventoryHealthyDays,
     * the proposed budget is always &gt;= current budget (increase direction).
     *
     * <p>Validates: Requirement 4.2
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Req 4.2: ACoS below target + healthy inventory → proposed >= current")
    void budgetIncreasesWhenAcosBelowTargetAndHealthyInventory(
            @ForAll("currentBudgets") BigDecimal currentBudget,
            @ForAll("targetAcosValues") BigDecimal targetAcos,
            @ForAll("belowTargetAcosRatios") BigDecimal belowTargetRatio,
            @ForAll("maxIncreaseRatios") BigDecimal maxIncreaseRatio,
            @ForAll("inventoryHealthyDays") int inventoryHealthyDays,
            @ForAll("inventoryAboveHealthy") int inventoryAbove) {

        // Actual ACoS is below target
        BigDecimal actualAcos = targetAcos.multiply(belowTargetRatio, MC);
        // Inventory is above healthy days
        int inventoryDays = inventoryHealthyDays + inventoryAbove;

        // Derive spend/sales so that ACoS = spend/sales = actualAcos
        // spend = actualAcos * sales; let sales = 1000
        BigDecimal sales = new BigDecimal("1000");
        BigDecimal spend = actualAcos.multiply(sales, MC);

        BigDecimal acosToleranceRatio = new BigDecimal("0.10");
        BigDecimal minBudget = new BigDecimal("1.00");
        BigDecimal maxBudget = new BigDecimal("100000.00");
        BigDecimal maxDecreaseRatio = new BigDecimal("0.30");

        PersonalityPolicyEntity pol = policy(acosToleranceRatio, maxIncreaseRatio);
        SafetyBoundary sb = boundary(minBudget, maxBudget, maxIncreaseRatio, maxDecreaseRatio,
                new BigDecimal(inventoryHealthyDays));

        V2BudgetEngineImpl engine = buildEngine(pol);

        BigDecimal proposed = engine.computeProposedBudget(
                currentBudget, actualAcos, targetAcos, acosToleranceRatio,
                null, inventoryDays, sb, pol);

        // Either no change (null) means budget stays same, or proposed >= current
        if (proposed != null) {
            assertThat(proposed).isGreaterThanOrEqualTo(currentBudget);
        }
    }

    /**
     * Property 2: When actual ACoS &gt; target * (1 + tolerance), the proposed
     * budget is always &lt;= current budget (decrease direction).
     *
     * <p>Validates: Requirement 4.3
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Req 4.3: ACoS above threshold → proposed <= current")
    void budgetDecreasesWhenAcosAboveThreshold(
            @ForAll("currentBudgets") BigDecimal currentBudget,
            @ForAll("targetAcosValues") BigDecimal targetAcos,
            @ForAll("toleranceRatios") BigDecimal acosToleranceRatio,
            @ForAll("aboveThresholdMultipliers") BigDecimal aboveMultiplier,
            @ForAll("maxDecreaseRatios") BigDecimal maxDecreaseRatio) {

        // Actual ACoS is above threshold = target * (1 + tolerance)
        BigDecimal threshold = targetAcos.multiply(BigDecimal.ONE.add(acosToleranceRatio, MC), MC);
        BigDecimal actualAcos = threshold.multiply(aboveMultiplier, MC);

        BigDecimal sales = new BigDecimal("1000");
        BigDecimal spend = actualAcos.multiply(sales, MC);

        BigDecimal maxIncreaseRatio = new BigDecimal("0.20");
        BigDecimal minBudget = new BigDecimal("1.00");
        BigDecimal maxBudget = new BigDecimal("100000.00");
        int inventoryDays = 60; // Healthy, not relevant to decrease

        PersonalityPolicyEntity pol = policy(acosToleranceRatio, maxIncreaseRatio);
        SafetyBoundary sb = boundary(minBudget, maxBudget, maxIncreaseRatio, maxDecreaseRatio,
                new BigDecimal("30"));

        V2BudgetEngineImpl engine = buildEngine(pol);

        BigDecimal proposed = engine.computeProposedBudget(
                currentBudget, actualAcos, targetAcos, acosToleranceRatio,
                null, inventoryDays, sb, pol);

        // Either no change (null), or proposed <= current
        if (proposed != null) {
            assertThat(proposed).isLessThanOrEqualTo(currentBudget);
        }
    }

    /**
     * Property 3: The proposed budget (after clamping) is always &gt;= minDailyBudget.
     *
     * <p>Validates: Requirement 4.5
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Req 4.5: Proposed budget after clamping is always >= minDailyBudget")
    void proposedBudgetNeverBelowMin(
            @ForAll("currentBudgets") BigDecimal currentBudget,
            @ForAll("targetAcosValues") BigDecimal targetAcos,
            @ForAll("toleranceRatios") BigDecimal acosToleranceRatio,
            @ForAll("aboveThresholdMultipliers") BigDecimal aboveMultiplier,
            @ForAll("minBudgets") BigDecimal minBudget,
            @ForAll("maxDecreaseRatios") BigDecimal maxDecreaseRatio) {

        // Force a decrease scenario: actualAcos well above threshold
        BigDecimal threshold = targetAcos.multiply(BigDecimal.ONE.add(acosToleranceRatio, MC), MC);
        BigDecimal actualAcos = threshold.multiply(aboveMultiplier, MC);

        BigDecimal maxIncreaseRatio = new BigDecimal("0.20");
        BigDecimal maxBudget = new BigDecimal("100000.00");

        PersonalityPolicyEntity pol = policy(acosToleranceRatio, maxIncreaseRatio);
        SafetyBoundary sb = boundary(minBudget, maxBudget, maxIncreaseRatio, maxDecreaseRatio,
                new BigDecimal("30"));

        V2BudgetEngineImpl engine = buildEngine(pol);

        BigDecimal proposed = engine.computeProposedBudget(
                currentBudget, actualAcos, targetAcos, acosToleranceRatio,
                null, 60, sb, pol);

        if (proposed != null) {
            BigDecimal clamped = engine.clampToSafetyBoundary(proposed, sb);
            assertThat(clamped).isGreaterThanOrEqualTo(minBudget);
        }
    }

    /**
     * Property 4: The proposed budget (after clamping) is always &lt;= maxDailyBudget.
     *
     * <p>Validates: Requirement 4.5
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Req 4.5: Proposed budget after clamping is always <= maxDailyBudget")
    void proposedBudgetNeverAboveMax(
            @ForAll("currentBudgets") BigDecimal currentBudget,
            @ForAll("targetAcosValues") BigDecimal targetAcos,
            @ForAll("belowTargetAcosRatios") BigDecimal belowTargetRatio,
            @ForAll("maxIncreaseRatios") BigDecimal maxIncreaseRatio,
            @ForAll("maxBudgets") BigDecimal maxBudget,
            @ForAll("inventoryHealthyDays") int inventoryHealthyDays) {

        // Force an increase scenario: actualAcos below target with healthy inventory
        BigDecimal actualAcos = targetAcos.multiply(belowTargetRatio, MC);
        int inventoryDays = inventoryHealthyDays + 10;

        BigDecimal acosToleranceRatio = new BigDecimal("0.10");
        BigDecimal minBudget = new BigDecimal("1.00");
        BigDecimal maxDecreaseRatio = new BigDecimal("0.30");

        PersonalityPolicyEntity pol = policy(acosToleranceRatio, maxIncreaseRatio);
        SafetyBoundary sb = boundary(minBudget, maxBudget, maxIncreaseRatio, maxDecreaseRatio,
                new BigDecimal(inventoryHealthyDays));

        V2BudgetEngineImpl engine = buildEngine(pol);

        BigDecimal proposed = engine.computeProposedBudget(
                currentBudget, actualAcos, targetAcos, acosToleranceRatio,
                null, inventoryDays, sb, pol);

        if (proposed != null) {
            BigDecimal clamped = engine.clampToSafetyBoundary(proposed, sb);
            assertThat(clamped).isLessThanOrEqualTo(maxBudget);
        }
    }

    /**
     * Property 5: The increase never exceeds maxDailyBudgetIncreaseRatio × current budget.
     *
     * <p>Validates: Requirement 4.5, 25.2
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Req 4.5/25.2: Increase never exceeds maxDailyBudgetIncreaseRatio × current")
    void increaseNeverExceedsMaxRatio(
            @ForAll("currentBudgets") BigDecimal currentBudget,
            @ForAll("targetAcosValues") BigDecimal targetAcos,
            @ForAll("belowTargetAcosRatios") BigDecimal belowTargetRatio,
            @ForAll("maxIncreaseRatios") BigDecimal maxIncreaseRatio,
            @ForAll("inventoryHealthyDays") int inventoryHealthyDays,
            @ForAll("inventoryAboveHealthy") int inventoryAbove) {

        BigDecimal actualAcos = targetAcos.multiply(belowTargetRatio, MC);
        int inventoryDays = inventoryHealthyDays + inventoryAbove;

        BigDecimal acosToleranceRatio = new BigDecimal("0.10");
        BigDecimal minBudget = new BigDecimal("1.00");
        BigDecimal maxBudget = new BigDecimal("100000.00");
        BigDecimal maxDecreaseRatio = new BigDecimal("0.30");

        PersonalityPolicyEntity pol = policy(acosToleranceRatio, maxIncreaseRatio);
        SafetyBoundary sb = boundary(minBudget, maxBudget, maxIncreaseRatio, maxDecreaseRatio,
                new BigDecimal(inventoryHealthyDays));

        V2BudgetEngineImpl engine = buildEngine(pol);

        BigDecimal proposed = engine.computeProposedBudget(
                currentBudget, actualAcos, targetAcos, acosToleranceRatio,
                null, inventoryDays, sb, pol);

        if (proposed != null && proposed.compareTo(currentBudget) > 0) {
            BigDecimal maxAllowed = currentBudget.multiply(
                    BigDecimal.ONE.add(maxIncreaseRatio, MC), MC)
                    .setScale(2, RoundingMode.HALF_UP);
            assertThat(proposed).isLessThanOrEqualTo(maxAllowed);
        }
    }

    /**
     * Property 6: The decrease never exceeds maxDailyBudgetDecreaseRatio × current budget.
     *
     * <p>Validates: Requirement 4.5, 25.2
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Req 4.5/25.2: Decrease never exceeds maxDailyBudgetDecreaseRatio × current")
    void decreaseNeverExceedsMaxRatio(
            @ForAll("currentBudgets") BigDecimal currentBudget,
            @ForAll("targetAcosValues") BigDecimal targetAcos,
            @ForAll("toleranceRatios") BigDecimal acosToleranceRatio,
            @ForAll("aboveThresholdMultipliers") BigDecimal aboveMultiplier,
            @ForAll("maxDecreaseRatios") BigDecimal maxDecreaseRatio) {

        BigDecimal threshold = targetAcos.multiply(BigDecimal.ONE.add(acosToleranceRatio, MC), MC);
        BigDecimal actualAcos = threshold.multiply(aboveMultiplier, MC);

        BigDecimal maxIncreaseRatio = new BigDecimal("0.20");
        BigDecimal minBudget = new BigDecimal("1.00");
        BigDecimal maxBudget = new BigDecimal("100000.00");

        PersonalityPolicyEntity pol = policy(acosToleranceRatio, maxIncreaseRatio);
        SafetyBoundary sb = boundary(minBudget, maxBudget, maxIncreaseRatio, maxDecreaseRatio,
                new BigDecimal("30"));

        V2BudgetEngineImpl engine = buildEngine(pol);

        BigDecimal proposed = engine.computeProposedBudget(
                currentBudget, actualAcos, targetAcos, acosToleranceRatio,
                null, 60, sb, pol);

        if (proposed != null && proposed.compareTo(currentBudget) < 0) {
            BigDecimal minAllowed = currentBudget.multiply(
                    BigDecimal.ONE.subtract(maxDecreaseRatio, MC), MC)
                    .setScale(2, RoundingMode.HALF_UP);
            assertThat(proposed).isGreaterThanOrEqualTo(minAllowed);
        }
    }

    // ── Arbitraries ─────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<BigDecimal> currentBudgets() {
        // Reasonable daily budgets between $10 and $5000
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("10.00"), new BigDecimal("5000.00"))
                .ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> targetAcosValues() {
        // Target ACoS between 5% and 80%
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.05"), new BigDecimal("0.80"))
                .ofScale(4);
    }

    @Provide
    Arbitrary<BigDecimal> belowTargetAcosRatios() {
        // Ratio from 0.01 to 0.99 — actual ACoS is below target by this fraction
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("0.99"))
                .ofScale(4);
    }

    @Provide
    Arbitrary<BigDecimal> toleranceRatios() {
        // Tolerance ratio between 0% and 30%
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00"), new BigDecimal("0.30"))
                .ofScale(4);
    }

    @Provide
    Arbitrary<BigDecimal> aboveThresholdMultipliers() {
        // Multiplier > 1.0 (strictly above threshold) up to 3.0
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("1.01"), new BigDecimal("3.00"))
                .ofScale(4);
    }

    @Provide
    Arbitrary<BigDecimal> maxIncreaseRatios() {
        // Max budget increase ratio between 5% and 50%
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.05"), new BigDecimal("0.50"))
                .ofScale(4);
    }

    @Provide
    Arbitrary<BigDecimal> maxDecreaseRatios() {
        // Max budget decrease ratio between 5% and 50%
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.05"), new BigDecimal("0.50"))
                .ofScale(4);
    }

    @Provide
    Arbitrary<Integer> inventoryHealthyDays() {
        // Healthy days threshold between 14 and 60
        return Arbitraries.integers().between(14, 60);
    }

    @Provide
    Arbitrary<Integer> inventoryAboveHealthy() {
        // Additional inventory days above healthy threshold (1 to 90)
        return Arbitraries.integers().between(1, 90);
    }

    @Provide
    Arbitrary<BigDecimal> minBudgets() {
        // Min budget between $1 and $50
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("1.00"), new BigDecimal("50.00"))
                .ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> maxBudgets() {
        // Max budget between $500 and $50000
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("500.00"), new BigDecimal("50000.00"))
                .ofScale(2);
    }
}
