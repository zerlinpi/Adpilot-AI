package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.entity.SearchTermDailyEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.service.AiPersonality;
import com.adpilot.modules.advertising.service.HostingAdjustmentType;
import com.adpilot.modules.advertising.service.HostingPhase;
import com.adpilot.modules.advertising.service.PersonalityPolicyService;
import com.adpilot.modules.advertising.service.PersonalityResolver;
import com.adpilot.modules.advertising.support.RiskScoreCalculator;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import com.adpilot.modules.advertising.support.SafetyBoundaryLevel;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryResolver;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for AI-hosting engine phase gating.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 24: Engine phase gating
 *
 * <p><b>Validates: Requirements 4.8, 5.12, 17.2, 17.3, 17.4</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>{@link HostingPhase#supports(HostingAdjustmentType)} encodes exactly the documented
 *       capability ladder: V1→{BID}, V2→{BID,BUDGET}, V3→{BID,BUDGET,KEYWORD,NEGATIVE}.</li>
 *   <li>The V2 budget engine emits {@code PHASE_DISABLED} (and never any candidate) exactly when
 *       the active phase does not support BUDGET — i.e. only V2/V3 may produce budget candidates,
 *       regardless of input data.</li>
 *   <li>The V3 search-term engine emits {@code PHASE_DISABLED} (and never any candidate) exactly
 *       when the active phase does not support KEYWORD — i.e. only V3 may produce keyword/negative
 *       candidates, regardless of input data.</li>
 *   <li>When the phase does support the capability, favorable data actually yields candidates,
 *       proving the gate is the only thing suppressing output for disabled phases.</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 24: Engine phase gating")
class EnginePhaseGatingPropertyTest {

    private static final int MIN_ITERATIONS = 200;
    private static final String PHASE_DISABLED = "PHASE_DISABLED";

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CAMPAIGN_ID = UUID.randomUUID();
    private static final UUID AD_GROUP_ID = UUID.randomUUID();

    // ── Property 1: supports() relation correctness ─────────────────────────────

    /**
     * The {@link HostingPhase#supports(HostingAdjustmentType)} relation matches the documented
     * capability ladder for every (phase, adjustment-type) pair.
     *
     * <p>Validates: Requirements 17.2, 17.3, 17.4
     */
    @Property
    @Label("supports() encodes V1→{BID}, V2→{BID,BUDGET}, V3→all")
    void supportsRelationMatchesCapabilityLadder(
            @ForAll HostingPhase phase,
            @ForAll HostingAdjustmentType type) {

        boolean expected = switch (phase) {
            case V1 -> type == HostingAdjustmentType.BID;
            case V2 -> type == HostingAdjustmentType.BID || type == HostingAdjustmentType.BUDGET;
            case V3 -> true; // V3 supports the full set
        };

        assertThat(phase.supports(type)).isEqualTo(expected);
    }

    /**
     * Each phase's capability set is strictly a superset of the previous phase's — the ladder only
     * grows. Confirms the gate is monotonic so an upgrade never removes a capability.
     *
     * <p>Validates: Requirements 17.2, 17.3, 17.4
     */
    @Property
    @Label("Capability ladder is monotonically non-decreasing across V1→V2→V3")
    void capabilityLadderIsMonotonic(@ForAll HostingAdjustmentType type) {
        if (HostingPhase.V1.supports(type)) {
            assertThat(HostingPhase.V2.supports(type)).isTrue();
        }
        if (HostingPhase.V2.supports(type)) {
            assertThat(HostingPhase.V3.supports(type)).isTrue();
        }
    }

    // ── Property 2: V2 budget engine phase gating ───────────────────────────────

    /**
     * For any phase and any input data, the V2 budget engine skips with {@code PHASE_DISABLED}
     * (emitting no candidate) if and only if the active phase does not support BUDGET. Only V2 and
     * V3 may proceed past the gate.
     *
     * <p>Validates: Requirements 4.8, 17.2, 17.3
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Req 4.8: V2 budget engine emits PHASE_DISABLED iff phase lacks BUDGET support")
    void v2BudgetEngineGatedByPhaseForAnyData(
            @ForAll HostingPhase phase,
            @ForAll("optionalTargetAcos") BigDecimal targetAcos,
            @ForAll("optionalBudget") BigDecimal budget,
            @ForAll("amounts") BigDecimal spend,
            @ForAll("amounts") BigDecimal sales,
            @ForAll @IntRange(min = 0, max = 120) int inventoryDays) {

        V2BudgetEngineImpl engine = buildV2Engine(phase);
        CampaignEntity campaign = campaign(targetAcos, budget);
        DataSnapshot snapshot = snapshotWithPerformance(spend, sales);

        V2BudgetEngine.EvaluationResult result =
                engine.evaluateWithReason(campaign, snapshot, defaultV2Boundary(), inventoryDays);

        if (!phase.supports(HostingAdjustmentType.BUDGET)) {
            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason()).isEqualTo(PHASE_DISABLED);
            assertThat(result.candidates()).isEmpty();
        } else {
            // Gate passed: it may still skip for other reasons, but never PHASE_DISABLED.
            assertThat(result.skipReason()).isNotEqualTo(PHASE_DISABLED);
        }
    }

    /**
     * With favorable data that yields a budget candidate at the supported phases, the V2 engine
     * produces candidates exactly for V2/V3 and stays empty at V1 — proving the phase gate is the
     * sole suppressor of output below the supported phase.
     *
     * <p>Validates: Requirements 4.8, 17.2, 17.3
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Req 4.8: favorable data yields V2 candidates only when phase supports BUDGET")
    void v2BudgetEngineProducesCandidatesOnlyWhenSupported(@ForAll HostingPhase phase) {
        V2BudgetEngineImpl engine = buildV2Engine(phase);
        // target 0.30, budget 100, ACoS = 15/100 = 0.15 (< target), healthy inventory → increase
        CampaignEntity campaign = campaign(new BigDecimal("0.30"), new BigDecimal("100.00"));
        DataSnapshot snapshot = snapshotWithPerformance(new BigDecimal("15"), new BigDecimal("100"));

        V2BudgetEngine.EvaluationResult result =
                engine.evaluateWithReason(campaign, snapshot, defaultV2Boundary(), 60);

        if (phase.supports(HostingAdjustmentType.BUDGET)) {
            assertThat(result.isSkipped()).isFalse();
            assertThat(result.candidates()).isNotEmpty();
        } else {
            assertThat(result.skipReason()).isEqualTo(PHASE_DISABLED);
            assertThat(result.candidates()).isEmpty();
        }
    }

    // ── Property 3: V3 search-term engine phase gating ──────────────────────────

    /**
     * For any phase and any input data, the V3 search-term engine skips with {@code PHASE_DISABLED}
     * (emitting no candidate) if and only if the active phase does not support KEYWORD. Only V3 may
     * proceed past the gate.
     *
     * <p>Validates: Requirements 5.12, 17.2, 17.4
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Req 5.12: V3 engine emits PHASE_DISABLED iff phase lacks KEYWORD support")
    void v3SearchTermEngineGatedByPhaseForAnyData(
            @ForAll HostingPhase phase,
            @ForAll("optionalTargetAcos") BigDecimal targetAcos,
            @ForAll @IntRange(min = 0, max = 80) int clicksPerDay,
            @ForAll @IntRange(min = 0, max = 5) int ordersPerDay,
            @ForAll @IntRange(min = 1, max = 14) int days) {

        V3SearchTermEngineImpl engine = buildV3Engine(phase);
        CampaignEntity campaign = campaign(targetAcos, null);
        DataSnapshot snapshot = snapshotWithSearchTerms(
                multiDaySearchTermRows("term", clicksPerDay, ordersPerDay, "2.00", "20.00", days));

        V3SearchTermEngine.EvaluationResult result =
                engine.evaluateWithReason(campaign, snapshot, defaultV3Boundary());

        if (!phase.supports(HostingAdjustmentType.KEYWORD)) {
            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason()).isEqualTo(PHASE_DISABLED);
            assertThat(result.candidates()).isEmpty();
        } else {
            assertThat(result.skipReason()).isNotEqualTo(PHASE_DISABLED);
        }
    }

    /**
     * With favorable multi-day data that yields a keyword candidate at V3, the V3 engine produces
     * candidates exactly for V3 and stays empty at V1/V2 — proving the phase gate is the sole
     * suppressor of output below the supported phase.
     *
     * <p>Validates: Requirements 5.12, 17.2, 17.4
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Req 5.12: favorable data yields V3 candidates only when phase supports KEYWORD")
    void v3SearchTermEngineProducesCandidatesOnlyWhenSupported(@ForAll HostingPhase phase) {
        V3SearchTermEngineImpl engine = buildV3Engine(phase);
        CampaignEntity campaign = campaign(new BigDecimal("0.30"), null);
        // 10 days * 10 clicks, 1 order/day, ACoS = 2/20 = 0.10 (< target) → keyword candidate
        DataSnapshot snapshot = snapshotWithSearchTerms(
                multiDaySearchTermRows("good term", 10, 1, "2.00", "20.00", 10));

        V3SearchTermEngine.EvaluationResult result =
                engine.evaluateWithReason(campaign, snapshot, defaultV3Boundary());

        if (phase.supports(HostingAdjustmentType.KEYWORD)) {
            assertThat(result.isSkipped()).isFalse();
            assertThat(result.candidates()).isNotEmpty();
        } else {
            assertThat(result.skipReason()).isEqualTo(PHASE_DISABLED);
            assertThat(result.candidates()).isEmpty();
        }
    }

    // ── Arbitraries ─────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<BigDecimal> optionalTargetAcos() {
        // Mix of null, zero, and positive target ACoS to exercise downstream skip branches too.
        Arbitrary<BigDecimal> positive = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.05"), new BigDecimal("0.80")).ofScale(4);
        return Arbitraries.oneOf(
                positive,
                Arbitraries.just(BigDecimal.ZERO),
                Arbitraries.just(null));
    }

    @Provide
    Arbitrary<BigDecimal> optionalBudget() {
        Arbitrary<BigDecimal> positive = Arbitraries.bigDecimals()
                .between(new BigDecimal("10.00"), new BigDecimal("5000.00")).ofScale(2);
        return Arbitraries.oneOf(positive, Arbitraries.just(null));
    }

    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("1000.00")).ofScale(2);
    }

    // ── Engine factories ────────────────────────────────────────────────────────

    private V2BudgetEngineImpl buildV2Engine(HostingPhase phase) {
        PersonalityResolver personalityResolver = mock(PersonalityResolver.class);
        when(personalityResolver.resolveForCampaign(any(CampaignEntity.class)))
                .thenReturn(AiPersonality.SYSTEM_FALLBACK);

        PersonalityPolicyService personalityPolicyService = mock(PersonalityPolicyService.class);
        when(personalityPolicyService.resolvePolicy(anyString())).thenReturn(v2Policy());

        OperationMapper operationMapper = mock(OperationMapper.class);
        when(operationMapper.selectOne(any())).thenReturn(null);

        LearningPeriodService learningPeriodService = mock(LearningPeriodService.class);
        when(learningPeriodService.shouldBlockBudgetChanges(any())).thenReturn(false);

        V2BudgetEngineImpl engine = new V2BudgetEngineImpl(
                personalityResolver, personalityPolicyService,
                new RiskScoreCalculator(), operationMapper, learningPeriodService);
        ReflectionTestUtils.setField(engine, "phaseConfig", phase.name());
        return engine;
    }

    private V3SearchTermEngineImpl buildV3Engine(HostingPhase phase) {
        PersonalityResolver personalityResolver = mock(PersonalityResolver.class);
        when(personalityResolver.resolveForCampaign(any(CampaignEntity.class)))
                .thenReturn(AiPersonality.SYSTEM_FALLBACK);

        PersonalityPolicyService personalityPolicyService = mock(PersonalityPolicyService.class);
        when(personalityPolicyService.resolvePolicy(anyString())).thenReturn(v3Policy());

        BrandWordProtectionService brandWordProtectionService = mock(BrandWordProtectionService.class);
        when(brandWordProtectionService.checkNegativeCandidate(any(), anyString()))
                .thenReturn(BrandProtectionResult.allowed());

        AiDecisionMapper aiDecisionMapper = mock(AiDecisionMapper.class);
        when(aiDecisionMapper.selectCount(any())).thenReturn(0L);

        V3SearchTermEngineImpl engine = new V3SearchTermEngineImpl(
                personalityResolver, personalityPolicyService,
                new RiskScoreCalculator(), brandWordProtectionService, aiDecisionMapper);
        ReflectionTestUtils.setField(engine, "phaseConfig", phase.name());
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

    private PersonalityPolicyEntity v2Policy() {
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

    private PersonalityPolicyEntity v3Policy() {
        PersonalityPolicyEntity policy = new PersonalityPolicyEntity();
        policy.setId(UUID.randomUUID());
        policy.setScope("system");
        policy.setPersonality("balanced");
        policy.setStatus("active");
        policy.setMinClicks(0);
        policy.setMinOrders(0);
        policy.setLookbackDays(14);
        policy.setMinConversionRate(BigDecimal.ZERO);
        policy.setNegativeConfidenceThreshold(new BigDecimal("0.60"));
        policy.setAcosToleranceRatio(new BigDecimal("0.10"));
        policy.setMaxNewKeywordsPerRun(5);
        policy.setMinKeywordOrders(1);
        policy.setNegativeKeywordMinClicks(20);
        policy.setRuleVersion("v1.0");
        return policy;
    }

    private SafetyBoundary defaultV2Boundary() {
        SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                .minDailyBudget(new BigDecimal("5.00"))
                .maxDailyBudget(new BigDecimal("10000.00"))
                .maxDailyBudgetIncreaseRatio(new BigDecimal("0.20"))
                .maxDailyBudgetDecreaseRatio(new BigDecimal("0.30"))
                .inventoryHealthyDays(new BigDecimal("30"))
                .build();
        return SafetyBoundaryResolver.resolve(null, null, null, limits);
    }

    private SafetyBoundary defaultV3Boundary() {
        EnumMap<SafetyBoundaryLimit, BigDecimal> values = new EnumMap<>(SafetyBoundaryLimit.class);
        EnumMap<SafetyBoundaryLimit, SafetyBoundaryLevel> sources = new EnumMap<>(SafetyBoundaryLimit.class);
        values.put(SafetyBoundaryLimit.MAX_KEYWORDS_PER_DAY, new BigDecimal("5"));
        sources.put(SafetyBoundaryLimit.MAX_KEYWORDS_PER_DAY, SafetyBoundaryLevel.SYSTEM_DEFAULT);
        values.put(SafetyBoundaryLimit.MAX_NEGATIVES_PER_DAY, new BigDecimal("10"));
        sources.put(SafetyBoundaryLimit.MAX_NEGATIVES_PER_DAY, SafetyBoundaryLevel.SYSTEM_DEFAULT);
        return createBoundary(values, sources);
    }

    private DataSnapshot snapshotWithPerformance(BigDecimal spend, BigDecimal sales) {
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

    private List<SearchTermDailyEntity> multiDaySearchTermRows(String term, int clicksPerDay,
                                                               int ordersPerDay, String spendPerDay,
                                                               String salesPerDay, int days) {
        List<SearchTermDailyEntity> rows = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            rows.add(SearchTermDailyEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(STORE_ID)
                    .campaignId(CAMPAIGN_ID)
                    .adGroupId(AD_GROUP_ID)
                    .searchTerm(term)
                    .reportDate(LocalDate.now().minusDays(i + 1))
                    .impressions((long) clicksPerDay * 10)
                    .clicks(clicksPerDay)
                    .orders(ordersPerDay)
                    .spend(new BigDecimal(spendPerDay))
                    .sales(new BigDecimal(salesPerDay))
                    .acos(new BigDecimal(salesPerDay).compareTo(BigDecimal.ZERO) > 0
                            ? new BigDecimal(spendPerDay).divide(new BigDecimal(salesPerDay),
                            6, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                    .currency("CNY")
                    .dataStatus("finalized")
                    .dataVersion(1)
                    .build());
        }
        return rows;
    }

    private DataSnapshot snapshotWithSearchTerms(List<SearchTermDailyEntity> rows) {
        return new DataSnapshot(
                Instant.now(), STORE_ID, 14, "Asia/Shanghai", "CNY",
                Collections.emptyList(), rows,
                Collections.emptyList(), Collections.emptyList());
    }

    private SafetyBoundary createBoundary(EnumMap<SafetyBoundaryLimit, BigDecimal> values,
                                          EnumMap<SafetyBoundaryLimit, SafetyBoundaryLevel> sources) {
        try {
            var constructor = SafetyBoundary.class.getDeclaredConstructor(EnumMap.class, EnumMap.class);
            constructor.setAccessible(true);
            return constructor.newInstance(values, sources);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SafetyBoundary via reflection", e);
        }
    }
}
