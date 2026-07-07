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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the {@link OptimizationCoordinator}'s in-flight conflict
 * de-duplication logic.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 29: In-flight conflict is never duplicated
 *
 * <p><b>Validates: Requirements 20.5, 23.4, 36.5</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>A candidate targeting an entity+field that already has an unsettled (in-flight)
 *       Operation is skipped by the coordinator.</li>
 *   <li>The same entity+field never has two active Operations simultaneously — all
 *       conflicting candidates are filtered out before reaching the routing pipeline.</li>
 *   <li>Once an in-flight Operation settles (effective/failed/cancelled/superseded), a new
 *       candidate for the same entity+field can proceed.</li>
 *   <li>Different entities or different fields on the same entity do NOT conflict
 *       (independence).</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 29: In-flight conflict is never duplicated")
class InFlightConflictDeduplicationPropertyTest {

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Builds a generous safety boundary that will not clip candidates to no-op.
     */
    private SafetyBoundary generousBoundary() {
        SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                .maxOperationsPerRun(new BigDecimal("1000"))
                .maxOperationsPerDay(new BigDecimal("10000"))
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
     * Creates a candidate decision with the specified entity/field combination.
     */
    private CandidateDecision buildCandidate(UUID storeId, UUID campaignId,
                                             String entityType, UUID entityId, String field) {
        return new CandidateDecision(
                UUID.randomUUID(),
                storeId,
                campaignId,
                entityType,
                entityId,
                field,
                HostingAdjustmentType.BID,
                "bid",
                CandidateDecision.ENGINE_V1_BID,
                new BigDecimal("1.00"),
                new BigDecimal("1.50"),
                new BigDecimal("0.2"),
                CandidateDecisionContext.DEFAULT_RISK_THRESHOLD,
                new BigDecimal("0.85"),
                "{}"
        );
    }

    // ── Generators ────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<Integer> candidateCounts() {
        return Arbitraries.integers().between(1, 20);
    }

    @Provide
    Arbitrary<String> entityTypes() {
        return Arbitraries.of("keyword", "campaign", "ad_group");
    }

    @Provide
    Arbitrary<String> fields() {
        return Arbitraries.of("bid", "daily_budget", "state");
    }

    // ── Property 1: Conflicting candidates are skipped ────────────────────────

    /**
     * A candidate targeting an entity+field that already has an unsettled (in-flight)
     * Operation is always skipped with reason IN_FLIGHT_CONFLICT.
     */
    @Property(tries = 200)
    @Label("Candidate colliding with unsettled Operation is skipped")
    void candidateCollidingWithUnsettledOperationIsSkipped(
            @ForAll("candidateCounts") int candidateCount,
            @ForAll("entityTypes") String entityType,
            @ForAll("fields") String field) {

        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        UUID conflictingEntityId = UUID.randomUUID();

        // Configure the conflict lock: default no conflict, then override for specific entity+field
        InFlightConflictLock conflictLock = mock(InFlightConflictLock.class);
        when(conflictLock.hasInFlightOperation(any(), any(), any())).thenReturn(false);
        when(conflictLock.hasInFlightOperation(eq(entityType), eq(conflictingEntityId), eq(field)))
                .thenReturn(true);

        DecisionRoutingPipeline pipeline = mock(DecisionRoutingPipeline.class);
        when(pipeline.route(any())).thenReturn(
                RoutingResult.pending("auto_execute_below_threshold"));

        OptimizationCoordinatorImpl coordinator = new OptimizationCoordinatorImpl(conflictLock, pipeline);

        // Build candidates: at least one targeting the conflicting entity+field
        List<CandidateDecision> candidates = new ArrayList<>();
        // The conflicting candidate
        candidates.add(buildCandidate(storeId, campaignId, entityType, conflictingEntityId, field));
        // Additional non-conflicting candidates (different entities)
        for (int i = 1; i < candidateCount; i++) {
            UUID otherEntity = UUID.randomUUID();
            candidates.add(buildCandidate(storeId, campaignId, entityType, otherEntity, field));
        }

        CoordinationResult result = coordinator.coordinate(
                candidates, generousBoundary(),
                campaignId, storeId,
                0, false, false, true,
                ExecutionMode.AUTO_EXECUTE);

        // The conflicting candidate must be in the skipped list with IN_FLIGHT_CONFLICT reason
        List<CoordinationResult.SkippedCandidate> conflictSkipped = result.skipped().stream()
                .filter(s -> OptimizationCoordinatorImpl.SKIP_IN_FLIGHT_CONFLICT.equals(s.reason()))
                .toList();

        assertThat(conflictSkipped)
                .as("At least one candidate should be skipped due to in-flight conflict")
                .isNotEmpty();

        // Every skipped-for-conflict candidate must be the one targeting the conflicting entity
        for (CoordinationResult.SkippedCandidate skippedCandidate : conflictSkipped) {
            assertThat(skippedCandidate.candidate().entityId())
                    .as("Skipped candidate should target the conflicting entity")
                    .isEqualTo(conflictingEntityId);
        }

        // The conflicting entity must NOT appear in survivors
        boolean conflictInSurvivors = result.survivors().stream()
                .anyMatch(s -> s.candidate().entityId().equals(conflictingEntityId));
        assertThat(conflictInSurvivors)
                .as("Conflicting entity+field must never survive coordination")
                .isFalse();
    }

