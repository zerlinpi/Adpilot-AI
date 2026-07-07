package com.adpilot.modules.advertising.operation;

import net.jqwik.api.*;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based test validating that the verify timeout is non-terminal.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 4: Verify timeout is non-terminal
 *
 * <p><b>Validates: Requirements 20.4</b>
 *
 * <p>When verification cannot read the entity or the value is unchanged past the configured retries,
 * the Operation transitions to {@code expired} with statusReason "VERIFY_TIMEOUT". However,
 * {@code expired} is NOT terminal — a later callback or successful re-read may still transition it
 * to {@code effective}, {@code failed}, or {@code reconciliation_required}.
 *
 * <p>Properties tested:
 * <ol>
 *   <li>A {@code submitted} Operation can reach {@code expired} via the TIMEOUT event
 *       (simulating read failure past retries / verify timeout).</li>
 *   <li>An {@code expired} Operation can still transition to {@code effective} via PLATFORM_EFFECTIVE
 *       (a later successful re-read or callback confirms the change applied).</li>
 *   <li>An {@code expired} Operation can still transition to {@code failed} via PLATFORM_FAILED
 *       (a later callback or re-read confirms the platform rejected the change).</li>
 *   <li>An {@code expired} Operation can still transition to {@code reconciliation_required} via
 *       RECONCILE (a later re-read shows a value mismatch requiring reconciliation).</li>
 *   <li>The TIMEOUT event that produces {@code expired} does NOT prevent any of the three
 *       subsequent resolution transitions from being legal — the state machine permits all three
 *       regardless of how the Operation arrived at expired.</li>
 *   <li>{@code expired} is in the UNSETTLED set — it is never treated as a final settled outcome.</li>
 * </ol>
 *
 * <p>The test exercises the real {@link OperationStateMachine} (the single transition authority)
 * directly, verifying the structural property that expired is non-terminal for any combination of
 * pre-expired path and post-expired resolution event.
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 4: Verify timeout is non-terminal")
class VerifyTimeoutNonTerminalPropertyTest {

    /** Real production component — the single authority for every legal Sync_State transition. */
    private final OperationStateMachine stateMachine = new OperationStateMachine();

    /**
     * The states from which a TIMEOUT event is legal (these are the states that can reach expired
     * via a verification timeout).
     */
    private static final Set<SyncState> TIMEOUT_SOURCES =
            EnumSet.of(SyncState.SUBMITTED, SyncState.AMAZON_PROCESSING);

    /**
     * The three resolution events that MUST remain legal from the expired state (Req 20.4).
     * These represent the three ways an expired Operation can be resolved later.
     */
    private static final Set<TransitionEvent> POST_EXPIRED_RESOLUTION_EVENTS =
            EnumSet.of(TransitionEvent.PLATFORM_EFFECTIVE, TransitionEvent.PLATFORM_FAILED,
                    TransitionEvent.RECONCILE);

    /**
     * The three resolution states reachable from expired (Req 20.4):
     * effective, failed, reconciliation_required.
     */
    private static final Set<SyncState> POST_EXPIRED_RESOLUTIONS =
            EnumSet.of(SyncState.EFFECTIVE, SyncState.FAILED, SyncState.RECONCILIATION_REQUIRED);

    /**
     * True terminal states (no outgoing transitions).
     */
    private static final Set<SyncState> TERMINAL_STATES =
            EnumSet.of(SyncState.EFFECTIVE, SyncState.CANCELLED, SyncState.SUPERSEDED, SyncState.LOCAL_ONLY);

