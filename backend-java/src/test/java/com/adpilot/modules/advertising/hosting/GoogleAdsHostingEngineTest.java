package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.service.HostingAdjustmentType;
import com.adpilot.modules.advertising.service.HostingPhase;
import com.adpilot.modules.advertising.support.RiskScoreCalculator;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryResolver;
import com.adpilot.modules.apisync.vo.GoogleAdsCampaignVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link GoogleAdsHostingEngine} (platform-workspace-rbac Req 8.1, 8.2, 8.5).
 */
@DisplayName("GoogleAdsHostingEngine")
class GoogleAdsHostingEngineTest {

    private static final UUID STORE_ID = UUID.randomUUID();

    private final GoogleAdsHostingEngine engine = new GoogleAdsHostingEngine(new RiskScoreCalculator());

    // =========================================================================
    // Budget direction (Req 8.1)
    // =========================================================================

    @Nested
    @DisplayName("Budget adjustment direction (Req 8.1)")
    class BudgetDirection {

        @Test
        @DisplayName("Proposes a budget INCREASE when ACoS is below target")
        void increasesWhenBelowTarget() {
            // ACoS = 50/500 = 0.10, below target 0.30 → increase
            GoogleAdsCampaignVo campaign = campaign("c1", "ENABLED",
                    new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal("500.00"), 200);

            List<CandidateDecision> candidates = engine.produceCandidates(
                    STORE_ID, campaign, defaultBoundary(), HostingPhase.V2, ctx(new BigDecimal("0.30")));

            CandidateDecision budget = ofChangeType(candidates, "budget");
            assertThat(budget).isNotNull();
            assertThat(budget.adjustmentType()).isEqualTo(HostingAdjustmentType.BUDGET);
            assertThat(budget.proposedValue()).isGreaterThan(budget.beforeValue());
            assertThat(budget.engineType()).isEqualTo(GoogleAdsHostingEngine.ENGINE_GOOGLE_ADS);
        }

        @Test
        @DisplayName("Proposes a budget DECREASE when ACoS exceeds target tolerance")
        void decreasesWhenAboveTarget() {
            // ACoS = 250/500 = 0.50, above target 0.20 → decrease
            GoogleAdsCampaignVo campaign = campaign("c1", "ENABLED",
                    new BigDecimal("100.00"), new BigDecimal("250.00"), new BigDecimal("500.00"), 200);

            List<CandidateDecision> candidates = engine.produceCandidates(
                    STORE_ID, campaign, defaultBoundary(), HostingPhase.V2, ctx(new BigDecimal("0.20")));

            CandidateDecision budget = ofChangeType(candidates, "budget");
            assertThat(budget).isNotNull();
            assertThat(budget.proposedValue()).isLessThan(budget.beforeValue());
        }

        @Test
        @DisplayName("No budget candidate when phase does not support BUDGET")
        void noBudgetWhenPhaseV1() {
            GoogleAdsCampaignVo campaign = campaign("c1", "ENABLED",
                    new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal("500.00"), 200);

            List<CandidateDecision> candidates = engine.produceCandidates(
                    STORE_ID, campaign, defaultBoundary(), HostingPhase.V1, ctx(new BigDecimal("0.30")));

            assertThat(ofChangeType(candidates, "budget")).isNull();
        }
    }

    // =========================================================================
    // Safety-boundary clamping (Req 8.5)
    // =========================================================================

    @Nested
    @DisplayName("Safety-boundary clamping (Req 8.5)")
    class Clamping {

        @Test
        @DisplayName("Budget increase is clamped to MAX_DAILY_BUDGET")
        void clampsToMaxDailyBudget() {
            // Strongly below target → large increase, but ceiling is tight.
            GoogleAdsCampaignVo campaign = campaign("c1", "ENABLED",
                    new BigDecimal("100.00"), new BigDecimal("1.00"), new BigDecimal("1000.00"), 500);
            SafetyBoundary boundary = SafetyBoundaryResolver.resolve(null, null, null,
                    SafetyBoundaryLimits.builder()
                            .minDailyBudget(new BigDecimal("1.00"))
                            .maxDailyBudget(new BigDecimal("110.00"))
                            .maxDailyBudgetIncreaseRatio(new BigDecimal("0.50"))
                            .build());

            CandidateDecision budget = ofChangeType(engine.produceCandidates(
                    STORE_ID, campaign, boundary, HostingPhase.V2, ctx(new BigDecimal("0.30"))), "budget");

            assertThat(budget).isNotNull();
            assertThat(budget.proposedValue()).isLessThanOrEqualTo(new BigDecimal("110.00"));
        }

