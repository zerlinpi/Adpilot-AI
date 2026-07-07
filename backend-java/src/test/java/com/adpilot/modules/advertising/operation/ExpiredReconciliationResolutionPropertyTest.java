package com.adpilot.modules.advertising.operation;

import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformWriteResult;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Property-based test for the resolution of {@code expired} and {@code reconciliation_required}
 * Operations through a platform status query.
 *
 * <p>Feature: advertising-workspace-rework, Property 8: expired and reconciliation_required resolve
 * via platform query.
 *
 * <p>Validates: Requirements 4.10, 56.5.
 *
 * <p><b>Property.</b> <em>For any</em> Operation in {@code expired} or {@code reconciliation_required},
 * the system queries the platform's actual state BEFORE any retry and resolves to exactly one of
 * {@code effective}, {@code failed}, or {@code cancelled} according to the reported state, never
 * immediately retries, and {@code expired} never maps directly to a terminal failure without a
 * platform query.
 *
 * <p><b>Subject.</b> The resolution is governed by two real, side-effect-free production components:
 * the {@link PlatformWriteConnector#mapPlatformStatus(String) connector status mapping} (Req 55.4 —
 * the result of a {@code queryStatus} platform read) and the {@link OperationStateMachine} (Req 4.1 —
 * the single authority for every legal Sync_State transition). The {@code StatusPoller}/{@code
 * TimeoutSweeper} that schedules the query is the orchestrator of these two components; this test
 * drives the same resolution sequence the poller must perform and asserts that the real state
 * machine permits <em>exactly</em> the spec-mandated resolutions and forbids any immediate retry.
 *
 * <p>For each generated start state ({@code expired} or {@code reconciliation_required}) and arbitrary
 * raw platform status string, the test (1) maps the status to a Sync_State via the real connector
 * (the platform query), (2) drives the resolution through the real state machine, and (3) asserts:
 * the resolution reaches exactly one of {@code effective}/{@code failed}/{@code cancelled} when the
 * platform reports a determinate state (or holds in {@code reconciliation_required} when the platform
 * state cannot yet be determined); no intermediate state is a re-attempt ({@code pending}/{@code
 * submitted}/{@code amazon-processing}); and {@code expired} reaches {@code failed} only via a
 * platform-query failure event, never via the automatic timeout event.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 8: expired and reconciliation_required resolve via platform query")
class ExpiredReconciliationResolutionPropertyTest {

    private static final int MIN_ITERATIONS = 200;

    /** Real production components — the single transition authority and the platform status mapping. */
    private final OperationStateMachine stateMachine = new OperationStateMachine();
    private final PlatformWriteConnector connector = new MinimalConnector();

    /** The two Sync_States that resolve only through a platform query (Req 4.10, 56.5). */
    private static final Set<SyncState> QUERY_RESOLVED_STATES =
            EnumSet.of(SyncState.EXPIRED, SyncState.RECONCILIATION_REQUIRED);

    /** The states that would represent a re-attempt; resolution must never visit any of them. */
    private static final Set<SyncState> RETRY_STATES =
            EnumSet.of(SyncState.PENDING, SyncState.SUBMITTED, SyncState.AMAZON_PROCESSING);

    /** The exactly-three terminal resolutions a platform query may produce (Req 56.5). */
    private static final Set<SyncState> TERMINAL_RESOLUTIONS =
            EnumSet.of(SyncState.EFFECTIVE, SyncState.FAILED, SyncState.CANCELLED);

