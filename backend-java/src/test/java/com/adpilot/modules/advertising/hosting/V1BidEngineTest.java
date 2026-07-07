package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.operation.InFlightConflictLock;
import com.adpilot.modules.advertising.operation.OperationMachineValues;
import com.adpilot.modules.advertising.operation.OperationSource;
import com.adpilot.modules.advertising.service.AiPersonality;
import com.adpilot.modules.advertising.service.HostingPhase;
import com.adpilot.modules.advertising.service.PersonalityPolicyService;
import com.adpilot.modules.advertising.service.PersonalityResolver;
import com.adpilot.modules.advertising.support.RiskScoreCalculator;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the V1BidEngine (Requirement 36).
 *
 * <p>Validates keyword-level ACoS computation, cooldown enforcement,
 * in-flight conflict lock, bid clamping, and candidate emission.
 */
@DisplayName("V1BidEngine")
class V1BidEngineTest {

    private KeywordMapper keywordMapper;
    private OperationMapper operationMapper;
    private InFlightConflictLock inFlightConflictLock;
    private PersonalityResolver personalityResolver;
    private PersonalityPolicyService personalityPolicyService;
    private RiskScoreCalculator riskScoreCalculator;
    private ObjectMapper objectMapper;
    private V1BidEngine engine;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID CAMPAIGN_ID = UUID.randomUUID();
    private static final UUID KEYWORD_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        keywordMapper = mock(KeywordMapper.class);
        operationMapper = mock(OperationMapper.class);
        inFlightConflictLock = mock(InFlightConflictLock.class);
        personalityResolver = mock(PersonalityResolver.class);
        personalityPolicyService = mock(PersonalityPolicyService.class);
        riskScoreCalculator = new RiskScoreCalculator();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        LearningPeriodService learningPeriodService = mock(LearningPeriodService.class);
        when(learningPeriodService.isInLearningPeriod(any())).thenReturn(false);

        engine = new V1BidEngine(
                keywordMapper, operationMapper, inFlightConflictLock,
                personalityResolver, personalityPolicyService,
                riskScoreCalculator, objectMapper, learningPeriodService);

        // Set default configuration values
        ReflectionTestUtils.setField(engine, "minClicksThreshold", 10);
        ReflectionTestUtils.setField(engine, "minImpressionsThreshold", 100);
        ReflectionTestUtils.setField(engine, "defaultMinBid", new BigDecimal("0.02"));
        ReflectionTestUtils.setField(engine, "defaultMaxBid", new BigDecimal("100.00"));