        @Test
        @DisplayName("Budget increase is bounded by the per-run increase ratio")
        void boundedByIncreaseRatio() {
            GoogleAdsCampaignVo campaign = campaign("c1", "ENABLED",
                    new BigDecimal("100.00"), new BigDecimal("1.00"), new BigDecimal("1000.00"), 500);
            // increase ratio cap = 10%
            SafetyBoundary boundary = SafetyBoundaryResolver.resolve(null, null, null,
                    SafetyBoundaryLimits.builder()
                            .minDailyBudget(new BigDecimal("1.00"))
                            .maxDailyBudget(new BigDecimal("100000.00"))
                            .maxDailyBudgetIncreaseRatio(new BigDecimal("0.10"))
                            .build());

            CandidateDecision budget = ofChangeType(engine.produceCandidates(
                    STORE_ID, campaign, boundary, HostingPhase.V2, ctx(new BigDecimal("0.30"))), "budget");

            assertThat(budget).isNotNull();
            // current * (1 + 0.10) = 110.00
            assertThat(budget.proposedValue()).isLessThanOrEqualTo(new BigDecimal("110.00"));
        }
    }

    // =========================================================================
    // Status pause (Req 8.1)
    // =========================================================================

    @Nested
    @DisplayName("Status pause candidate (Req 8.1)")
    class StatusPause {

        @Test
        @DisplayName("Produces a pause candidate for an enabled campaign with spend and no conversions")
        void pausesWastefulCampaign() {
            GoogleAdsCampaignVo campaign = campaign("c1", "ENABLED",
                    new BigDecimal("100.00"), new BigDecimal("80.00"), BigDecimal.ZERO, 300);
            campaign.setConversions(0d);

            CandidateDecision state = ofChangeType(engine.produceCandidates(
                    STORE_ID, campaign, defaultBoundary(), HostingPhase.V2, ctx(new BigDecimal("0.30"))), "state");

            assertThat(state).isNotNull();
            assertThat(state.field()).isEqualTo("status");
            assertThat(state.proposedValue()).isNull();
            assertThat(state.isReversible()).isTrue();
        }

        @Test
        @DisplayName("Does not pause a campaign that has conversions")
        void doesNotPauseConvertingCampaign() {
            GoogleAdsCampaignVo campaign = campaign("c1", "ENABLED",
                    new BigDecimal("100.00"), new BigDecimal("80.00"), new BigDecimal("300.00"), 300);
            campaign.setConversions(5d);

            CandidateDecision state = ofChangeType(engine.produceCandidates(
                    STORE_ID, campaign, defaultBoundary(), HostingPhase.V2, ctx(new BigDecimal("0.30"))), "state");

            assertThat(state).isNull();
        }

        @Test
        @DisplayName("Does not pause an already-paused campaign")
        void doesNotPausePausedCampaign() {
            GoogleAdsCampaignVo campaign = campaign("c1", "PAUSED",
                    new BigDecimal("100.00"), new BigDecimal("80.00"), BigDecimal.ZERO, 300);
            campaign.setConversions(0d);

            CandidateDecision state = ofChangeType(engine.produceCandidates(
                    STORE_ID, campaign, defaultBoundary(), HostingPhase.V2, ctx(new BigDecimal("0.30"))), "state");

            assertThat(state).isNull();
        }
    }

    // =========================================================================
    // Campaign create (Req 8.1)
    // =========================================================================

    @Nested
    @DisplayName("Campaign-create candidate (Req 8.1)")
    class CampaignCreate {

        @Test
        @DisplayName("Produces a create candidate for a strong performer when enabled")
        void createsForStrongPerformer() {
            // ACoS = 50/1000 = 0.05, well below target 0.30 (< 0.30 * 0.5 = 0.15)
            GoogleAdsCampaignVo campaign = campaign("c1", "ENABLED",
                    new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal("1000.00"), 500);
            campaign.setConversions(20d);

            GoogleAdsHostingContext ctx = new GoogleAdsHostingContext(
                    new BigDecimal("0.30"), 14, "balanced", "v1.0",
                    BigDecimal.ZERO, new BigDecimal("0.20"), new BigDecimal("0.30"),
                    new BigDecimal("0.30"), true, new BigDecimal("0.50"));

            CandidateDecision create = ofChangeType(engine.produceCandidates(
                    STORE_ID, campaign, defaultBoundary(), HostingPhase.V2, ctx), "create");

            assertThat(create).isNotNull();
            assertThat(create.isReversible()).isFalse();
            assertThat(create.proposedValue()).isNotNull();
        }

