package com.adpilot.modules.advertising.operation;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the pure {@link RecommendationStatusMapper}.
 *
 * <p>Feature: advertising-workspace-rework, Property 26: Recommendation_Status mapping is total
 * and exact.
 *
 * <p>Validates: Requirements 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8.
 *
 * <p>For any Sync_State the resulting Recommendation_Status equals the Requirement 10.8 mapping:
 * every Unsettled_State maps to {@code applying}; {@code effective} maps to {@code effective};
 * {@code failed} maps to {@code failed}; {@code cancelled} and {@code superseded} map to
 * {@code pending}; {@code local-only} maps to {@code local-only}; a Recommendation with no applied
 * Operation ({@code null}) maps to {@code pending}; and {@code expired} never maps to
 * {@code failed}.
 *
 * <p>The mapping is also <strong>total</strong>: every {@link SyncState}, plus the {@code null}
 * "no applied Operation" case, maps to exactly one {@link RecommendationStatus} and never throws.
 */
class RecommendationStatusMappingPropertyTest {

    private final RecommendationStatusMapper mapper = new RecommendationStatusMapper();

    /**
     * The Requirement 10.8 expected mapping, computed independently of the subject's switch so the
     * property is an exact, redundant oracle rather than a restatement of the implementation.
     */
    private static RecommendationStatus expected(SyncState syncState) {
        if (syncState == null) {
            return RecommendationStatus.PENDING;
        }
        return switch (syncState) {
            case EFFECTIVE -> RecommendationStatus.EFFECTIVE;
            case FAILED -> RecommendationStatus.FAILED;
            case CANCELLED, SUPERSEDED -> RecommendationStatus.PENDING;
            case LOCAL_ONLY -> RecommendationStatus.LOCAL_ONLY;
            // Every Unsettled_State (pending, awaiting_approval, submitting, submitted,
            // amazon-processing, cancel_requested, expired, reconciliation_required) reconciles as applying.
            case PENDING, AWAITING_APPROVAL, SUBMITTING, SUBMITTED, AMAZON_PROCESSING,
                 CANCEL_REQUESTED, EXPIRED, RECONCILIATION_REQUIRED -> RecommendationStatus.APPLYING;
        };
    }

    /** Every Sync_State, plus null, for total-coverage generation. */
    @Provide
    Arbitrary<SyncState> syncStatesIncludingNull() {
        return Arbitraries.of(SyncState.class).injectNull(0.1);
    }

    /**
     * Feature: advertising-workspace-rework, Property 26: Recommendation_Status mapping is total
     * and exact.
     *
     * <p>Validates: Requirements 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8.
     *
     * <p>For any Sync_State (and the null "no applied Operation" case), the mapper returns exactly
     * the Requirement 10.8 status and never throws.
     */
    @Property(tries = 1000, generation = GenerationMode.RANDOMIZED)
    void mappingMatchesRequirement108ForEverySyncState(
            @ForAll("syncStatesIncludingNull") SyncState syncState) {
        assertThat(mapper.map(syncState)).isEqualTo(expected(syncState));
    }

    /**
     * Feature: advertising-workspace-rework, Property 26: Recommendation_Status mapping is total
     * and exact.
     *
     * <p>Validates: Requirements 10.2, 10.8.
     *
     * <p>Every Unsettled_State maps to {@code applying} (the reconciling / in-progress status).
     */
    @Property(tries = 200, generation = GenerationMode.RANDOMIZED)
    void everyUnsettledStateMapsToApplying(
            @ForAll("unsettledStates") SyncState unsettled) {
        assertThat(mapper.map(unsettled)).isEqualTo(RecommendationStatus.APPLYING);
    }

    @Provide
    Arbitrary<SyncState> unsettledStates() {
        return Arbitraries.of(SyncState.UNSETTLED.toArray(new SyncState[0]));
    }

    /**
     * Feature: advertising-workspace-rework, Property 26: Recommendation_Status mapping is total
     * and exact.
     *
     * <p>Validates: Requirement 10.8.
     *
     * <p>The non-terminal {@code expired} state NEVER maps to {@code failed}; it reconciles as
     * {@code applying}.
     */
    @Property(tries = 200, generation = GenerationMode.RANDOMIZED)
    void expiredNeverMapsToFailed(@ForAll("syncStatesIncludingNull") SyncState syncState) {
        if (syncState == SyncState.EXPIRED) {
            assertThat(mapper.map(syncState))
                    .isNotEqualTo(RecommendationStatus.FAILED)
                    .isEqualTo(RecommendationStatus.APPLYING);
        }
    }

    /**
     * Feature: advertising-workspace-rework, Property 26: Recommendation_Status mapping is total
     * and exact.
     *
     * <p>Validates: Requirements 10.1, 10.5, 10.8.
     *
     * <p>Totality: the mapping is defined for every declared {@link SyncState} and for null, never
     * throws, and only ever produces a Sync_State-derived status (never the operator-only
     * {@code dismissed}/{@code watching}).</p>
     */
    @Test
    void mappingIsTotalAndNeverProducesOperatorOnlyStatuses() {
        Set<RecommendationStatus> operatorOnly =
                EnumSet.of(RecommendationStatus.DISMISSED, RecommendationStatus.WATCHING);

        // null (no applied Operation) is covered.
        assertThat(mapper.map(null)).isEqualTo(RecommendationStatus.PENDING);

        for (SyncState state : SyncState.values()) {
            RecommendationStatus status = mapper.map(state);
            assertThat(status)
                    .as("mapping must be defined for %s", state)
                    .isNotNull()
                    .isEqualTo(expected(state));
            assertThat(status)
                    .as("Sync_State %s must not map to an operator-only status", state)
                    .isNotIn(operatorOnly);
        }
    }
}