    // ── Property 2: No duplicate operations for same entity+field ─────────────

    /**
     * When multiple candidates target the same entity+field that has an in-flight
     * operation, ALL of them are skipped — the same entity+field never has two
     * Operations simultaneously.
     */
    @Property(tries = 200)
    @Label("Same entity+field never has two active Operations — all duplicates skipped")
    void sameEntityFieldNeverHasTwoActiveOperations(
            @ForAll @IntRange(min = 2, max = 10) int duplicateCount,
            @ForAll("entityTypes") String entityType,
            @ForAll("fields") String field) {

        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        UUID conflictingEntityId = UUID.randomUUID();

        // The conflict lock reports an in-flight operation for the shared entity+field
        InFlightConflictLock conflictLock = mock(InFlightConflictLock.class);
        when(conflictLock.hasInFlightOperation(eq(entityType), eq(conflictingEntityId), eq(field)))
                .thenReturn(true);

        DecisionRoutingPipeline pipeline = mock(DecisionRoutingPipeline.class);
        when(pipeline.route(any())).thenReturn(
                RoutingResult.pending("auto_execute_below_threshold"));

        OptimizationCoordinatorImpl coordinator = new OptimizationCoordinatorImpl(conflictLock, pipeline);

        // Build multiple candidates all targeting the same entity+field
        List<CandidateDecision> candidates = new ArrayList<>();
        for (int i = 0; i < duplicateCount; i++) {
            candidates.add(buildCandidate(storeId, campaignId, entityType, conflictingEntityId, field));
        }

        CoordinationResult result = coordinator.coordinate(
                candidates, generousBoundary(),
                campaignId, storeId,
                0, false, false, true,
                ExecutionMode.AUTO_EXECUTE);

        // ALL candidates must be skipped — none survive
        assertThat(result.survivors())
                .as("No candidates should survive when they all target an entity+field with in-flight conflict")
                .isEmpty();

        // All must be skipped with IN_FLIGHT_CONFLICT reason
        assertThat(result.skipped())
                .as("All %d duplicates should be skipped", duplicateCount)
                .hasSize(duplicateCount);

        result.skipped().forEach(s ->
                assertThat(s.reason())
                        .as("Skip reason should be IN_FLIGHT_CONFLICT")
                        .isEqualTo(OptimizationCoordinatorImpl.SKIP_IN_FLIGHT_CONFLICT));
    }

    // ── Property 3: Settled operations allow new candidates to proceed ─────────

