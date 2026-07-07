package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;
import com.adpilot.modules.advertising.entity.SearchTermDailyEntity;
import com.adpilot.modules.advertising.service.AiPersonality;
import com.adpilot.modules.advertising.service.HostingAdjustmentType;
import com.adpilot.modules.advertising.service.PersonalityPolicyService;
import com.adpilot.modules.advertising.service.PersonalityResolver;
import com.adpilot.modules.advertising.support.RiskScoreCalculator;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import com.adpilot.modules.advertising.support.SafetyBoundaryLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
 * Unit tests for the V3SearchTermEngine (Requirement 5).
 *
 * <p>Validates: Requirements 5.1, 5.2, 5.3, 5.5, 5.6, 5.7, 5.10, 5.11, 5.12, 31.4.</p>
 */
@DisplayName("V3SearchTermEngine")
class V3SearchTermEngineTest {

    private PersonalityResolver personalityResolver;
    private PersonalityPolicyService personalityPolicyService;
    private RiskScoreCalculator riskScoreCalculator;
    private BrandWordProtectionService brandWordProtectionService;
    private AiDecisionMapper aiDecisionMapper;
    private V3SearchTermEngineImpl engine;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CAMPAIGN_ID = UUID.randomUUID();
    private static final UUID AD_GROUP_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        personalityResolver = mock(PersonalityResolver.class);
        personalityPolicyService = mock(PersonalityPolicyService.class);
        riskScoreCalculator = new RiskScoreCalculator();
        brandWordProtectionService = mock(BrandWordProtectionService.class);
        aiDecisionMapper = mock(AiDecisionMapper.class);

        engine = new V3SearchTermEngineImpl(
                personalityResolver, personalityPolicyService,
                riskScoreCalculator, brandWordProtectionService, aiDecisionMapper);

        // Set phase to V3 so the engine is active
        ReflectionTestUtils.setField(engine, "phaseConfig", "V3");

