package com.adpilot.modules.apisync.service;

import com.adpilot.modules.apisync.entity.ApiSyncJobEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Canonical "most-recent-first" ordering for sync history (Req 1.3.5).
 *
 * <p>The sync-history query {@code GET /api/stores/{storeId}/sync-jobs} returns
 * a store's sync jobs ordered most-recent-first, which the persistence layer
 * expresses as {@code ORDER BY started_at DESC}. This class extracts that
 * ordering into a single, unit-testable definition so the rule can be verified
 * independently of the database and reused as an in-memory safety sort.</p>
 *
 * <p>Jobs that have not yet started (no {@code startedAt}) are treated as the
 * oldest and sort last, keeping the ordering total.</p>
 */
public final class SyncHistoryOrdering {

    private SyncHistoryOrdering() {
    }

    /**
     * Orders sync jobs by start time descending (most recent first); jobs with
     * no start time sort last.
     */
    public static final Comparator<ApiSyncJobEntity> MOST_RECENT_FIRST =
            Comparator.comparing(ApiSyncJobEntity::getStartedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    /**
     * Returns a new list containing the given jobs ordered most-recent-first by
     * start time. The input list is not modified.
     */
    public static List<ApiSyncJobEntity> orderMostRecentFirst(List<ApiSyncJobEntity> jobs) {
        List<ApiSyncJobEntity> ordered = new ArrayList<>(jobs);
        ordered.sort(MOST_RECENT_FIRST);
        return ordered;
    }
}
