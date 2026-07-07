package com.adpilot.modules.apisync.service;

import com.adpilot.modules.apisync.entity.ApiSyncJobEntity;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link SyncHistoryOrdering}, the canonical
 * most-recent-first ordering applied to a store's sync history (task 5.2's
 * {@code GET /api/stores/{storeId}/sync-jobs}).
 *
 * Feature: core-platform-completion, Property 7: Sync history is ordered
 * most-recent-first.
 *
 * For any set of sync jobs for a store, the history returned is ordered by
 * start time descending. The ordering must also be a faithful permutation of
 * the jobs (none added or dropped), and not-yet-started jobs sort last.
 *
 * Validates: Requirements 1.3.5
 */
class SyncHistoryOrderingPropertyTest {

    // Feature: core-platform-completion, Property 7: Sync history is ordered most-recent-first
    @Property(tries = 200)
    void historyIsOrderedByStartTimeDescending(@ForAll("jobLists") List<ApiSyncJobEntity> jobs) {
        List<ApiSyncJobEntity> ordered = SyncHistoryOrdering.orderMostRecentFirst(jobs);

        // The ordering neither adds nor drops jobs: it is a permutation of the input.
        assertThat(ordered).containsExactlyInAnyOrderElementsOf(jobs);

        // Walk the result asserting non-increasing start times, with not-yet-started
        // jobs (null start time) only ever appearing after every started job.
        boolean seenNotStarted = false;
        LocalDateTime previous = null;
        for (ApiSyncJobEntity job : ordered) {
            LocalDateTime start = job.getStartedAt();
            if (start == null) {
                seenNotStarted = true;
                continue;
            }
            assertThat(seenNotStarted)
                    .as("a started job must not appear after a not-yet-started job")
                    .isFalse();
            if (previous != null) {
                // Most-recent-first: each job starts at or before the one ahead of it.
                assertThat(start)
                        .as("each job's start time is <= the previous job's start time")
                        .isBeforeOrEqualTo(previous);
            }
            previous = start;
        }
    }

    // Feature: core-platform-completion, Property 7: Sync history is ordered most-recent-first
    @Property(tries = 200)
    void firstJobIsTheMostRecentlyStarted(@ForAll("startedJobLists") List<ApiSyncJobEntity> jobs) {
        // With every job started, the head of the history is the latest start time.
        List<ApiSyncJobEntity> ordered = SyncHistoryOrdering.orderMostRecentFirst(jobs);

        LocalDateTime latest = jobs.stream()
                .map(ApiSyncJobEntity::getStartedAt)
                .max(LocalDateTime::compareTo)
                .orElseThrow();

        assertThat(ordered.get(0).getStartedAt()).isEqualTo(latest);
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<List<ApiSyncJobEntity>> jobLists() {
        // Mostly started jobs, with ~10% not-yet-started to exercise null handling.
        Arbitrary<LocalDateTime> startOrNull = Arbitraries.frequencyOf(
                Tuple.of(9, startTimes()),
                Tuple.of(1, Arbitraries.just((LocalDateTime) null)));
        return jobWith(startOrNull).list().ofMaxSize(40);
    }

    @Provide
    Arbitrary<List<ApiSyncJobEntity>> startedJobLists() {
        return jobWith(startTimes()).list().ofMinSize(1).ofMaxSize(40);
    }

    private Arbitrary<ApiSyncJobEntity> jobWith(Arbitrary<LocalDateTime> starts) {
        return starts.map(start -> ApiSyncJobEntity.builder()
                .id(UUID.randomUUID())
                .connectionId(UUID.randomUUID())
                .syncType("incremental")
                .entityType("order")
                .status("completed")
                .startedAt(start)
                .build());
    }

    /**
     * Start times drawn from a small epoch-second window so generated jobs
     * frequently share timestamps, exercising ties and equal-time ordering.
     */
    private Arbitrary<LocalDateTime> startTimes() {
        return Arbitraries.longs().between(1_700_000_000L, 1_700_000_100L)
                .map(seconds -> LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC));
    }
}
