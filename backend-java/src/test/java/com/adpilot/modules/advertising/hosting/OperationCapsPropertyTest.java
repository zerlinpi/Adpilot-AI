package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.operation.InFlightConflictLock;
import com.adpilot.modules.advertising.service.HostingAdjustmentType;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryResolver;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based test for the {@link OptimizationCoordinator}'s enforcement of
 * operation caps ({@code maxOperationsPerRun} and {@code maxOperationsPerDay}).
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 28: Coordinator enforces operation caps
 *
 * <p><b>Validates: Requirements 23.3</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>The number of surviving candidates from a single run never exceeds
 *       {@code maxOperationsPerRun}.</li>
 *   <li>The total operations created across multiple runs in a day never exceeds
 *       {@code maxOperationsPerDay}.</li>
 *   <li>When there are more candidates than the cap allows, the coordinator selects
 *       by priority (lowest risk first).</li>
 *   <li>The caps are enforced regardless of how many candidates the engines produce.</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 28: Coordinator enforces operation caps")
class OperationCapsPropertyTest {

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Builds a coordinator with no in-flight conflicts and a no-op routing pipeline
     * (always returns PENDING_OPERATION so all survivors are counted).
     */
    private OptimizationCoordinatorImpl buildCoordinator() {
        InFlightConflictLock conflictLock = mock(InFlightConflictLock.class);
        when(conflictLock.hasInFlightOperation(any(), any(), any())).thenReturn(false);

        DecisionRoutingPipeline pipeline = mock(DecisionRoutingPipeline.class);
        when(pipeline.route(any())).thenReturn(
                RoutingResult.pending("auto_execute_below_threshold"));

        return new OptimizationCoordinatorImpl(conflictLock, pipeline);
    }

    /**
     * Builds a SafetyBoundary with the specified operation caps and generous
     * bid/budget bounds so no candidate is clipped to a no-op.
     */
    private SafetyBoundary buildBoundary(int maxPerRun, int maxPerDay) {
        SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                .maxOperationsPerRun(BigDecimal.valueOf(maxPerRun))
                .maxOperationsPerDay(BigDecimal.valueOf(maxPerDay))
                .minBid(new BigDecimal("0.01"))
                .maxBid(new BigDecimal("100.00"))
                .maxCpc(new BigDecimal("100.00"))
                .maxBidAdjustmentRatio(new BigDecimal("5.00"))
                .minDailyBudget(new BigDecimal("1.00"))
                .maxDailyBudget(new BigDecimal("100000.00"))
                .maxDailyBudgetIncreaseRatio(new BigDecimal("5.00"))
                .maxDailyBudgetDecreaseRatio(new BigDecimal("0.90"))
                .build();
        return SafetyBoundaryResolver.resolve(null, null, null, limits);
    }

    /**
     * Creates a list of bid-increase candidate decisions with distinct risk scores.
     * Each candidate has a unique risk score in [0.01, 0.99] assigned sequentially so
     * priority ordering is predictable.
     */
    private List<CandidateDecision> buildCandidates(int count) {
        List<CandidateDecision> candidates = new ArrayList<>();
        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        for (int i = 0; i < count; i++) {
            // Assign risk scores in descending order so sorting by lowest-risk picks the last-added first
            // This makes priority verification straightforward
            BigDecimal riskScore = new BigDecimal("0.01")
                    .add(new BigDecimal(i).multiply(new BigDecimal("0.01")));
            // Clamp to [0, 1.0]
            if (riskScore.compareTo(BigDecimal.ONE) > 0) {
                riskScore = new BigDecimal("0.99");
            }
            candidates.add(new CandidateDecision(
                    UUID.randomUUID(),
                    storeId,
                    campaignId,
                    "keyword",
                    UUID.randomUUID(),
                    "bid",
                    HostingAdjustmentType.BID,
                    "bid",
                    CandidateDecision.ENGINE_V1_BID,
                    new BigDecimal("1.00"),      // beforeValue
                    new BigDecimal("1.50"),      // proposedValue (increase)
                    riskScore,
                    CandidateDecisionContext.DEFAULT_RISK_THRESHOLD,
                    new BigDecimal("0.85"),
                    "{}"
            ));
        }
        return candidates;
    }