    /**
     * Feature: advertising-workspace-rework, Property 8: expired and reconciliation_required resolve
     * via platform query.
     *
     * <p>Validates: Requirements 4.10, 56.5.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 8: an expired/reconciliation_required Operation resolves to effective/failed/cancelled via a platform query, never an immediate retry")
    void expiredAndReconciliationRequiredResolveViaPlatformQuery(
            @ForAll("queryResolvedStates") SyncState start,
            @ForAll("platformStatuses") String rawPlatformStatus) {

        // --- Guard: the start state is genuinely one that requires reconciliation, and neither it
        //     nor any retry can be reached automatically out of it. No retry event is ever legal. ---
        assertThat(QUERY_RESOLVED_STATES).contains(start);
        assertNoImmediateRetryIsLegal(start);

        // --- Step 1: query the platform's actual state (Req 4.10 "query BEFORE any retry"). ---
        // The connector mapping is the resolved result of a queryStatus read (Req 55.4); it is total
        // and never throws, so a query always yields a single decision input.
        SyncState[] reportedHolder = new SyncState[1];
        assertThatCode(() -> reportedHolder[0] = connector.mapPlatformStatus(rawPlatformStatus))
                .as("the platform query (mapPlatformStatus) must never throw")
                .doesNotThrowAnyException();
        SyncState reported = reportedHolder[0];
        assertThat(reported).as("a platform query always yields exactly one Sync_State").isNotNull();

        // --- Step 2: drive the resolution through the REAL state machine, recording every state. ---
        Resolution resolution = resolve(start, reported);
        List<SyncState> visited = resolution.visited();

        // --- Invariant A: never an immediate retry. No state on the resolution path is a re-attempt
        //     (pending/submitted/amazon-processing). The poller queries; it does not re-submit. ---
        for (SyncState s : visited) {
            assertThat(RETRY_STATES)
                    .as("resolution of %s (reported=%s) must never re-attempt via %s", start, reported, s)
                    .doesNotContain(s);
        }

        SyncState finalState = resolution.finalState();

        // --- Invariant B: outcome is exactly one of effective/failed/cancelled per the reported
        //     state, OR (only when the platform state cannot yet be determined) it holds in
        //     reconciliation_required awaiting a later determinate query — never a silent failure. ---
        switch (reportedCategory(reported)) {
            case APPLIED -> assertThat(finalState)
                    .as("platform reports the change applied → effective (reported=%s)", reported)
                    .isEqualTo(SyncState.EFFECTIVE);
            case FAILED -> assertThat(finalState)
                    .as("platform reports the change did not apply/errored → failed (reported=%s)", reported)
                    .isEqualTo(SyncState.FAILED);
            case NOT_APPLIED_CANCELLED -> assertThat(finalState)
                    .as("platform reports the change was not applied → cancelled (reported=%s)", reported)
                    .isEqualTo(SyncState.CANCELLED);
            case INDETERMINATE -> assertThat(finalState)
                    .as("platform state not yet determinable → hold in reconciliation_required, never failed (reported=%s)", reported)
                    .isEqualTo(SyncState.RECONCILIATION_REQUIRED);
        }

        // A determinate query always resolves to EXACTLY one of the three terminal resolutions.
        if (reportedCategory(reported) != ReportedCategory.INDETERMINATE) {
            assertThat(TERMINAL_RESOLUTIONS)
                    .as("a determinate platform query resolves to exactly effective/failed/cancelled")
                    .contains(finalState);
        }

        // --- Invariant C: expired NEVER maps directly to failure without a platform query. When the
        //     start is expired and the outcome is failed, it was reached by the platform-query failure
        //     event (PLATFORM_FAILED), and only because the platform itself reported failure. ---
        if (start == SyncState.EXPIRED && finalState == SyncState.FAILED) {
            assertThat(resolution.events())
                    .as("expired→failed must be driven by a platform-query failure, never an automatic timeout")
                    .contains(TransitionEvent.PLATFORM_FAILED)
                    .doesNotContain(TransitionEvent.TIMEOUT);
            assertThat(reportedCategory(reported))
                    .as("expired→failed only when the platform query itself reported failure")
                    .isEqualTo(ReportedCategory.FAILED);
        }
    }

    // --- resolution model (the StatusPoller's required behaviour, validated by the real machine) ---

    /**
     * Drives the platform-query resolution of {@code start} given the platform-reported state,
     * applying each step through the REAL {@link OperationStateMachine}. Every transition is the
     * machine's own decision: an illegal step would throw and fail the property. The returned
     * {@link Resolution} captures the full path so the test can assert the no-retry and
     * no-direct-failure invariants.
     */
    private Resolution resolve(SyncState start, SyncState reported) {
        List<SyncState> visited = new ArrayList<>();
        List<TransitionEvent> events = new ArrayList<>();
        SyncState current = start;
        visited.add(current);

        switch (reportedCategory(reported)) {
            case APPLIED -> current = step(current, TransitionEvent.PLATFORM_EFFECTIVE, visited, events);
            case FAILED -> current = step(current, TransitionEvent.PLATFORM_FAILED, visited, events);
            case NOT_APPLIED_CANCELLED -> {
                // expired cannot go directly to cancelled; it first reconciles, then confirms the
                // platform never applied the change. reconciliation_required confirms directly.
                if (current == SyncState.EXPIRED) {
                    current = step(current, TransitionEvent.RECONCILE, visited, events);
                }
                current = step(current, TransitionEvent.CANCEL_CONFIRMED, visited, events);
            }
            case INDETERMINATE -> {
                // The platform's actual state cannot be determined yet: hold for reconciliation
                // rather than retry or fail. expired moves into reconciliation_required; an
                // already-reconciling Operation stays put until a later determinate query.
                if (current == SyncState.EXPIRED) {
                    current = step(current, TransitionEvent.RECONCILE, visited, events);
                }
            }
        }
        return new Resolution(visited, events, current);
    }

