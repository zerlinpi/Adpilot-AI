package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.service.HostingPhase;
import com.adpilot.modules.advertising.support.RiskScoreCalculator;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryResolver;
import com.adpilot.modules.apisync.vo.GoogleAdsCampaignVo;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for safety-boundary clamping in the {@link GoogleAdsHostingEngine}.
 *
 * <p>Feature: platform-workspace-rbac, Property 16: AI hosting clamps to the safety boundary.
 *
 * <p>Validates: Requirements 8.5.
 *
 * <p>For any proposed Google Ads change and any resolved Safety_Boundary, the value carried by
 * the emitted candidate lies within the boundary's limits. The engine clamps every numeric
 * proposed value (budget adjustment, campaign-create initial budget) within the resolved
 * {@link SafetyBoundary}'s absolute {@code [MIN_DAILY_BUDGET, MAX_DAILY_BUDGET]} bounds and the
 * per-run increase/decrease ratio caps before emitting a candidate. This test generates random
 * campaign performance data and random safety boundaries and asserts that every emitted candidate
 * carrying a numeric value lies within the boundary's absolute limits.
 */
class GoogleAdsHostingClampingPropertyTest {

    private final GoogleAdsHostingEngine engine = new GoogleAdsHostingEngine(new RiskScoreCalculator());

    /**
     * Feature: platform-workspace-rbac, Property 16: AI hosting clamps to the safety boundary.
     *
     * <p>Validates: Requirements 8.5.
     *
     * <p>Every emitted candidate that carries a numeric proposed value lies within the resolved
     * boundary's absolute daily-budget limits, regardless of the proposed change the performance
     * data would otherwise drive.
     */
    @Property(tries = 200)
    void everyEmittedCandidateValueLiesWithinTheSafetyBoundary(@ForAll("scenarios") Scenario scenario) {
        SafetyBoundary boundary = scenario.boundary();
        BigDecimal min = boundary.get(SafetyBoundaryLimit.MIN_DAILY_BUDGET).orElseThrow();
        BigDecimal max = boundary.get(SafetyBoundaryLimit.MAX_DAILY_BUDGET).orElseThrow();

        List<CandidateDecision> candidates = engine.produceCandidates(
                UUID.randomUUID(), scenario.campaign(), boundary, HostingPhase.V2, scenario.ctx());

        for (CandidateDecision candidate : candidates) {
            BigDecimal proposed = candidate.proposedValue();
            if (proposed == null) {
                // Status (pause) candidates carry no in-place numeric value — nothing to clamp.
                continue;
            }
            assertThat(proposed)
                    .as("candidate %s proposed value must be >= MIN_DAILY_BUDGET", candidate.changeType())
                    .isGreaterThanOrEqualTo(min);
            assertThat(proposed)
                    .as("candidate %s proposed value must be <= MAX_DAILY_BUDGET", candidate.changeType())
                    .isLessThanOrEqualTo(max);
        }
    }

    // --- generators --------------------------------------------------------

    @Provide
    Arbitrary<Scenario> scenarios() {
        // 2-decimal positive currency amounts via unscaled cents.
        Arbitrary<BigDecimal> budget = cents(1, 10_000_00);
        Arbitrary<BigDecimal> cost = cents(0, 10_000_00);
        Arbitrary<BigDecimal> conversionValue = cents(0, 20_000_00);
        Arbitrary<Long> clicks = Arbitraries.longs().between(0L, 5_000L);
        Arbitrary<Double> conversions = Arbitraries.doubles().between(0d, 500d);
        Arbitrary<String> status = Arbitraries.of("ENABLED", "PAUSED");

        // target ACoS as a ratio in (0, 1].
        Arbitrary<BigDecimal> targetAcos = Arbitraries.integers().between(1, 100)
                .map(i -> BigDecimal.valueOf(i, 2));

        // Absolute boundary: min <= max (both 2-decimal currency amounts).
        Arbitrary<BigDecimal> minBudget = cents(1, 5_000_00);
        Arbitrary<BigDecimal> maxDelta = cents(0, 5_000_00);

        // Per-run ratio caps in (0, 3].
        Arbitrary<BigDecimal> increaseRatio = Arbitraries.integers().between(1, 300)
                .map(i -> BigDecimal.valueOf(i, 2));
        Arbitrary<BigDecimal> decreaseRatio = Arbitraries.integers().between(1, 100)
                .map(i -> BigDecimal.valueOf(i, 2));

        Arbitrary<Boolean> enableCreate = Arbitraries.of(true, false);

        return Combinators.combine(
                        Combinators.combine(budget, cost, conversionValue, clicks, conversions, status)
                                .as(CampaignInputs::new),
                        targetAcos, minBudget, maxDelta, increaseRatio, decreaseRatio, enableCreate)
                .as((inputs, tAcos, minB, delta, incR, decR, create) -> {
                    BigDecimal maxB = minB.add(delta);
                    SafetyBoundary boundary = SafetyBoundaryResolver.resolve(null, null, null,
                            SafetyBoundaryLimits.builder()
                                    .minDailyBudget(minB)
                                    .maxDailyBudget(maxB)
                                    .maxDailyBudgetIncreaseRatio(incR)
                                    .maxDailyBudgetDecreaseRatio(decR)
                                    .build());

                    GoogleAdsCampaignVo campaign = GoogleAdsCampaignVo.builder()
                            .campaignId("c-" + UUID.randomUUID())
                            .name("campaign")
                            .status(inputs.status())
                            .budget(inputs.budget())
                            .cost(inputs.cost())
                            .conversionValue(inputs.conversionValue().signum() == 0 ? null : inputs.conversionValue())
                            .impressions(inputs.clicks() * 10)
                            .clicks(inputs.clicks())
                            .conversions(inputs.conversions())
                            .build();

                    GoogleAdsHostingContext ctx = new GoogleAdsHostingContext(
                            tAcos, 14, "balanced", "v1.0",
                            BigDecimal.ZERO, incR, decR,
                            new BigDecimal("0.30"), create, new BigDecimal("0.50"));

                    return new Scenario(campaign, boundary, ctx);
                });
    }

    private static Arbitrary<BigDecimal> cents(int minCents, int maxCents) {
        return Arbitraries.integers().between(minCents, maxCents)
                .map(c -> BigDecimal.valueOf(c, 2));
    }

    /** Raw campaign performance inputs generated for a scenario. */
    record CampaignInputs(BigDecimal budget, BigDecimal cost, BigDecimal conversionValue,
                          long clicks, double conversions, String status) {
    }

    /** A complete generated clamping scenario: campaign data + resolved boundary + policy context. */
    record Scenario(GoogleAdsCampaignVo campaign, SafetyBoundary boundary, GoogleAdsHostingContext ctx) {
    }
}