    // ── Generators ────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<Integer> candidateCounts() {
        return Arbitraries.integers().between(1, 100);
    }

    @Provide
    Arbitrary<Integer> perRunCaps() {
        return Arbitraries.integers().between(1, 50);
    }

    @Provide
    Arbitrary<Integer> perDayCaps() {
        return Arbitraries.integers().between(1, 200);
    }

    @Provide
    Arbitrary<Integer> operationsTodayCounts() {
        return Arbitraries.integers().between(0, 150);
    }

    // ── Properties ────────────────────────────────────────────────────────────

    @Property(tries = 200)
    @Label("Survivors never exceed maxOperationsPerRun")
    void survivorsNeverExceedMaxPerRun(
            @ForAll("candidateCounts") int candidateCount,
            @ForAll("perRunCaps") int maxPerRun) {

        OptimizationCoordinatorImpl coordinator = buildCoordinator();
        // Use a very high per-day cap so per-run cap is the binding constraint
        SafetyBoundary boundary = buildBoundary(maxPerRun, 10000);
        List<CandidateDecision> candidates = buildCandidates(candidateCount);

        CoordinationResult result = coordinator.coordinate(
                candidates, boundary,
                UUID.randomUUID(), UUID.randomUUID(),
                0,      // operationsToday = 0, so per-day is not binding
                false,  // killSwitchActive
                false,  // shadowModeActive
                true,   // phaseEnabled
                ExecutionMode.AUTO_EXECUTE);

        assertThat(result.survivors().size())
                .as("Survivors (%d) must not exceed maxOperationsPerRun (%d)",
                        result.survivors().size(), maxPerRun)
                .isLessThanOrEqualTo(maxPerRun);
    }

    @Property(tries = 200)
    @Label("Survivors never exceed the remaining day budget (maxPerDay - operationsToday)")
    void survivorsNeverExceedRemainingDayBudget(
            @ForAll("candidateCounts") int candidateCount,
            @ForAll("perDayCaps") int maxPerDay,
            @ForAll("operationsTodayCounts") int operationsToday) {

        OptimizationCoordinatorImpl coordinator = buildCoordinator();
        // Use a very high per-run cap so per-day is the binding constraint
        SafetyBoundary boundary = buildBoundary(10000, maxPerDay);
        List<CandidateDecision> candidates = buildCandidates(candidateCount);

        CoordinationResult result = coordinator.coordinate(
                candidates, boundary,
                UUID.randomUUID(), UUID.randomUUID(),
                operationsToday,
                false,  // killSwitchActive
                false,  // shadowModeActive
                true,   // phaseEnabled
                ExecutionMode.AUTO_EXECUTE);

        int remainingDayBudget = Math.max(0, maxPerDay - operationsToday);
        assertThat(result.survivors().size())
                .as("Survivors (%d) must not exceed remaining day budget = max(0, %d - %d) = %d",
                        result.survivors().size(), maxPerDay, operationsToday, remainingDayBudget)
                .isLessThanOrEqualTo(remainingDayBudget);
    }

    @Property(tries = 200)
    @Label("When capped, coordinator selects lowest-risk candidates first")
    void cappedSelectionPreferencesLowestRiskFirst(
            @ForAll @IntRange(min = 3, max = 50) int candidateCount,
            @ForAll @IntRange(min = 1, max = 10) int maxPerRun) {

        // Only meaningful when candidates exceed the cap
        Assume.that(candidateCount > maxPerRun);

        OptimizationCoordinatorImpl coordinator = buildCoordinator();
        SafetyBoundary boundary = buildBoundary(maxPerRun, 10000);
        List<CandidateDecision> candidates = buildCandidates(candidateCount);

        CoordinationResult result = coordinator.coordinate(
                candidates, boundary,
                UUID.randomUUID(), UUID.randomUUID(),
                0,
                false, false, true,
                ExecutionMode.AUTO_EXECUTE);

        // Verify survivors are ordered by risk score (lowest first)
        List<BigDecimal> survivorRisks = result.survivors().stream()
                .map(s -> s.candidate().riskScore())
                .toList();

        for (int i = 1; i < survivorRisks.size(); i++) {
            assertThat(survivorRisks.get(i))
                    .as("Survivor at index %d (risk=%s) should have risk >= survivor at index %d (risk=%s)",
                            i, survivorRisks.get(i), i - 1, survivorRisks.get(i - 1))
                    .isGreaterThanOrEqualTo(survivorRisks.get(i - 1));
        }

        // All survivors should have lower or equal risk than all skipped candidates
        // that were skipped due to the cap (not due to other reasons)
        BigDecimal maxSurvivorRisk = survivorRisks.isEmpty() ? BigDecimal.ZERO
                : survivorRisks.get(survivorRisks.size() - 1);

        List<BigDecimal> capSkippedRisks = result.skipped().stream()
                .filter(s -> OptimizationCoordinatorImpl.SKIP_MAX_OPERATIONS_PER_RUN.equals(s.reason())
                        || OptimizationCoordinatorImpl.SKIP_MAX_OPERATIONS_PER_DAY.equals(s.reason()))
                .map(s -> s.candidate().riskScore())
                .toList();

        for (BigDecimal skippedRisk : capSkippedRisks) {
            assertThat(skippedRisk)
                    .as("Skipped candidate risk (%s) should be >= max survivor risk (%s)",
                            skippedRisk, maxSurvivorRisk)
                    .isGreaterThanOrEqualTo(maxSurvivorRisk);
        }
    }

    @Property(tries = 200)
    @Label("Caps are enforced regardless of candidate count — effectiveMax = min(perRun, perDay - today)")
    void capsEnforcedRegardlessOfCandidateCount(
            @ForAll("candidateCounts") int candidateCount,
            @ForAll("perRunCaps") int maxPerRun,
            @ForAll("perDayCaps") int maxPerDay,
            @ForAll("operationsTodayCounts") int operationsToday) {

        OptimizationCoordinatorImpl coordinator = buildCoordinator();
        SafetyBoundary boundary = buildBoundary(maxPerRun, maxPerDay);
        List<CandidateDecision> candidates = buildCandidates(candidateCount);

        CoordinationResult result = coordinator.coordinate(
                candidates, boundary,
                UUID.randomUUID(), UUID.randomUUID(),
                operationsToday,
                false, false, true,
                ExecutionMode.AUTO_EXECUTE);

        int remainingDayBudget = Math.max(0, maxPerDay - operationsToday);
        int effectiveMax = Math.min(maxPerRun, remainingDayBudget);

        // Survivors must not exceed the effective cap
        assertThat(result.survivors().size())
                .as("Survivors (%d) must not exceed effectiveMax = min(%d, max(0, %d - %d)) = %d",
                        result.survivors().size(), maxPerRun, maxPerDay, operationsToday, effectiveMax)
                .isLessThanOrEqualTo(effectiveMax);

        // Total processed should equal candidates count (all are accounted for)
        assertThat(result.totalProcessed())
                .as("Total processed should account for all candidates")
                .isEqualTo(candidateCount);
    }

    @Property(tries = 100)
    @Label("When operationsToday >= maxPerDay, no candidates survive")
    void noCandidatesSurviveWhenDayBudgetExhausted(
            @ForAll @IntRange(min = 1, max = 50) int candidateCount,
            @ForAll @IntRange(min = 1, max = 100) int maxPerDay) {

        // operationsToday is already at or beyond the day cap
        int operationsToday = maxPerDay + Arbitraries.integers().between(0, 50).sample();

        OptimizationCoordinatorImpl coordinator = buildCoordinator();
        SafetyBoundary boundary = buildBoundary(50, maxPerDay);
        List<CandidateDecision> candidates = buildCandidates(candidateCount);

        CoordinationResult result = coordinator.coordinate(
                candidates, boundary,
                UUID.randomUUID(), UUID.randomUUID(),
                operationsToday,
                false, false, true,
                ExecutionMode.AUTO_EXECUTE);

        assertThat(result.survivors())
                .as("No candidates should survive when operationsToday (%d) >= maxPerDay (%d)",
                        operationsToday, maxPerDay)
                .isEmpty();
    }
}