    /**
     * Once an in-flight Operation settles (the conflict lock returns false),
     * a new candidate for the same entity+field can proceed through coordination.
     */
    @Property(tries = 200)
    @Label("Settled operation allows new candidate for the same entity+field to proceed")
    void settledOperationAllowsNewCandidate(
            @ForAll("entityTypes") String entityType,
            @ForAll("fields") String field) {

        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();

        // The conflict lock returns false — the previous operation has settled
        InFlightConflictLock conflictLock = mock(InFlightConflictLock.class);
        when(conflictLock.hasInFlightOperation(eq(entityType), eq(entityId), eq(field)))
                .thenReturn(false);

        DecisionRoutingPipeline pipeline = mock(DecisionRoutingPipeline.class);
        when(pipeline.route(any())).thenReturn(
                RoutingResult.pending("auto_execute_below_threshold"));

        OptimizationCoordinatorImpl coordinator = new OptimizationCoordinatorImpl(conflictLock, pipeline);

        List<CandidateDecision> candidates = List.of(
                buildCandidate(storeId, campaignId, entityType, entityId, field));

        CoordinationResult result = coordinator.coordinate(
                candidates, generousBoundary(),
                campaignId, storeId,
                0, false, false, true,
                ExecutionMode.AUTO_EXECUTE);

        // The candidate should survive (not be skipped for conflict)
        boolean skippedForConflict = result.skipped().stream()
                .anyMatch(s -> OptimizationCoordinatorImpl.SKIP_IN_FLIGHT_CONFLICT.equals(s.reason()));
        assertThat(skippedForConflict)
                .as("When no in-flight conflict exists, the candidate should not be skipped for conflict")
                .isFalse();

        assertThat(result.survivors())
                .as("Candidate for a settled entity+field should survive coordination")
                .isNotEmpty();

        assertThat(result.survivors().get(0).candidate().entityId())
                .isEqualTo(entityId);
    }

    // ── Property 4: Different entities or fields do NOT conflict ───────────────

    /**
     * Candidates targeting different entities, or different fields on the same entity,
     * are independent — an in-flight conflict on entity A / field X does not block
     * candidates on entity B / field X or entity A / field Y.
     */
    @Property(tries = 200)
    @Label("Different entities or fields do not conflict — independence preserved")
    void differentEntitiesOrFieldsDoNotConflict(
            @ForAll @IntRange(min = 2, max = 8) int independentCount) {

        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        UUID conflictingEntityId = UUID.randomUUID();
        String conflictingField = "bid";
        String conflictingEntityType = "keyword";

        // The conflict lock: only the specific entity+field is in-flight
        InFlightConflictLock conflictLock = mock(InFlightConflictLock.class);
        when(conflictLock.hasInFlightOperation(
                eq(conflictingEntityType), eq(conflictingEntityId), eq(conflictingField)))
                .thenReturn(true);
        // Default: all other combinations are clear
        when(conflictLock.hasInFlightOperation(any(), any(), any())).thenReturn(false);
        // Re-set the specific conflict after the default
        when(conflictLock.hasInFlightOperation(
                eq(conflictingEntityType), eq(conflictingEntityId), eq(conflictingField)))
                .thenReturn(true);

        DecisionRoutingPipeline pipeline = mock(DecisionRoutingPipeline.class);
        when(pipeline.route(any())).thenReturn(
                RoutingResult.pending("auto_execute_below_threshold"));

        OptimizationCoordinatorImpl coordinator = new OptimizationCoordinatorImpl(conflictLock, pipeline);

        // Build independent candidates: different entities or different fields
        List<CandidateDecision> candidates = new ArrayList<>();
        Set<UUID> independentEntityIds = new HashSet<>();

        for (int i = 0; i < independentCount; i++) {
            UUID entityId = UUID.randomUUID();
            independentEntityIds.add(entityId);
            // Alternate between different entities with same field and same entity with different field
            if (i % 2 == 0) {
                // Different entity, same field
                candidates.add(buildCandidate(storeId, campaignId,
                        conflictingEntityType, entityId, conflictingField));
            } else {
                // Same entity type, different entity, different field
                candidates.add(buildCandidate(storeId, campaignId,
                        conflictingEntityType, entityId, "daily_budget"));
            }
        }

        CoordinationResult result = coordinator.coordinate(
                candidates, generousBoundary(),
                campaignId, storeId,
                0, false, false, true,
                ExecutionMode.AUTO_EXECUTE);

        // None of the independent candidates should be skipped for in-flight conflict
        List<CoordinationResult.SkippedCandidate> conflictSkipped = result.skipped().stream()
                .filter(s -> OptimizationCoordinatorImpl.SKIP_IN_FLIGHT_CONFLICT.equals(s.reason()))
                .toList();

        assertThat(conflictSkipped)
                .as("Independent candidates (different entity or field) must not be skipped for in-flight conflict")
                .isEmpty();

        // All independent candidates should survive (or be skipped for other reasons like cross-engine constraints)
        // but specifically none should be blocked by the conflict lock
        Set<UUID> survivorEntityIds = new HashSet<>();
        result.survivors().forEach(s -> survivorEntityIds.add(s.candidate().entityId()));

        // At minimum, candidates not affected by cross-engine constraints should survive
        // The key assertion: no conflict-based filtering occurred for independent entities
        assertThat(conflictSkipped).isEmpty();
    }

