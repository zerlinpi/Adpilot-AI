package com.adpilot.modules.advertising.operation;

import org.springframework.stereotype.Component;

/**
 * The single, authoritative mapping from the resulting Operation's {@link SyncState} to a
 * {@link RecommendationStatus}, exactly as enumerated in Requirement 10.8.
 *
 * <p>This is a <strong>pure</strong> component: it is stateless, side-effect free, and free of
 * persistence, JSON, or scheduling concerns, so it can be unit- and property-tested in isolation
 * (Property 26, task 14.12). It is registered as a Spring {@link Component} purely so it can be
 * injected as the one shared authority; it holds no mutable state.</p>
 *
 * <p>The mapping is <strong>total</strong> — every {@link SyncState}, plus the "no applied Operation
 * yet" case ({@code null}), maps to exactly one {@link RecommendationStatus}:</p>
 *
 * <table border="1">
 *   <caption>Sync_State → Recommendation_Status (Requirement 10.8)</caption>
 *   <tr><th>Sync_State</th><th>Recommendation_Status</th></tr>
 *   <tr><td>(no applied Operation / {@code null})</td><td>{@code pending}</td></tr>
 *   <tr><td>{@code pending}</td><td>{@code applying}</td></tr>
 *   <tr><td>{@code awaiting_approval}</td><td>{@code applying}</td></tr>
 *   <tr><td>{@code submitted}</td><td>{@code applying}</td></tr>
 *   <tr><td>{@code amazon-processing}</td><td>{@code applying}</td></tr>
 *   <tr><td>{@code cancel_requested}</td><td>{@code applying}</td></tr>
 *   <tr><td>{@code expired}</td><td>{@code applying} (NEVER {@code failed})</td></tr>
 *   <tr><td>{@code reconciliation_required}</td><td>{@code applying}</td></tr>
 *   <tr><td>{@code effective}</td><td>{@code effective}</td></tr>
 *   <tr><td>{@code failed}</td><td>{@code failed} (re-actionable for retry)</td></tr>
 *   <tr><td>{@code cancelled}</td><td>{@code pending} (re-actionable)</td></tr>
 *   <tr><td>{@code superseded}</td><td>{@code pending} (re-actionable)</td></tr>
 *   <tr><td>{@code local-only}</td><td>{@code local-only} (terminal local)</td></tr>
 * </table>
 *
 * <p>Every Unsettled_State (as defined by {@link SyncState#UNSETTLED}) maps to {@code applying},
 * treating {@code applying} as the reconciling / in-progress status. In particular the non-terminal
 * {@code expired} state maps to {@code applying}, NEVER to {@code failed}. The
 * operator-driven statuses {@link RecommendationStatus#DISMISSED} and
 * {@link RecommendationStatus#WATCHING} are set on dismiss/watch-list (Requirement 10.6) and are
 * never produced by this mapping.</p>
 *
 * <p>Validates: Requirements 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8.</p>
 */
@Component
public class RecommendationStatusMapper {

    /**
     * Maps the resulting Operation's {@link SyncState} to the corresponding
     * {@link RecommendationStatus} per Requirement 10.8.
     *
     * <p>A {@code null} {@code syncState} represents a Recommendation that has no applied Operation
     * yet and maps to {@link RecommendationStatus#PENDING} (Requirement 10.8).</p>
     *
     * @param syncState the resulting Operation's Sync_State, or {@code null} when no Operation has
     *                  been applied yet
     * @return the Recommendation_Status this Sync_State corresponds to
     */
    public RecommendationStatus map(SyncState syncState) {
        // A Recommendation that has no applied Operation yet remains pending (Req 10.8).
        if (syncState == null) {
            return RecommendationStatus.PENDING;
        }

        switch (syncState) {
            case EFFECTIVE:
                // Confirmed applied on the platform (Req 10.3).
                return RecommendationStatus.EFFECTIVE;
            case FAILED:
                // Rejected/errored; kept actionable for retry (Req 10.4).
                return RecommendationStatus.FAILED;
            case CANCELLED:
            case SUPERSEDED:
                // Settled-but-not-applied; the Recommendation becomes re-actionable (Req 10.8).
                return RecommendationStatus.PENDING;
            case LOCAL_ONLY:
                // Not write-capable; a terminal local state, never effective (Req 10.5).
                return RecommendationStatus.LOCAL_ONLY;
            default:
                // Every remaining state is an Unsettled_State (pending, awaiting_approval, submitted,
                // amazon-processing, cancel_requested, expired, reconciliation_required) and maps to
                // applying — in particular expired NEVER maps to failed (Req 10.2, 10.8).
                if (syncState.isUnsettled()) {
                    return RecommendationStatus.APPLYING;
                }
                // Defensive: a new settled Sync_State added without updating this mapping.
                throw new IllegalStateException(
                        "No Recommendation_Status mapping defined for Sync_State: " + syncState);
        }
    }
}