    // ────────────────────────────────────────────────────────────────────────────
    // Property 4a: TIMEOUT from submitted/amazon-processing reaches expired
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 4: Verify timeout is non-terminal
     *
     * <p><b>Validates: Requirements 20.4</b>
     *
     * <p>For any state that supports the TIMEOUT event (submitted, amazon-processing),
     * the TIMEOUT event always transitions to exactly the {@code expired} state.
     * This confirms that read failure / unchanged value past retries reaches expired.
     */
    @Property(tries = 200)
    @Label("Property 4a: TIMEOUT from submitted/amazon-processing always reaches expired")
    void timeoutFromSubmittedOrProcessingReachesExpired(
            @ForAll("timeoutSourceStates") SyncState source) {

        SyncState result = stateMachine.transition(source, TransitionEvent.TIMEOUT);

        assertThat(result)
                .as("TIMEOUT from %s must transition to EXPIRED (Req 20.4)", source)
                .isEqualTo(SyncState.EXPIRED);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 4b: expired is NOT terminal — all three resolution events are legal
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 4: Verify timeout is non-terminal
     *
     * <p><b>Validates: Requirements 20.4</b>
     *
     * <p>For any of the three post-expired resolution events (PLATFORM_EFFECTIVE, PLATFORM_FAILED,
     * RECONCILE), the transition from {@code expired} is legal and reaches the expected target state.
     * This confirms that expired does NOT prevent subsequent verification from resolving the
     * Operation.
     */
    @Property(tries = 200)
    @Label("Property 4b: expired allows subsequent resolution to effective/failed/reconciliation_required")
    void expiredAllowsSubsequentResolution(
            @ForAll("postExpiredEvents") TransitionEvent resolutionEvent) {

        // First, confirm the transition is legal (does not throw).
        assertThatCode(() -> stateMachine.transition(SyncState.EXPIRED, resolutionEvent))
                .as("Resolution event %s must be legal from EXPIRED (Req 20.4)", resolutionEvent)
                .doesNotThrowAnyException();

        SyncState result = stateMachine.transition(SyncState.EXPIRED, resolutionEvent);

        // The result must be one of the three expected resolution states.
        assertThat(POST_EXPIRED_RESOLUTIONS)
                .as("Resolution from EXPIRED via %s must reach one of effective/failed/reconciliation_required",
                        resolutionEvent)
                .contains(result);

        // Verify the specific mappings (the state machine contract):
        switch (resolutionEvent) {
            case PLATFORM_EFFECTIVE -> assertThat(result).isEqualTo(SyncState.EFFECTIVE);
            case PLATFORM_FAILED -> assertThat(result).isEqualTo(SyncState.FAILED);
            case RECONCILE -> assertThat(result).isEqualTo(SyncState.RECONCILIATION_REQUIRED);
            default -> throw new AssertionError("Unexpected event: " + resolutionEvent);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 4c: full path submitted → expired → resolution is always legal
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 4: Verify timeout is non-terminal
     *
     * <p><b>Validates: Requirements 20.4</b>
     *
     * <p>For any pre-expired source (submitted/amazon-processing) and any post-expired resolution
     * event, the full two-step path source → expired → resolution is legal through the state machine.
     * This confirms end-to-end that a verify timeout followed by a later successful/failed/mismatch
     * re-read correctly resolves the Operation.
     */
    @Property(tries = 200)
    @Label("Property 4c: full path source → expired → resolution is always legal")
    void fullPathFromSourceThroughExpiredToResolutionIsLegal(
            @ForAll("timeoutSourceStates") SyncState source,
            @ForAll("postExpiredEvents") TransitionEvent resolutionEvent) {

        // Step 1: transition to expired via TIMEOUT (simulates verify timeout / read failure past retries)
        SyncState expired = stateMachine.transition(source, TransitionEvent.TIMEOUT);
        assertThat(expired).isEqualTo(SyncState.EXPIRED);

        // Step 2: a later callback or re-read resolves the expired Operation
        SyncState finalState = stateMachine.transition(expired, resolutionEvent);

        // The final state must be one of the three valid resolution targets
        assertThat(POST_EXPIRED_RESOLUTIONS)
                .as("After verify timeout from %s, resolution via %s must reach effective/failed/reconciliation_required",
                        source, resolutionEvent)
                .contains(finalState);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 4d: expired is NOT in the terminal state set
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 4: Verify timeout is non-terminal
     *
     * <p><b>Validates: Requirements 20.4</b>
     *
     * <p>For any source state that can reach expired, the resulting expired state is:
     * (a) in the UNSETTLED set (it blocks in-flight conflicts and surfaces in pending overlay),
     * (b) NOT in the terminal state set (it has outgoing transitions), and
     * (c) has exactly three legal target states (effective, failed, reconciliation_required).
     */
    @Property(tries = 200)
    @Label("Property 4d: expired is unsettled, not terminal, with exactly 3 outgoing targets")
    void expiredIsUnsettledAndNonTerminal(
            @ForAll("timeoutSourceStates") SyncState source) {

        SyncState expired = stateMachine.transition(source, TransitionEvent.TIMEOUT);
        assertThat(expired).isEqualTo(SyncState.EXPIRED);

        // expired is in the UNSETTLED set (it has not reached a final outcome)
        assertThat(SyncState.EXPIRED.isUnsettled())
                .as("EXPIRED must be in the UNSETTLED set (Req 20.4: outcome is not final)")
                .isTrue();

        // expired is NOT terminal (it has outgoing transitions)
        assertThat(TERMINAL_STATES)
                .as("EXPIRED must NOT be in the terminal state set")
                .doesNotContain(SyncState.EXPIRED);

        // expired has exactly the three expected legal targets
        Set<SyncState> legalTargets = stateMachine.legalTargets(SyncState.EXPIRED);
        assertThat(legalTargets)
                .as("EXPIRED must have exactly 3 legal targets: effective, failed, reconciliation_required")
                .hasSize(3)
                .containsExactlyInAnyOrder(
                        SyncState.EFFECTIVE,
                        SyncState.FAILED,
                        SyncState.RECONCILIATION_REQUIRED);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 4e: no retry/cancel/approval events are legal from expired
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 4: Verify timeout is non-terminal
     *
     * <p><b>Validates: Requirements 20.4</b>
     *
     * <p>No retry, cancel, approval, or submission event is legal from expired. The only way
     * to resolve an expired Operation is through a platform query/callback (PLATFORM_EFFECTIVE,
     * PLATFORM_FAILED, or RECONCILE). This ensures that verify timeout does not accidentally
     * enable re-submission without first determining the platform's actual state.
     */
    @Property(tries = 200)
    @Label("Property 4e: only resolution events are legal from expired (no retry/cancel/submit)")
    void onlyResolutionEventsLegalFromExpired(
            @ForAll("forbiddenFromExpiredEvents") TransitionEvent forbiddenEvent) {

        assertThatThrownBy(() -> stateMachine.transition(SyncState.EXPIRED, forbiddenEvent))
                .as("Event %s must NOT be legal from EXPIRED — only resolution events are allowed",
                        forbiddenEvent)
                .isInstanceOf(IllegalStateException.class);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Generators
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * States from which TIMEOUT is legal, representing an in-flight Operation that could
     * experience a verify timeout.
     */
    @Provide
    Arbitrary<SyncState> timeoutSourceStates() {
        return Arbitraries.of(SyncState.SUBMITTED, SyncState.AMAZON_PROCESSING);
    }

    /**
     * The three resolution events that must be legal from the expired state (Req 20.4).
     */
    @Provide
    Arbitrary<TransitionEvent> postExpiredEvents() {
        return Arbitraries.of(
                TransitionEvent.PLATFORM_EFFECTIVE,
                TransitionEvent.PLATFORM_FAILED,
                TransitionEvent.RECONCILE);
    }

    /**
     * Events that must NOT be legal from the expired state — expired can only be resolved
     * via a platform query result, never retried, submitted, cancelled, or approved directly.
     */
    @Provide
    Arbitrary<TransitionEvent> forbiddenFromExpiredEvents() {
        return Arbitraries.of(
                TransitionEvent.RETRY,
                TransitionEvent.SUBMIT,
                TransitionEvent.APPROVE,
                TransitionEvent.REJECT,
                TransitionEvent.CANCEL,
                TransitionEvent.SUPERSEDE,
                TransitionEvent.REQUEST_APPROVAL,
                TransitionEvent.REQUEST_CANCEL,
                TransitionEvent.TIMEOUT,
                TransitionEvent.PLATFORM_PROCESSING,
                TransitionEvent.PLATFORM_ACCEPTED,
                TransitionEvent.RETRYABLE_REJECT,
                TransitionEvent.PERMANENT_REJECT,
                TransitionEvent.CANCEL_CONFIRMED);
    }
}