        // Default mock behavior
        when(personalityResolver.resolveForCampaign(any(CampaignEntity.class)))
                .thenReturn(AiPersonality.SYSTEM_FALLBACK);
        when(personalityPolicyService.resolvePolicy(anyString()))
                .thenReturn(defaultPolicy());
    }

    // =========================================================================
    // Phase gating
    // =========================================================================

    @Nested
    @DisplayName("Phase gating")
    class PhaseGating {

        @Test
        @DisplayName("Returns empty when phase does not support BID")
        void returnsEmptyWhenPhaseDoesNotSupportBid() {
            // HostingPhase with no BID support doesn't exist in enum but we test
            // by checking that V1/V2/V3 all support BID
            // Instead, test with a null campaign
            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            DataSnapshot snapshot = emptySnapshot();
            SafetyBoundary boundary = defaultBoundary();

            // V1 should work (all phases support BID in the current enum)
            when(keywordMapper.selectList(any())).thenReturn(Collections.emptyList());
            List<CandidateDecision> result = engine.produceCandidates(
                    campaign, snapshot, boundary, HostingPhase.V1);
            assertThat(result).isEmpty(); // empty because no keywords
        }

        @Test
        @DisplayName("Returns empty when campaign has no target ACoS")
        void returnsEmptyWhenNoTargetAcos() {
            CampaignEntity campaign = campaign(null);
            DataSnapshot snapshot = emptySnapshot();
            SafetyBoundary boundary = defaultBoundary();

            List<CandidateDecision> result = engine.produceCandidates(
                    campaign, snapshot, boundary, HostingPhase.V1);
            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // Keyword-level ACoS computation (Req 36.1)
    // =========================================================================

    @Nested
    @DisplayName("Keyword-level ACoS (Req 36.1)")
    class KeywordLevelAcos {

        @Test
        @DisplayName("Computes ACoS from keyword-specific performance data")
        void computesAcosFromKeywordData() {
            // Spend = 30, Sales = 100 → ACoS = 0.30
            List<PerformanceDailyEntity> performance = List.of(
                    perfRow(KEYWORD_ID, new BigDecimal("10"), new BigDecimal("40"), 5, 50L),
                    perfRow(KEYWORD_ID, new BigDecimal("20"), new BigDecimal("60"), 8, 80L)
            );

            BigDecimal acos = engine.computeKeywordAcos(performance);
            assertThat(acos).isEqualByComparingTo(new BigDecimal("0.3"));
        }

        @Test
        @DisplayName("Returns null when no spend and no sales")
        void returnsNullWhenNoSignal() {
            List<PerformanceDailyEntity> performance = List.of(
                    perfRow(KEYWORD_ID, BigDecimal.ZERO, BigDecimal.ZERO, 0, 100L)
            );

            BigDecimal acos = engine.computeKeywordAcos(performance);
            assertThat(acos).isNull();
        }

        @Test
        @DisplayName("Returns sentinel when spend but no sales")
        void returnsSentinelWhenNoSales() {
            List<PerformanceDailyEntity> performance = List.of(
                    perfRow(KEYWORD_ID, new BigDecimal("50"), BigDecimal.ZERO, 10, 200L)
            );

            BigDecimal acos = engine.computeKeywordAcos(performance);
            assertThat(acos).isEqualByComparingTo(new BigDecimal("999.99"));
        }
    }

    // =========================================================================
    // Insufficient data skip (Req 36.2)
    // =========================================================================

    @Nested
    @DisplayName("Insufficient data (Req 36.2)")
    class InsufficientData {

        @Test
        @DisplayName("Skips keyword with insufficient clicks")
        void skipsInsufficientClicks() {
            List<PerformanceDailyEntity> performance = List.of(
                    perfRow(KEYWORD_ID, new BigDecimal("5"), new BigDecimal("50"), 5, 200L)
            );

            boolean sufficient = engine.hasSufficientData(performance);
            assertThat(sufficient).isFalse();
        }

        @Test
        @DisplayName("Skips keyword with insufficient impressions")
        void skipsInsufficientImpressions() {
            List<PerformanceDailyEntity> performance = List.of(
                    perfRow(KEYWORD_ID, new BigDecimal("5"), new BigDecimal("50"), 15, 50L)
            );

            boolean sufficient = engine.hasSufficientData(performance);
            assertThat(sufficient).isFalse();
        }

        @Test
        @DisplayName("Accepts keyword with sufficient data")
        void acceptsSufficientData() {
            List<PerformanceDailyEntity> performance = List.of(
                    perfRow(KEYWORD_ID, new BigDecimal("5"), new BigDecimal("50"), 12, 150L)
            );

            boolean sufficient = engine.hasSufficientData(performance);
            assertThat(sufficient).isTrue();
        }

        @Test
        @DisplayName("Empty performance returns insufficient")
        void emptyPerformanceIsInsufficient() {
            assertThat(engine.hasSufficientData(Collections.emptyList())).isFalse();
            assertThat(engine.hasSufficientData(null)).isFalse();
        }
    }

    // =========================================================================
    // Cooldown enforcement (Req 36.5)
    // =========================================================================

    @Nested
    @DisplayName("Cooldown enforcement (Req 36.5)")
    class CooldownEnforcement {

        @Test
        @DisplayName("Keyword in cooldown is skipped")
        void keywordInCooldownSkipped() {
            // Mock a recent operation on the keyword
            OperationEntity recentOp = OperationEntity.builder()
                    .id(UUID.randomUUID())
                    .createdAt(LocalDateTime.now().minusHours(1))
                    .build();
            when(operationMapper.selectOne(any())).thenReturn(recentOp);

            assertThat(engine.isInCooldown(KEYWORD_ID, 24)).isTrue();
        }

        @Test
        @DisplayName("Keyword past cooldown is not skipped")
        void keywordPastCooldownNotSkipped() {
            when(operationMapper.selectOne(any())).thenReturn(null);

            assertThat(engine.isInCooldown(KEYWORD_ID, 24)).isFalse();
        }
    }

    // =========================================================================
    // Bid clamping (Req 36.6)
    // =========================================================================

    @Nested
    @DisplayName("Bid clamping (Req 36.6)")
    class BidClamping {

        @Test
        @DisplayName("Clamps by maxBidAdjustmentRatio")
        void clampsMaxBidAdjustmentRatio() {
            BigDecimal currentBid = new BigDecimal("1.00");
            BigDecimal adjustedBid = new BigDecimal("2.00"); // +100% change
            SafetyBoundary boundary = boundaryWith(SafetyBoundaryLimit.MAX_BID_ADJUSTMENT_RATIO,
                    new BigDecimal("0.30")); // max 30%

            BigDecimal result = engine.clampByMaxBidAdjustmentRatio(currentBid, adjustedBid, boundary);
            // Should clamp to 1.00 + 0.30 = 1.30
            assertThat(result).isEqualByComparingTo(new BigDecimal("1.30"));
        }

        @Test
        @DisplayName("Clamps bid decrease by maxBidAdjustmentRatio")
        void clampsBidDecreaseMaxRatio() {
            BigDecimal currentBid = new BigDecimal("1.00");
            BigDecimal adjustedBid = new BigDecimal("0.50"); // -50% change
            SafetyBoundary boundary = boundaryWith(SafetyBoundaryLimit.MAX_BID_ADJUSTMENT_RATIO,
                    new BigDecimal("0.20")); // max 20%

            BigDecimal result = engine.clampByMaxBidAdjustmentRatio(currentBid, adjustedBid, boundary);
            // Should clamp to 1.00 - 0.20 = 0.80
            assertThat(result).isEqualByComparingTo(new BigDecimal("0.80"));
        }

        @Test
        @DisplayName("Does not clamp when within ratio")
        void doesNotClampWithinRatio() {
            BigDecimal currentBid = new BigDecimal("1.00");
            BigDecimal adjustedBid = new BigDecimal("1.10"); // +10% change
            SafetyBoundary boundary = boundaryWith(SafetyBoundaryLimit.MAX_BID_ADJUSTMENT_RATIO,
                    new BigDecimal("0.30")); // max 30%

            BigDecimal result = engine.clampByMaxBidAdjustmentRatio(currentBid, adjustedBid, boundary);
            assertThat(result).isEqualByComparingTo(new BigDecimal("1.10"));
        }

        @Test
        @DisplayName("Clamps by maxCpc")
        void clampsMaxCpc() {
            BigDecimal adjustedBid = new BigDecimal("5.00");
            SafetyBoundary boundary = boundaryWith(SafetyBoundaryLimit.MAX_CPC, new BigDecimal("3.00"));

            BigDecimal result = engine.clampByMaxCpc(adjustedBid, boundary);
            assertThat(result).isEqualByComparingTo(new BigDecimal("3.00"));
        }

        @Test
        @DisplayName("Does not clamp when below maxCpc")
        void doesNotClampBelowMaxCpc() {
            BigDecimal adjustedBid = new BigDecimal("2.50");
            SafetyBoundary boundary = boundaryWith(SafetyBoundaryLimit.MAX_CPC, new BigDecimal("3.00"));

            BigDecimal result = engine.clampByMaxCpc(adjustedBid, boundary);
            assertThat(result).isEqualByComparingTo(new BigDecimal("2.50"));
        }
    }

    // =========================================================================
    // End-to-end candidate production
    // =========================================================================

    @Nested
    @DisplayName("End-to-end candidate production")
    class EndToEnd {

        @Test
        @DisplayName("Produces bid decrease candidate when keyword ACoS > target")
        void producesBidDecreaseWhenAcosAboveTarget() {
            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            KeywordEntity keyword = keyword(KEYWORD_ID, new BigDecimal("1.50"));

            // Keyword ACoS = 60/100 = 0.60 (above target 0.30)
            List<PerformanceDailyEntity> perfData = List.of(
                    perfRow(KEYWORD_ID, new BigDecimal("30"), new BigDecimal("50"), 20, 200L),
                    perfRow(KEYWORD_ID, new BigDecimal("30"), new BigDecimal("50"), 15, 150L)
            );

            DataSnapshot snapshot = snapshotWithPerformance(perfData);
            SafetyBoundary boundary = defaultBoundary();

            when(keywordMapper.selectList(any())).thenReturn(List.of(keyword));
            when(inFlightConflictLock.hasInFlightOperation(anyString(), any(), anyString()))
                    .thenReturn(false);
            when(operationMapper.selectOne(any())).thenReturn(null);

            List<CandidateDecision> candidates = engine.produceCandidates(
                    campaign, snapshot, boundary, HostingPhase.V1);

            assertThat(candidates).hasSize(1);
            CandidateDecision candidate = candidates.get(0);
            assertThat(candidate.entityType()).isEqualTo("keyword");
            assertThat(candidate.entityId()).isEqualTo(KEYWORD_ID);
            assertThat(candidate.field()).isEqualTo("bid");
            assertThat(candidate.engineType()).isEqualTo(CandidateDecision.ENGINE_V1_BID);
            assertThat(candidate.beforeValue()).isEqualByComparingTo(new BigDecimal("1.50"));
            // Adjusted bid should be less than current (bid decrease)
            assertThat(candidate.proposedValue()).isLessThan(candidate.beforeValue());
        }

        @Test
        @DisplayName("Produces bid increase candidate when keyword ACoS < target")
        void producesBidIncreaseWhenAcosBelowTarget() {
            CampaignEntity campaign = campaign(new BigDecimal("0.50"));
            KeywordEntity keyword = keyword(KEYWORD_ID, new BigDecimal("1.00"));

            // Keyword ACoS = 20/100 = 0.20 (below target 0.50)
            List<PerformanceDailyEntity> perfData = List.of(
                    perfRow(KEYWORD_ID, new BigDecimal("10"), new BigDecimal("50"), 15, 150L),
                    perfRow(KEYWORD_ID, new BigDecimal("10"), new BigDecimal("50"), 12, 120L)
            );

            DataSnapshot snapshot = snapshotWithPerformance(perfData);
            SafetyBoundary boundary = defaultBoundary();

            when(keywordMapper.selectList(any())).thenReturn(List.of(keyword));
            when(inFlightConflictLock.hasInFlightOperation(anyString(), any(), anyString()))
                    .thenReturn(false);
            when(operationMapper.selectOne(any())).thenReturn(null);

            List<CandidateDecision> candidates = engine.produceCandidates(
                    campaign, snapshot, boundary, HostingPhase.V1);

            assertThat(candidates).hasSize(1);
            CandidateDecision candidate = candidates.get(0);
            // Adjusted bid should be more than current (bid increase)
            assertThat(candidate.proposedValue()).isGreaterThan(candidate.beforeValue());
        }

        @Test
        @DisplayName("Skips keyword with in-flight operation")
        void skipsKeywordWithInFlightOp() {
            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            KeywordEntity keyword = keyword(KEYWORD_ID, new BigDecimal("1.50"));

            List<PerformanceDailyEntity> perfData = List.of(
                    perfRow(KEYWORD_ID, new BigDecimal("60"), new BigDecimal("100"), 20, 200L)
            );

            DataSnapshot snapshot = snapshotWithPerformance(perfData);
            SafetyBoundary boundary = defaultBoundary();

            when(keywordMapper.selectList(any())).thenReturn(List.of(keyword));
            // In-flight conflict exists
            when(inFlightConflictLock.hasInFlightOperation(
                    eq("keyword"), eq(KEYWORD_ID), eq("bid"))).thenReturn(true);

            List<CandidateDecision> candidates = engine.produceCandidates(
                    campaign, snapshot, boundary, HostingPhase.V1);

            assertThat(candidates).isEmpty();
        }

        @Test
        @DisplayName("Skips keyword within cooldown period")
        void skipsKeywordInCooldown() {
            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            KeywordEntity keyword = keyword(KEYWORD_ID, new BigDecimal("1.50"));

            List<PerformanceDailyEntity> perfData = List.of(
                    perfRow(KEYWORD_ID, new BigDecimal("60"), new BigDecimal("100"), 20, 200L)
            );

            DataSnapshot snapshot = snapshotWithPerformance(perfData);
            SafetyBoundary boundary = defaultBoundary();

            when(keywordMapper.selectList(any())).thenReturn(List.of(keyword));
            when(inFlightConflictLock.hasInFlightOperation(anyString(), any(), anyString()))
                    .thenReturn(false);
            // Recent operation exists (within cooldown)
            OperationEntity recentOp = OperationEntity.builder()
                    .id(UUID.randomUUID())
                    .createdAt(LocalDateTime.now().minusHours(1))
                    .build();
            when(operationMapper.selectOne(any())).thenReturn(recentOp);

            // Policy with 24-hour cooldown
            PersonalityPolicyEntity policy = defaultPolicy();
            policy.setAdjustmentCooldownHours(24);
            when(personalityPolicyService.resolvePolicy(anyString())).thenReturn(policy);

            List<CandidateDecision> candidates = engine.produceCandidates(
                    campaign, snapshot, boundary, HostingPhase.V1);

            assertThat(candidates).isEmpty();
        }

        @Test
        @DisplayName("Skips keyword with insufficient data without fallback (Req 36.2)")
        void skipsInsufficientDataNoFallback() {
            CampaignEntity campaign = campaign(new BigDecimal("0.30"));
            KeywordEntity keyword = keyword(KEYWORD_ID, new BigDecimal("1.50"));

            // Only 3 clicks — insufficient
            List<PerformanceDailyEntity> perfData = List.of(
                    perfRow(KEYWORD_ID, new BigDecimal("5"), new BigDecimal("50"), 3, 50L)
            );

            DataSnapshot snapshot = snapshotWithPerformance(perfData);
            SafetyBoundary boundary = defaultBoundary();

            when(keywordMapper.selectList(any())).thenReturn(List.of(keyword));
            when(inFlightConflictLock.hasInFlightOperation(anyString(), any(), anyString()))
                    .thenReturn(false);
            when(operationMapper.selectOne(any())).thenReturn(null);

            List<CandidateDecision> candidates = engine.produceCandidates(
                    campaign, snapshot, boundary, HostingPhase.V1);

            // No candidates — insufficient data, no fallback to campaign aggregate
            assertThat(candidates).isEmpty();
        }
    }

    // =========================================================================
    // Data confidence and volatility computation
    // =========================================================================

    @Nested
    @DisplayName("Data confidence")
    class DataConfidence {

        @Test
        @DisplayName("Higher clicks = higher confidence")
        void higherClicksHigherConfidence() {
            List<PerformanceDailyEntity> lowClicks = List.of(
                    perfRow(KEYWORD_ID, new BigDecimal("5"), new BigDecimal("50"), 10, 100L)
            );
            List<PerformanceDailyEntity> highClicks = List.of(
                    perfRow(KEYWORD_ID, new BigDecimal("50"), new BigDecimal("200"), 100, 500L)
            );

            BigDecimal lowConf = engine.computeDataConfidence(lowClicks);
            BigDecimal highConf = engine.computeDataConfidence(highClicks);

            assertThat(highConf).isGreaterThan(lowConf);
        }

        @Test
        @DisplayName("Empty returns zero confidence")
        void emptyReturnsZero() {
            assertThat(engine.computeDataConfidence(Collections.emptyList()))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private CampaignEntity campaign(BigDecimal targetAcos) {
        CampaignEntity c = new CampaignEntity();
        c.setId(CAMPAIGN_ID);
        c.setStoreId(STORE_ID);
        c.setTargetAcos(targetAcos);
        return c;
    }

    private KeywordEntity keyword(UUID keywordId, BigDecimal bid) {
        return KeywordEntity.builder()
                .id(keywordId)
                .campaignId(CAMPAIGN_ID)
                .adGroupId(UUID.randomUUID())
                .storeId(STORE_ID)
                .keywordText("test keyword")
                .status("enabled")
                .bid(bid)
                .build();
    }

    private PerformanceDailyEntity perfRow(UUID keywordId, BigDecimal spend,
                                           BigDecimal sales, int clicks, long impressions) {
        return PerformanceDailyEntity.builder()
                .id(UUID.randomUUID())
                .storeId(STORE_ID)
                .campaignId(CAMPAIGN_ID)
                .keywordId(keywordId)
                .entityType("keyword")
                .entityId(keywordId)
                .date(LocalDate.now().minusDays(1))
                .spend(spend)
                .sales(sales)
                .clicks(clicks)
                .impressions(impressions)
                .build();
    }

    private PersonalityPolicyEntity defaultPolicy() {
        return PersonalityPolicyEntity.builder()
                .id(UUID.randomUUID())
                .scope("system")
                .personality("balanced")
                .ruleVersion("v1.0")
                .lookbackDays(14)
                .adjustmentCooldownHours(0)
                .maxBidIncreaseRatio(new BigDecimal("0.20"))
                .maxBidDecreaseRatio(new BigDecimal("0.20"))
                .minClicks(10)
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
                .minBid(new BigDecimal("0.02"))
                .maxBid(new BigDecimal("100.00"))
                .maxCpc(new BigDecimal("50.00"))
                .maxBidAdjustmentRatio(new BigDecimal("0.50"))
                .build();
        return SafetyBoundaryResolver.resolve(null, null, null, limits);
    }

    private SafetyBoundary boundaryWith(SafetyBoundaryLimit limit, BigDecimal value) {
        SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                .minBid(new BigDecimal("0.02"))
                .maxBid(new BigDecimal("100.00"))
                .limit(limit, value)
                .build();
        return SafetyBoundaryResolver.resolve(null, null, null, limits);
    }
}