        // Default mock behavior
        when(personalityResolver.resolveForCampaign(any(CampaignEntity.class)))
                .thenReturn(AiPersonality.SYSTEM_FALLBACK);
        when(personalityPolicyService.resolvePolicy(anyString()))
                .thenReturn(defaultPolicy());
        when(brandWordProtectionService.checkNegativeCandidate(any(), anyString()))
                .thenReturn(BrandProtectionResult.allowed());
        when(aiDecisionMapper.selectCount(any())).thenReturn(0L);
    }

    // =========================================================================
    // Phase gating (Req 5.12)
    // =========================================================================

    @Nested
    @DisplayName("Phase gating (Req 5.12)")
    class PhaseGating {

        @Test
        @DisplayName("Skips when phase is V1 (does not support KEYWORD)")
        void skipsWhenPhaseIsV1() {
            ReflectionTestUtils.setField(engine, "phaseConfig", "V1");

            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            DataSnapshot snapshot = snapshotWithSearchTerms(
                    searchTermRow("good term", 50, 5, "10.00", "100.00"));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason()).isEqualTo("PHASE_DISABLED");
        }

        @Test
        @DisplayName("Skips when phase is V2 (does not support KEYWORD)")
        void skipsWhenPhaseIsV2() {
            ReflectionTestUtils.setField(engine, "phaseConfig", "V2");

            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            DataSnapshot snapshot = snapshotWithSearchTerms(
                    searchTermRow("good term", 50, 5, "10.00", "100.00"));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason()).isEqualTo("PHASE_DISABLED");
        }

        @Test
        @DisplayName("Runs when phase is V3")
        void runsWhenPhaseIsV3() {
            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            // Use multi-day data to ensure confidence > threshold
            DataSnapshot snapshot = snapshotWithSearchTermList(
                    multiDaySearchTermRows("good term", 10, 1, "2.00", "20.00", 10));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            assertThat(result.isSkipped()).isFalse();
        }
    }

    // =========================================================================
    // No target ACoS skip
    // =========================================================================

    @Nested
    @DisplayName("No target ACoS")
    class NoTargetAcos {

        @Test
        @DisplayName("Skips campaign with null target ACoS")
        void skipsNullTargetAcos() {
            CampaignEntity campaign = campaign(null);
            DataSnapshot snapshot = snapshotWithSearchTerms(
                    searchTermRow("term", 50, 5, "10.00", "100.00"));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason()).isEqualTo("NO_TARGET_ACOS");
        }

        @Test
        @DisplayName("Skips campaign with zero target ACoS")
        void skipsZeroTargetAcos() {
            CampaignEntity campaign = campaign(BigDecimal.ZERO);
            DataSnapshot snapshot = snapshotWithSearchTerms(
                    searchTermRow("term", 50, 5, "10.00", "100.00"));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason()).isEqualTo("NO_TARGET_ACOS");
        }
    }

    // =========================================================================
    // Keyword addition proposals (Req 5.2)
    // =========================================================================

    @Nested
    @DisplayName("Keyword addition proposals (Req 5.2)")
    class KeywordAddition {

        @Test
        @DisplayName("Proposes keyword when orders > 0 AND ACoS < target AND confidence > threshold")
        void proposesKeywordWhenCriteriaMet() {
            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            // Multi-day data: 10 days * 10 clicks, orders=1/day → confidence > 0.60
            // ACoS = 2/20 = 0.10, below target 0.30
            DataSnapshot snapshot = snapshotWithSearchTermList(
                    multiDaySearchTermRows("converting term", 10, 1, "2.00", "20.00", 10));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            assertThat(result.isSkipped()).isFalse();
            assertThat(result.candidates()).isNotEmpty();
            CandidateDecision candidate = result.candidates().get(0);
            assertThat(candidate.adjustmentType()).isEqualTo(HostingAdjustmentType.KEYWORD);
            assertThat(candidate.changeType()).isEqualTo("keyword");
            assertThat(candidate.engineType()).isEqualTo(CandidateDecision.ENGINE_V3_KEYWORD);
        }

        @Test
        @DisplayName("Does NOT propose keyword when orders = 0")
        void doesNotProposeWhenNoOrders() {
            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            // orders = 0 → keyword criteria not met (this might still be a negative candidate)
            DataSnapshot snapshot = snapshotWithSearchTerms(
                    searchTermRow("no orders term", 50, 0, "10.00", "0.00"));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            // Should produce negative candidates but NOT keyword candidates
            for (CandidateDecision c : result.candidates()) {
                assertThat(c.adjustmentType()).isNotEqualTo(HostingAdjustmentType.KEYWORD);
            }
        }

        @Test
        @DisplayName("Does NOT propose keyword when ACoS >= target")
        void doesNotProposeWhenAcosAboveTarget() {
            CampaignEntity campaign = campaign(new BigDecimal("0.10"));
            // ACoS = 50/100 = 0.50, above target 0.10
            DataSnapshot snapshot = snapshotWithSearchTerms(
                    searchTermRow("high acos term", 50, 3, "50.00", "100.00"));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            for (CandidateDecision c : result.candidates()) {
                assertThat(c.adjustmentType()).isNotEqualTo(HostingAdjustmentType.KEYWORD);
            }
        }
    }

    // =========================================================================
    // Negative keyword proposals (Req 5.3)
    // =========================================================================

    @Nested
    @DisplayName("Negative keyword proposals (Req 5.3)")
    class NegativeKeyword {

        @Test
        @DisplayName("Proposes negative when clicks > minClicks AND orders = 0 AND confidence > threshold")
        void proposesNegativeWhenCriteriaMet() {
            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            // 14 days * 15 clicks = 210 total (> minClicks=20), orders=0, confidence > 0.60
            DataSnapshot snapshot = snapshotWithSearchTermList(
                    multiDaySearchTermRows("wasted term", 15, 0, "5.00", "0.00", 14));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            assertThat(result.isSkipped()).isFalse();
            assertThat(result.candidates()).isNotEmpty();
            CandidateDecision negCandidate = result.candidates().stream()
                    .filter(c -> c.adjustmentType() == HostingAdjustmentType.NEGATIVE)
                    .findFirst().orElse(null);
            assertThat(negCandidate).isNotNull();
            assertThat(negCandidate.changeType()).isEqualTo("negative_keyword");
        }

        @Test
        @DisplayName("Does NOT propose negative when clicks <= minClicks")
        void doesNotProposeWhenClicksBelowMin() {
            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            // clicks = 5 (< default minClicks=20), orders = 0
            DataSnapshot snapshot = snapshotWithSearchTerms(
                    searchTermRow("low click term", 5, 0, "2.00", "0.00"));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            // No negatives should be proposed
            for (CandidateDecision c : result.candidates()) {
                assertThat(c.adjustmentType()).isNotEqualTo(HostingAdjustmentType.NEGATIVE);
            }
        }

        @Test
        @DisplayName("Does NOT propose negative when orders > 0")
        void doesNotProposeNegativeWhenHasOrders() {
            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            // clicks = 60, orders = 2 → not a negative candidate
            DataSnapshot snapshot = snapshotWithSearchTerms(
                    searchTermRow("converting term", 60, 2, "20.00", "80.00"));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            for (CandidateDecision c : result.candidates()) {
                assertThat(c.adjustmentType()).isNotEqualTo(HostingAdjustmentType.NEGATIVE);
            }
        }
    }

    // =========================================================================
    // Brand word protection (Req 5.4)
    // =========================================================================

    @Nested
    @DisplayName("Brand word protection (Req 5.4)")
    class BrandProtection {

        @Test
        @DisplayName("Rejects negative keyword that matches brand word")
        void rejectsNegativeMatchingBrandWord() {
            when(brandWordProtectionService.checkNegativeCandidate(any(), anyString()))
                    .thenReturn(BrandProtectionResult.rejected("MyBrand", "exact"));

            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            // Multi-day: 14 days * 15 clicks, orders = 0
            DataSnapshot snapshot = snapshotWithSearchTermList(
                    multiDaySearchTermRows("mybrand product", 15, 0, "5.00", "0.00", 14));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            // No negative should be produced since brand-protected
            for (CandidateDecision c : result.candidates()) {
                assertThat(c.adjustmentType()).isNotEqualTo(HostingAdjustmentType.NEGATIVE);
            }
        }

        @Test
        @DisplayName("Allows negative keyword when NOT brand-protected")
        void allowsNegativeWhenNotBrandProtected() {
            when(brandWordProtectionService.checkNegativeCandidate(any(), anyString()))
                    .thenReturn(BrandProtectionResult.allowed());

            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            // Multi-day data: 14 days * 15 clicks, orders = 0
            DataSnapshot snapshot = snapshotWithSearchTermList(
                    multiDaySearchTermRows("irrelevant term", 15, 0, "5.00", "0.00", 14));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            assertThat(result.candidates()).isNotEmpty();
            boolean hasNegative = result.candidates().stream()
                    .anyMatch(c -> c.adjustmentType() == HostingAdjustmentType.NEGATIVE);
            assertThat(hasNegative).isTrue();
        }
    }

    // =========================================================================
    // Per-day caps (Req 5.5, 5.6)
    // =========================================================================

    @Nested
    @DisplayName("Per-day caps (Req 5.5, 5.6)")
    class PerDayCaps {

        @Test
        @DisplayName("Respects maxKeywordsAddedPerDay cap")
        void respectsMaxKeywordsPerDayCap() {
            // Already added 5 keywords today (at the default max of 5)
            when(aiDecisionMapper.selectCount(any())).thenReturn(5L);

            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            DataSnapshot snapshot = snapshotWithSearchTermList(
                    multiDaySearchTermRows("good term", 10, 1, "2.00", "20.00", 10));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            // Keyword proposals should be overflowed (not emitted as candidates)
            boolean hasKeyword = result.candidates().stream()
                    .anyMatch(c -> c.adjustmentType() == HostingAdjustmentType.KEYWORD);
            assertThat(hasKeyword).isFalse();
        }

        @Test
        @DisplayName("Respects maxNegativesAddedPerDay cap")
        void respectsMaxNegativesPerDayCap() {
            // Set up to return 10 for negatives count (at default max of 10)
            when(aiDecisionMapper.selectCount(any())).thenReturn(10L);

            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            DataSnapshot snapshot = snapshotWithSearchTermList(
                    multiDaySearchTermRows("wasted term", 15, 0, "5.00", "0.00", 14));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            boolean hasNegative = result.candidates().stream()
                    .anyMatch(c -> c.adjustmentType() == HostingAdjustmentType.NEGATIVE);
            assertThat(hasNegative).isFalse();
        }
    }

    // =========================================================================
    // Keyword expansion mode (Req 5.7)
    // =========================================================================

    @Nested
    @DisplayName("Keyword expansion mode (Req 5.7)")
    class KeywordExpansionMode {

        @Test
        @DisplayName("Does not propose keywords when maxNewKeywordsPerRun is 0 (expansion off)")
        void doesNotProposeWhenExpansionOff() {
            PersonalityPolicyEntity policy = defaultPolicy();
            policy.setMaxNewKeywordsPerRun(0); // keyword expansion off
            when(personalityPolicyService.resolvePolicy(anyString())).thenReturn(policy);

            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            // This term qualifies for keyword addition: multi-day, orders > 0, ACoS < target
            DataSnapshot snapshot = snapshotWithSearchTermList(
                    multiDaySearchTermRows("good term", 10, 1, "2.00", "20.00", 10));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            // No keyword candidates should be produced
            boolean hasKeyword = result.candidates().stream()
                    .anyMatch(c -> c.adjustmentType() == HostingAdjustmentType.KEYWORD);
            assertThat(hasKeyword).isFalse();
        }

        @Test
        @DisplayName("Still proposes negatives when expansion is off")
        void stillProposesNegativesWhenExpansionOff() {
            PersonalityPolicyEntity policy = defaultPolicy();
            policy.setMaxNewKeywordsPerRun(0); // keyword expansion off
            when(personalityPolicyService.resolvePolicy(anyString())).thenReturn(policy);

            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            // 14 days * 15 clicks, orders = 0 → qualifies for negative
            DataSnapshot snapshot = snapshotWithSearchTermList(
                    multiDaySearchTermRows("wasted term", 15, 0, "5.00", "0.00", 14));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            boolean hasNegative = result.candidates().stream()
                    .anyMatch(c -> c.adjustmentType() == HostingAdjustmentType.NEGATIVE);
            assertThat(hasNegative).isTrue();
        }
    }

    // =========================================================================
    // Confidence scoring (Req 5.1)
    // =========================================================================

    @Nested
    @DisplayName("Confidence scoring (Req 5.1)")
    class ConfidenceScoring {

        @Test
        @DisplayName("Higher clicks yield higher confidence")
        void higherClicksHigherConfidence() {
            V3SearchTermEngineImpl.AggregatedSearchTerm lowClicks = new V3SearchTermEngineImpl.AggregatedSearchTerm();
            lowClicks.totalClicks = 10;
            lowClicks.daysOfData = 7;

            V3SearchTermEngineImpl.AggregatedSearchTerm highClicks = new V3SearchTermEngineImpl.AggregatedSearchTerm();
            highClicks.totalClicks = 100;
            highClicks.daysOfData = 7;

            BigDecimal lowConf = engine.computeConfidence(lowClicks);
            BigDecimal highConf = engine.computeConfidence(highClicks);

            assertThat(highConf).isGreaterThan(lowConf);
        }

        @Test
        @DisplayName("More days of data yield higher confidence")
        void moreDaysHigherConfidence() {
            V3SearchTermEngineImpl.AggregatedSearchTerm fewDays = new V3SearchTermEngineImpl.AggregatedSearchTerm();
            fewDays.totalClicks = 30;
            fewDays.daysOfData = 2;

            V3SearchTermEngineImpl.AggregatedSearchTerm manyDays = new V3SearchTermEngineImpl.AggregatedSearchTerm();
            manyDays.totalClicks = 30;
            manyDays.daysOfData = 14;

            BigDecimal fewConf = engine.computeConfidence(fewDays);
            BigDecimal manyConf = engine.computeConfidence(manyDays);

            assertThat(manyConf).isGreaterThan(fewConf);
        }

        @Test
        @DisplayName("Having orders boosts confidence")
        void ordersBoostConfidence() {
            V3SearchTermEngineImpl.AggregatedSearchTerm noOrders = new V3SearchTermEngineImpl.AggregatedSearchTerm();
            noOrders.totalClicks = 30;
            noOrders.daysOfData = 7;
            noOrders.totalOrders = 0;

            V3SearchTermEngineImpl.AggregatedSearchTerm withOrders = new V3SearchTermEngineImpl.AggregatedSearchTerm();
            withOrders.totalClicks = 30;
            withOrders.daysOfData = 7;
            withOrders.totalOrders = 3;

            BigDecimal noOrderConf = engine.computeConfidence(noOrders);
            BigDecimal withOrderConf = engine.computeConfidence(withOrders);

            assertThat(withOrderConf).isGreaterThan(noOrderConf);
        }

        @Test
        @DisplayName("Confidence is clamped to [0, 1]")
        void confidenceClampedToRange() {
            V3SearchTermEngineImpl.AggregatedSearchTerm maxCase = new V3SearchTermEngineImpl.AggregatedSearchTerm();
            maxCase.totalClicks = 10000;
            maxCase.daysOfData = 365;
            maxCase.totalOrders = 100;

            BigDecimal confidence = engine.computeConfidence(maxCase);

            assertThat(confidence).isLessThanOrEqualTo(BigDecimal.ONE);
            assertThat(confidence).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
    }

    // =========================================================================
    // Match type support (Req 5.10)
    // =========================================================================

    @Nested
    @DisplayName("Match type (Req 5.10)")
    class MatchType {

        @Test
        @DisplayName("High-performing terms get exact match")
        void highPerformingGetExact() {
            V3SearchTermEngineImpl.AggregatedSearchTerm agg = new V3SearchTermEngineImpl.AggregatedSearchTerm();
            agg.totalClicks = 50;
            agg.totalOrders = 3;

            String matchType = engine.determineMatchType(agg);
            assertThat(matchType).isEqualTo("exact");
        }

        @Test
        @DisplayName("Moderate-performing terms get phrase match")
        void moderatePerformingGetPhrase() {
            V3SearchTermEngineImpl.AggregatedSearchTerm agg = new V3SearchTermEngineImpl.AggregatedSearchTerm();
            agg.totalClicks = 35;
            agg.totalOrders = 1;

            String matchType = engine.determineMatchType(agg);
            assertThat(matchType).isEqualTo("phrase");
        }

        @Test
        @DisplayName("Lower-performing terms get broad match")
        void lowerPerformingGetBroad() {
            V3SearchTermEngineImpl.AggregatedSearchTerm agg = new V3SearchTermEngineImpl.AggregatedSearchTerm();
            agg.totalClicks = 10;
            agg.totalOrders = 1;

            String matchType = engine.determineMatchType(agg);
            assertThat(matchType).isEqualTo("broad");
        }
    }

    // =========================================================================
    // Decision snapshot and evidence (Req 5.11)
    // =========================================================================

    @Nested
    @DisplayName("Decision snapshot and evidence (Req 5.11)")
    class DecisionSnapshotTest {

        @Test
        @DisplayName("Keyword candidate contains confidence + evidence in snapshot")
        void keywordCandidateContainsEvidence() {
            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            // Multi-day data to meet confidence threshold
            DataSnapshot snapshot = snapshotWithSearchTermList(
                    multiDaySearchTermRows("proven term", 10, 1, "2.00", "20.00", 10));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            assertThat(result.candidates()).isNotEmpty();
            CandidateDecision candidate = result.candidates().stream()
                    .filter(c -> c.adjustmentType() == HostingAdjustmentType.KEYWORD)
                    .findFirst().orElse(null);
            assertThat(candidate).isNotNull();

            String json = candidate.decisionSnapshot();
            assertThat(json).contains("\"confidence\"");
            assertThat(json).contains("\"evidence\"");
            assertThat(json).contains("\"clicks\"");
            assertThat(json).contains("\"orders\"");
            assertThat(json).contains("\"spend\"");
            assertThat(json).contains("\"sales\"");
            assertThat(json).contains("\"acos\"");
            assertThat(json).contains("\"daysOfData\"");
            assertThat(json).contains("\"searchTerm\":\"proven term\"");
            assertThat(json).contains("\"reversible\":false");
        }

        @Test
        @DisplayName("Negative candidate contains confidence + evidence in snapshot")
        void negativeCandidateContainsEvidence() {
            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            // Multi-day data: 14 days * 15 clicks, 0 orders
            DataSnapshot snapshot = snapshotWithSearchTermList(
                    multiDaySearchTermRows("wasted term", 15, 0, "5.00", "0.00", 14));

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            CandidateDecision negCandidate = result.candidates().stream()
                    .filter(c -> c.adjustmentType() == HostingAdjustmentType.NEGATIVE)
                    .findFirst().orElse(null);
            assertThat(negCandidate).isNotNull();

            String json = negCandidate.decisionSnapshot();
            assertThat(json).contains("\"type\":\"negative_keyword_addition\"");
            assertThat(json).contains("\"confidence\"");
            assertThat(json).contains("\"evidence\"");
            assertThat(json).contains("\"reversible\":false");
        }
    }

    // =========================================================================
    // No search term data skip
    // =========================================================================

    @Nested
    @DisplayName("No data scenarios")
    class NoData {

        @Test
        @DisplayName("Skips when no search term data in snapshot")
        void skipsWhenNoSearchTermData() {
            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            DataSnapshot snapshot = emptySnapshot();

            V3SearchTermEngine.EvaluationResult result = engine.evaluateWithReason(
                    campaign, snapshot, defaultBoundary());

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.skipReason()).isEqualTo("NO_DATA");
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
            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            DataSnapshot snapshot = snapshotWithSearchTermList(
                    multiDaySearchTermRows("good term", 10, 1, "2.00", "20.00", 10));

            List<CandidateDecision> candidates = engine.evaluate(
                    campaign, snapshot, defaultBoundary());

            assertThat(candidates).isNotEmpty();
        }

        @Test
        @DisplayName("evaluate() returns empty when skipped")
        void evaluateReturnsEmptyWhenSkipped() {
            ReflectionTestUtils.setField(engine, "phaseConfig", "V1");
            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            DataSnapshot snapshot = snapshotWithSearchTermList(
                    multiDaySearchTermRows("good term", 10, 1, "2.00", "20.00", 10));

            List<CandidateDecision> candidates = engine.evaluate(
                    campaign, snapshot, defaultBoundary());

            assertThat(candidates).isEmpty();
        }
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    private CampaignEntity campaign(BigDecimal targetAcos) {
        CampaignEntity c = new CampaignEntity();
        c.setId(CAMPAIGN_ID);
        c.setStoreId(STORE_ID);
        c.setTargetAcos(targetAcos);
        return c;
    }

    private PersonalityPolicyEntity defaultPolicy() {
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
        policy.setApprovalBidChangeRatio(BigDecimal.ZERO);
        policy.setApprovalBudgetChangeRatio(BigDecimal.ZERO);
        policy.setMaxBidIncreaseRatio(new BigDecimal("0.50"));
        policy.setMaxBidDecreaseRatio(new BigDecimal("0.30"));
        policy.setMaxDailyBudgetIncreaseRatio(new BigDecimal("0.20"));
        policy.setAdjustmentCooldownHours(0);
        policy.setExploreBudgetRatioMin(BigDecimal.ZERO);
        policy.setExploreBudgetRatioMax(BigDecimal.ZERO);
        policy.setMaxNewKeywordsPerRun(5);
        policy.setMinKeywordOrders(1);
        policy.setNegativeKeywordMinClicks(20);
        policy.setRuleVersion("v1.0");
        return policy;
    }

    private SafetyBoundary defaultBoundary() {
        EnumMap<SafetyBoundaryLimit, BigDecimal> values = new EnumMap<>(SafetyBoundaryLimit.class);
        EnumMap<SafetyBoundaryLimit, SafetyBoundaryLevel> sources = new EnumMap<>(SafetyBoundaryLimit.class);
        values.put(SafetyBoundaryLimit.MAX_KEYWORDS_PER_DAY, new BigDecimal("5"));
        sources.put(SafetyBoundaryLimit.MAX_KEYWORDS_PER_DAY, SafetyBoundaryLevel.SYSTEM_DEFAULT);
        values.put(SafetyBoundaryLimit.MAX_NEGATIVES_PER_DAY, new BigDecimal("10"));
        sources.put(SafetyBoundaryLimit.MAX_NEGATIVES_PER_DAY, SafetyBoundaryLevel.SYSTEM_DEFAULT);
        return createBoundary(values, sources);
    }

    private DataSnapshot emptySnapshot() {
        return new DataSnapshot(
                Instant.now(), STORE_ID, 14, "Asia/Shanghai", "CNY",
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
    }

    private DataSnapshot snapshotWithSearchTerms(SearchTermDailyEntity... rows) {
        return new DataSnapshot(
                Instant.now(), STORE_ID, 14, "Asia/Shanghai", "CNY",
                Collections.emptyList(), List.of(rows),
                Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Create multiple days of search term data to ensure confidence threshold is met.
     */
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

    private DataSnapshot snapshotWithSearchTermList(List<SearchTermDailyEntity> rows) {
        return new DataSnapshot(
                Instant.now(), STORE_ID, 14, "Asia/Shanghai", "CNY",
                Collections.emptyList(), rows,
                Collections.emptyList(), Collections.emptyList());
    }

    private SearchTermDailyEntity searchTermRow(String term, int clicks, int orders,
                                                 String spend, String sales) {
        return SearchTermDailyEntity.builder()
                .id(UUID.randomUUID())
                .storeId(STORE_ID)
                .campaignId(CAMPAIGN_ID)
                .adGroupId(AD_GROUP_ID)
                .searchTerm(term)
                .reportDate(LocalDate.now().minusDays(1))
                .impressions((long) clicks * 10)
                .clicks(clicks)
                .orders(orders)
                .spend(new BigDecimal(spend))
                .sales(new BigDecimal(sales))
                .acos(new BigDecimal(sales).compareTo(BigDecimal.ZERO) > 0
                        ? new BigDecimal(spend).divide(new BigDecimal(sales),
                        6, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO)
                .currency("CNY")
                .dataStatus("finalized")
                .dataVersion(1)
                .build();
    }

    /**
     * Creates a SafetyBoundary via reflection since the constructor is package-private.
     */
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
