package com.adpilot.modules.advertising.operation;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link OperationStateMachine}.
 *
 * <p>Feature: advertising-workspace-rework, Property 4: Only legal Sync_State transitions are
 * permitted.
 *
 * <p>Validates: Requirements 4.1, 3.1.
 *
 * <p>For any pair of Sync_States {@code (from, to)}, {@link OperationStateMachine#canTransition}
 * returns {@code true} if and only if {@code (from, to)} is one of the transitions enumerated in
 * Requirement 4.1; every other transition is rejected.
 *
 * <p>The oracle below ({@link #LEGAL_EDGES}) is encoded <strong>independently</strong> from the
 * production transition table — directly from the prose of Requirement 4.1 in
 * {@code requirements.md} — so the test cannot trivially agree with the implementation by sharing
 * its data. The property then asserts {@code canTransition} agrees with this oracle for ALL
 * {@code (from, to)} pairs across the full Sync_State × Sync_State space.
 */
@Label("Feature: advertising-workspace-rework, Property 4: Only legal Sync_State transitions are permitted")
class OperationStateMachinePropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    /**
     * The legal Sync_State edges, transcribed independently from Requirement 4.1 and extended with
     * Requirement 16.7 (SUBMITTING state) and Requirements 7.7/24.2 (APPROVE → PENDING):
     *
     * <ul>
     *   <li>{@code pending} → {@code awaiting_approval} | {@code submitting} | {@code cancelled} | {@code superseded}</li>
     *   <li>{@code awaiting_approval} → {@code pending} (approve) | {@code cancelled} | {@code superseded}</li>
     *   <li>{@code submitting} → {@code submitted} (accepted) | {@code pending} (retryable) | {@code failed} (permanent reject)</li>
     *   <li>{@code submitted} → {@code amazon-processing} | {@code effective} | {@code failed} | {@code expired} | {@code cancel_requested}</li>
     *   <li>{@code amazon-processing} → {@code effective} | {@code failed} | {@code expired} | {@code cancel_requested}</li>
     *   <li>{@code cancel_requested} → {@code cancelled} | {@code effective} | {@code reconciliation_required}</li>
     *   <li>{@code reconciliation_required} → {@code effective} | {@code failed} | {@code cancelled}</li>
     *   <li>{@code expired} → {@code effective} | {@code failed} | {@code reconciliation_required}</li>
     *   <li>a retry path from {@code failed} that creates a NEW {@code pending} attempt</li>
     *   <li>{@code local-only}, {@code effective}, {@code cancelled}, {@code superseded} are terminal — no outgoing edges</li>
     * </ul>
     */
    private static final Map<SyncState, Set<SyncState>> LEGAL_EDGES = buildOracle();

    private static Map<SyncState, Set<SyncState>> buildOracle() {
        EnumMap<SyncState, Set<SyncState>> edges = new EnumMap<>(SyncState.class);

        edges.put(SyncState.PENDING, EnumSet.of(
                SyncState.AWAITING_APPROVAL,
                SyncState.SUBMITTING,
                SyncState.CANCELLED,
                SyncState.SUPERSEDED));

        edges.put(SyncState.AWAITING_APPROVAL, EnumSet.of(
                SyncState.PENDING,
                SyncState.CANCELLED,
                SyncState.SUPERSEDED));

        // Req 16.7: SUBMITTING is a transient state representing "platform call in progress"
        edges.put(SyncState.SUBMITTING, EnumSet.of(
                SyncState.SUBMITTED,
                SyncState.PENDING,
                SyncState.FAILED));

        edges.put(SyncState.SUBMITTED, EnumSet.of(
                SyncState.AMAZON_PROCESSING,
                SyncState.EFFECTIVE,
                SyncState.FAILED,
                SyncState.EXPIRED,
                SyncState.CANCEL_REQUESTED));

        edges.put(SyncState.AMAZON_PROCESSING, EnumSet.of(
                SyncState.EFFECTIVE,
                SyncState.FAILED,
                SyncState.EXPIRED,
                SyncState.CANCEL_REQUESTED));

        edges.put(SyncState.CANCEL_REQUESTED, EnumSet.of(
                SyncState.CANCELLED,
                SyncState.EFFECTIVE,
                SyncState.RECONCILIATION_REQUIRED));

        edges.put(SyncState.RECONCILIATION_REQUIRED, EnumSet.of(
                SyncState.EFFECTIVE,
                SyncState.FAILED,
                SyncState.CANCELLED));

        edges.put(SyncState.EXPIRED, EnumSet.of(
                SyncState.EFFECTIVE,
                SyncState.FAILED,
                SyncState.RECONCILIATION_REQUIRED));

        // A retry from failed creates a NEW pending attempt.
        edges.put(SyncState.FAILED, EnumSet.of(SyncState.PENDING));

        // Terminal local/settled states have no outgoing transitions.
        edges.put(SyncState.LOCAL_ONLY, EnumSet.noneOf(SyncState.class));
        edges.put(SyncState.EFFECTIVE, EnumSet.noneOf(SyncState.class));
        edges.put(SyncState.CANCELLED, EnumSet.noneOf(SyncState.class));
        edges.put(SyncState.SUPERSEDED, EnumSet.noneOf(SyncState.class));

        return edges;
    }

    private final OperationStateMachine stateMachine = new OperationStateMachine();

    /**
     * Feature: advertising-workspace-rework, Property 4: Only legal Sync_State transitions are
     * permitted.
     *
     * <p>Validates: Requirements 4.1, 3.1.
     *
     * <p>For every {@code (from, to)} pair drawn from the full Sync_State × Sync_State space,
     * {@code canTransition} is {@code true} exactly when the edge is in the independent
     * Requirement 4.1 oracle, and {@code false} for every other pair (including self-transitions and
     * any edge out of a terminal state).
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("canTransition(from, to) is true iff (from, to) is a Requirement 4.1 edge")
    void canTransitionAgreesWithRequirement41Oracle(
            @ForAll SyncState from,
            @ForAll SyncState to) {

        boolean expectedLegal = LEGAL_EDGES.get(from).contains(to);

        assertThat(stateMachine.canTransition(from, to))
                .as("canTransition(%s, %s) must equal Requirement 4.1 legality (%s)",
                        from, to, expectedLegal)
                .isEqualTo(expectedLegal);
    }
}