    /** Applies one event through the real state machine, recording the event and the new state. */
    private SyncState step(SyncState from, TransitionEvent event,
                           List<SyncState> visited, List<TransitionEvent> events) {
        SyncState to = stateMachine.transition(from, event);
        events.add(event);
        visited.add(to);
        return to;
    }

    /**
     * Classifies the platform-reported Sync_State into the resolution decision the spec mandates
     * (Req 4.10): change applied, did-not-apply/errored, not-applied (cancelled), or not-yet-knowable.
     */
    private ReportedCategory reportedCategory(SyncState reported) {
        return switch (reported) {
            case EFFECTIVE -> ReportedCategory.APPLIED;
            case FAILED -> ReportedCategory.FAILED;
            case CANCELLED -> ReportedCategory.NOT_APPLIED_CANCELLED;
            // SUBMITTED / AMAZON_PROCESSING / RECONCILIATION_REQUIRED (and the conservative unknown
            // mapping) all mean the platform's final state is not yet determinable.
            default -> ReportedCategory.INDETERMINATE;
        };
    }

    /** Asserts no event that would re-attempt or auto-expire is legal out of a query-resolved state. */
    private void assertNoImmediateRetryIsLegal(SyncState start) {
        assertThat(stateMachine.legalTargets(start))
                .as("a query-resolved state never transitions directly into a re-attempt")
                .doesNotContain(SyncState.PENDING, SyncState.SUBMITTED, SyncState.AMAZON_PROCESSING);
        for (TransitionEvent forbidden : List.of(TransitionEvent.RETRY, TransitionEvent.SUBMIT,
                TransitionEvent.REQUEST_APPROVAL, TransitionEvent.APPROVE, TransitionEvent.TIMEOUT)) {
            assertThat(isLegal(start, forbidden))
                    .as("event %s must not be legal from %s (no immediate retry / no auto-expiry-to-failure)",
                            forbidden, start)
                    .isFalse();
        }
    }

    private boolean isLegal(SyncState from, TransitionEvent event) {
        try {
            stateMachine.transition(from, event);
            return true;
        } catch (IllegalStateException notLegal) {
            return false;
        }
    }

    private enum ReportedCategory { APPLIED, FAILED, NOT_APPLIED_CANCELLED, INDETERMINATE }

    /** The recorded outcome of a resolution: the path of states, the events applied, and the result. */
    private record Resolution(List<SyncState> visited, List<TransitionEvent> events, SyncState finalState) {}

    // --- generators --------------------------------------------------------

    /** The two Sync_States that, per Req 4.10/56.5, resolve only through a platform query. */
    @Provide
    Arbitrary<SyncState> queryResolvedStates() {
        return Arbitraries.of(SyncState.EXPIRED, SyncState.RECONCILIATION_REQUIRED);
    }

    /**
     * Arbitrary raw platform status strings spanning the whole input space the connector's status
     * mapping must handle: recognized success/failure/cancel/in-flight statuses (in varied casing and
     * padding), {@code null}, blank, and arbitrary unicode noise (which conservatively maps to
     * reconciliation_required).
     */
    @Provide
    Arbitrary<String> platformStatuses() {
        Arbitrary<String> known = Arbitraries.of(
                "SUCCESS", "succeeded", "Completed", " applied ", "EFFECTIVE", "enabled", "ACTIVE",
                "PENDING", "queued", "submitted", "ACCEPTED",
                "IN_PROGRESS", "processing", "running",
                "FAILED", "failure", "error", "rejected", "INVALID",
                "CANCELLED", "canceled", "aborted");
        Arbitrary<String> edge = Arbitraries.of("", "   ", "unknown", "???", "状态未知", "42");
        Arbitrary<String> noise = Arbitraries.strings().ofMaxLength(12);
        Arbitrary<String> nullable = Arbitraries.just(null);
        return Arbitraries.oneOf(known, known, edge, noise, nullable);
    }

    // --- minimal connector exposing the real default status mapping ---------

    /**
     * A minimal {@link PlatformWriteConnector} implementing only the required members so it relies on
     * the real default {@link PlatformWriteConnector#mapPlatformStatus(String)} contract under test.
     * {@code submit} is never invoked by this test (it queries, it does not write).
     */
    private static final class MinimalConnector implements PlatformWriteConnector {
        @Override
        public String platform() {
            return "test_platform";
        }

        @Override
        public PlatformWriteResult submit(ConnectionContext ctx, PlatformChange change) {
            throw new UnsupportedOperationException("submit must not be called during status resolution");
        }
    }
}