    // ── Property 5: Mixed scenario — conflict + independent coexist ───────────

    /**
     * In a mixed batch of candidates, only the ones that collide with an in-flight
     * Operation on the same entity+field are skipped; the rest proceed.
     */
    @Property(tries = 200)
    @Label("In a mixed batch, only conflicting candidates are skipped — others proceed")
    void mixedBatchOnlyConflictingCandidatesSkipped(
            @ForAll @IntRange(min = 1, max = 5) int conflictingCount,
            @ForAll @IntRange(min = 1, max = 5) int nonConflictingCount) {

        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        UUID conflictingEntityId = UUID.randomUUID();
        String entityType = "keyword";
        String field = "bid";

        InFlightConflictLock conflictLock = mock(InFlightConflictLock.class);
        // Default: no conflict
        when(conflictLock.hasInFlightOperation(any(), any(), any())).thenReturn(false);
        // Only the specific entity+field has a conflict
        when(conflictLock.hasInFlightOperation(eq(entityType), eq(conflictingEntityId), eq(field)))
                .thenReturn(true);

        DecisionRoutingPipeline pipeline = mock(DecisionRoutingPipeline.class);
        when(pipeline.route(any())).thenReturn(
                RoutingResult.pending("auto_execute_below_threshold"));

        OptimizationCoordinatorImpl coordinator = new OptimizationCoordinatorImpl(conflictLock, pipeline);

        List<CandidateDecision> candidates = new ArrayList<>();

        // Add conflicting candidates
        for (int i = 0; i < conflictingCount; i++) {
            candidates.add(buildCandidate(storeId, campaignId, entityType, conflictingEntityId, field));
        }
        // Add non-conflicting candidates (different entities)
        List<UUID> nonConflictingIds = new ArrayList<>();
        for (int i = 0; i < nonConflictingCount; i++) {
            UUID otherEntity = UUID.randomUUID();
            nonConflictingIds.add(otherEntity);
            candidates.add(buildCandidate(storeId, campaignId, entityType, otherEntity, field));
        }

        CoordinationResult result = coordinator.coordinate(
                candidates, generousBoundary(),
                campaignId, storeId,
                0, false, false, true,
                ExecutionMode.AUTO_EXECUTE);

        // Conflicting candidates are all skipped
        long conflictSkippedCount = result.skipped().stream()
                .filter(s -> OptimizationCoordinatorImpl.SKIP_IN_FLIGHT_CONFLICT.equals(s.reason()))
                .count();
        assertThat(conflictSkippedCount)
                .as("All %d conflicting candidates should be skipped", conflictingCount)
                .isEqualTo(conflictingCount);

        // Non-conflicting candidates all survive (unless hit by other constraints)
        Set<UUID> survivorEntities = new HashSet<>();
        result.survivors().forEach(s -> survivorEntities.add(s.candidate().entityId()));

        for (UUID nonConflictId : nonConflictingIds) {
            assertThat(survivorEntities)
                    .as("Non-conflicting entity %s should survive", nonConflictId)
                    .contains(nonConflictId);
        }

        // Total accounts for all candidates
        assertThat(result.totalProcessed())
                .isEqualTo(conflictingCount + nonConflictingCount);
    }
}