        @Test
        @DisplayName("Does not create when create is disabled in context")
        void noCreateWhenDisabled() {
            GoogleAdsCampaignVo campaign = campaign("c1", "ENABLED",
                    new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal("1000.00"), 500);
            campaign.setConversions(20d);

            CandidateDecision create = ofChangeType(engine.produceCandidates(
                    STORE_ID, campaign, defaultBoundary(), HostingPhase.V2, ctx(new BigDecimal("0.30"))), "create");

            assertThat(create).isNull();
        }
    }

    // =========================================================================
    // ACoS computation
    // =========================================================================

    @Nested
    @DisplayName("ACoS computation")
    class AcosComputation {

        @Test
        @DisplayName("Computes cost / conversion value")
        void computesRatio() {
            GoogleAdsCampaignVo campaign = campaign("c1", "ENABLED",
                    new BigDecimal("100.00"), new BigDecimal("30.00"), new BigDecimal("100.00"), 100);
            assertThat(engine.computeAcos(campaign)).isEqualByComparingTo(new BigDecimal("0.30"));
        }

        @Test
        @DisplayName("Returns null when no cost and no value")
        void nullWhenNoSignal() {
            GoogleAdsCampaignVo campaign = campaign("c1", "ENABLED",
                    new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, 0);
            assertThat(engine.computeAcos(campaign)).isNull();
        }

        @Test
        @DisplayName("Returns high sentinel when cost but no conversion value")
        void sentinelWhenNoSales() {
            GoogleAdsCampaignVo campaign = campaign("c1", "ENABLED",
                    new BigDecimal("100.00"), new BigDecimal("40.00"), BigDecimal.ZERO, 50);
            assertThat(engine.computeAcos(campaign)).isEqualByComparingTo(GoogleAdsHostingEngine.NO_SALES_ACOS);
        }
    }

    @Test
    @DisplayName("Deterministic campaign UUID for the same external id")
    void deterministicCampaignUuid() {
        assertThat(GoogleAdsHostingEngine.campaignUuid("12345"))
                .isEqualTo(GoogleAdsHostingEngine.campaignUuid("12345"));
        assertThat(GoogleAdsHostingEngine.campaignUuid("12345"))
                .isNotEqualTo(GoogleAdsHostingEngine.campaignUuid("67890"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static CandidateDecision ofChangeType(List<CandidateDecision> candidates, String changeType) {
        return candidates.stream()
                .filter(c -> changeType.equals(c.changeType()))
                .findFirst()
                .orElse(null);
    }

    private static GoogleAdsHostingContext ctx(BigDecimal targetAcos) {
        return GoogleAdsHostingContext.defaults(targetAcos, 14);
    }

    private static GoogleAdsCampaignVo campaign(String id, String status, BigDecimal budget,
                                                BigDecimal cost, BigDecimal conversionValue, long clicks) {
        GoogleAdsCampaignVo vo = GoogleAdsCampaignVo.builder()
                .campaignId(id)
                .name("Campaign " + id)
                .status(status)
                .budget(budget)
                .cost(cost)
                .conversionValue(conversionValue.signum() == 0 ? null : conversionValue)
                .impressions(clicks * 10)
                .clicks(clicks)
                .conversions(conversionValue.signum() == 0 ? 0d : 10d)
                .build();
        return vo;
    }

    private static SafetyBoundary defaultBoundary() {
        return SafetyBoundaryResolver.resolve(null, null, null,
                SafetyBoundaryLimits.builder()
                        .minDailyBudget(new BigDecimal("1.00"))
                        .maxDailyBudget(new BigDecimal("100000.00"))
                        .maxDailyBudgetIncreaseRatio(new BigDecimal("0.20"))
                        .maxDailyBudgetDecreaseRatio(new BigDecimal("0.30"))
                        .build());
    }
}
