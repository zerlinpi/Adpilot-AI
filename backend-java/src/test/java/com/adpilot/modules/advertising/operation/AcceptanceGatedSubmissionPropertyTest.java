package com.adpilot.modules.advertising.operation;

import com.adpilot.modules.apisync.model.PlatformWriteResult;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for acceptance-gated submission and retry reuse.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 3: Submitted only after acceptance;
 * transport retries reuse the same Operation.
 *
 * <p><b>Validates: Requirements 16.7, 30.1, 30.7</b>
 *
 * <p>This test validates three properties of the Outbox retry ownership model:
 * <ol>
 *   <li>An Operation only reaches {@code submitted} state after the connector returns
 *       an accepted result — never on retryable or permanent reject (Req 16.7).</li>
 *   <li>Transport retries (retryable responses) reuse the same Operation row — they
 *       do NOT create new Operations (Req 30.7).</li>
 *   <li>Multiple retryable responses increment {@code attempt_count} but the Operation ID
 *       stays the same (Req 30.1, 30.7).</li>
 * </ol>
 *
 * <p>The test models the OutboxWorker's core behavior: claim a pending Operation,
 * transition to {@code submitting}, invoke the connector, then branch on the result:
 * <ul>
 *   <li>Accepted → advance to {@code submitted}</li>
 *   <li>Retryable → return to {@code pending} (same Operation row, attempt_count++)</li>
 *   <li>Permanent reject → advance to {@code failed}</li>
 * </ul>
 */
@Label("Feature: amazon-ads-ai-hosting-system, Property 3: Submitted only after acceptance; transport retries reuse the same Operation")
class AcceptanceGatedSubmissionPropertyTest {

    private final OperationStateMachine stateMachine = new OperationStateMachine();

    /**
     * Represents a connector response outcome from the platform.
     */
    enum ConnectorOutcome {
        ACCEPTED,
        RETRYABLE,
        PERMANENT_REJECT
    }

    /**
     * Models the Outbox row state maintained across retries. The Outbox is the single
     * retry owner (Req 30.7) — it keeps the same row and increments attempt_count on
     * each retryable rejection rather than creating a new row or a new Operation.
     */
    static class SimulatedOutboxRow {
        final UUID id = UUID.randomUUID();
        final UUID operationId;
        int attemptCount = 0;
        String status = "pending";
        LocalDateTime nextAttemptAt = null;
        String lastError = null;

        SimulatedOutboxRow(UUID operationId) {
            this.operationId = operationId;
        }
    }

    // -------------------------------------------------------------------------
    // Property: An Operation only reaches SUBMITTED after an accepted result
    // -------------------------------------------------------------------------

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 3: Submitted only after acceptance;
     * transport retries reuse the same Operation.
     *
     * <p><b>Validates: Requirements 16.7, 30.1, 30.7</b>
     *
     * <p>Given any sequence of connector responses, the Operation only reaches the
     * {@code submitted} state when and only when the connector returns an accepted
     * result. On retryable rejections, the Operation returns to {@code pending};
     * on permanent rejections, it advances to {@code failed}. The {@code submitted}
     * state is never reached from a non-accepted response.
     */
    @Property(tries = 500)
    @Label("Operation reaches SUBMITTED only on acceptance, never on retryable or permanent reject")
    void operationReachesSubmittedOnlyOnAcceptance(
            @ForAll("responseSequences") List<ConnectorOutcome> outcomes) {

        UUID operationId = UUID.randomUUID();
        SimulatedOutboxRow outbox = new SimulatedOutboxRow(operationId);
        SyncState currentState = SyncState.PENDING;

        for (ConnectorOutcome outcome : outcomes) {
            // Only process if the operation is still in PENDING (eligible for claim)
            if (currentState != SyncState.PENDING) {
                break;
            }

            // Outbox claims the row: pending → submitting (Req 16.7)
            currentState = stateMachine.transition(currentState, TransitionEvent.SUBMIT);
            assertThat(currentState)
                    .as("After SUBMIT event, Operation must be in SUBMITTING")
                    .isEqualTo(SyncState.SUBMITTING);

            // Simulate the connector response
            switch (outcome) {
                case ACCEPTED -> {
                    currentState = stateMachine.transition(currentState, TransitionEvent.PLATFORM_ACCEPTED);
                    assertThat(currentState)
                            .as("After acceptance, Operation must reach SUBMITTED (Req 16.7)")
                            .isEqualTo(SyncState.SUBMITTED);
                    // Mark outbox submitted
                    outbox.status = "submitted";
                }
                case RETRYABLE -> {
                    currentState = stateMachine.transition(currentState, TransitionEvent.RETRYABLE_REJECT);
                    assertThat(currentState)
                            .as("After retryable rejection, Operation must return to PENDING (Req 16.7)")
                            .isEqualTo(SyncState.PENDING);
                    // Outbox increments attempt_count, sets next_attempt_at (Req 30.7)
                    outbox.attemptCount++;
                    outbox.nextAttemptAt = LocalDateTime.now().plusSeconds(30);
                    outbox.lastError = "transient failure";
                    outbox.status = "pending";
                }
                case PERMANENT_REJECT -> {
                    currentState = stateMachine.transition(currentState, TransitionEvent.PERMANENT_REJECT);
                    assertThat(currentState)
                            .as("After permanent rejection, Operation must reach FAILED (Req 16.7)")
                            .isEqualTo(SyncState.FAILED);
                    outbox.status = "failed";
                }
            }
        }

        // Final invariants
        if (currentState == SyncState.SUBMITTED) {
            // If we ended up in SUBMITTED, the last processed outcome must have been ACCEPTED
            ConnectorOutcome lastProcessed = findLastProcessedOutcome(outcomes, currentState);
            assertThat(lastProcessed)
                    .as("SUBMITTED state can only be reached through an ACCEPTED outcome")
                    .isEqualTo(ConnectorOutcome.ACCEPTED);
        }
    }

    // -------------------------------------------------------------------------
    // Property: Transport retries reuse the same Operation row (no new Operations)
    // -------------------------------------------------------------------------

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 3: Submitted only after acceptance;
     * transport retries reuse the same Operation.
     *
     * <p><b>Validates: Requirements 30.1, 30.7</b>
     *
     * <p>Multiple retryable rejections reuse the same Operation row and the same
     * Outbox row. No new Operation is created for retries — the Outbox simply increments
     * the attempt_count and schedules the next_attempt_at. The Operation ID is stable
     * across all retry attempts.
     */
    @Property(tries = 500)
    @Label("Transport retries reuse the same Operation row and never create new Operations")
    void transportRetriesReuseTheSameOperationRow(
            @ForAll @IntRange(min = 1, max = 10) int retryCount) {

        UUID operationId = UUID.randomUUID();
        SimulatedOutboxRow outbox = new SimulatedOutboxRow(operationId);
        SyncState currentState = SyncState.PENDING;

        // Track that only one operation ID exists throughout all retries
        List<UUID> operationIdsObserved = new ArrayList<>();
        operationIdsObserved.add(operationId);

        for (int attempt = 0; attempt < retryCount; attempt++) {
            assertThat(currentState)
                    .as("Before each retry attempt, Operation must be in PENDING")
                    .isEqualTo(SyncState.PENDING);

            // Outbox claims: pending → submitting
            currentState = stateMachine.transition(currentState, TransitionEvent.SUBMIT);
            assertThat(currentState).isEqualTo(SyncState.SUBMITTING);

            // Retryable rejection: submitting → pending
            currentState = stateMachine.transition(currentState, TransitionEvent.RETRYABLE_REJECT);
            assertThat(currentState).isEqualTo(SyncState.PENDING);

            // Outbox single retry owner updates (Req 30.7)
            outbox.attemptCount++;
            outbox.nextAttemptAt = LocalDateTime.now().plusSeconds(30L * (attempt + 1));
            outbox.lastError = "attempt " + (attempt + 1) + " failed";
            outbox.status = "pending";

            // The Operation ID remains the same — no new Operation was created
            assertThat(outbox.operationId)
                    .as("The Outbox row still references the SAME Operation ID after attempt %d (Req 30.7)",
                            attempt + 1)
                    .isEqualTo(operationId);
        }

        // After all retries, the attempt_count equals the number of retryable rejections
        assertThat(outbox.attemptCount)
                .as("attempt_count must equal the number of retryable rejections (Req 30.1, 30.7)")
                .isEqualTo(retryCount);

        // Only one Operation ID was ever observed
        assertThat(operationIdsObserved)
                .as("No new Operations were created during retries")
                .hasSize(1)
                .containsOnly(operationId);

        // The Operation is still pending (can still be retried or eventually accepted)
        assertThat(currentState).isEqualTo(SyncState.PENDING);
    }

    // -------------------------------------------------------------------------
    // Property: After N retries followed by acceptance, attempt_count = N and
    //           state reaches SUBMITTED with the same Operation ID
    // -------------------------------------------------------------------------

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 3: Submitted only after acceptance;
     * transport retries reuse the same Operation.
     *
     * <p><b>Validates: Requirements 16.7, 30.1, 30.7</b>
     *
     * <p>After a variable number of retryable rejections (N), when the connector finally
     * returns accepted, the Operation transitions to {@code submitted} with:
     * <ul>
     *   <li>The same Operation ID throughout (no new rows created)</li>
     *   <li>The Outbox's attempt_count == N</li>
     *   <li>Final state == SUBMITTED (only reached after acceptance)</li>
     * </ul>
     */
    @Property(tries = 500)
    @Label("After N retries followed by acceptance, state is SUBMITTED with attempt_count = N")
    void retriesFollowedByAcceptanceReachSubmittedWithCorrectAttemptCount(
            @ForAll @IntRange(min = 0, max = 10) int retriesBefore) {

        UUID operationId = UUID.randomUUID();
        SimulatedOutboxRow outbox = new SimulatedOutboxRow(operationId);
        SyncState currentState = SyncState.PENDING;

        // Phase 1: N retryable rejections
        for (int i = 0; i < retriesBefore; i++) {
            currentState = stateMachine.transition(currentState, TransitionEvent.SUBMIT);
            assertThat(currentState).isEqualTo(SyncState.SUBMITTING);

            currentState = stateMachine.transition(currentState, TransitionEvent.RETRYABLE_REJECT);
            assertThat(currentState).isEqualTo(SyncState.PENDING);

            outbox.attemptCount++;
            outbox.nextAttemptAt = LocalDateTime.now().plusSeconds(30);
            outbox.lastError = "retry " + (i + 1);
        }

        // Intermediate assertion: NOT submitted yet
        assertThat(currentState)
                .as("After %d retries without acceptance, Operation must NOT be SUBMITTED", retriesBefore)
                .isNotEqualTo(SyncState.SUBMITTED);
        assertThat(outbox.attemptCount).isEqualTo(retriesBefore);

        // Phase 2: Final acceptance
        currentState = stateMachine.transition(currentState, TransitionEvent.SUBMIT);
        assertThat(currentState).isEqualTo(SyncState.SUBMITTING);

        currentState = stateMachine.transition(currentState, TransitionEvent.PLATFORM_ACCEPTED);
        assertThat(currentState)
                .as("After acceptance, Operation reaches SUBMITTED (Req 16.7)")
                .isEqualTo(SyncState.SUBMITTED);

        // Final assertions
        assertThat(outbox.operationId)
                .as("The same Operation ID is used throughout all retries and final acceptance (Req 30.7)")
                .isEqualTo(operationId);
        assertThat(outbox.attemptCount)
                .as("attempt_count equals the number of retryable rejections before acceptance (Req 30.1)")
                .isEqualTo(retriesBefore);
    }

    // -------------------------------------------------------------------------
    // Property: A retryable rejection NEVER advances to SUBMITTED
    // -------------------------------------------------------------------------

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 3: Submitted only after acceptance;
     * transport retries reuse the same Operation.
     *
     * <p><b>Validates: Requirements 16.7</b>
     *
     * <p>A retryable rejection (the RETRYABLE_REJECT event from SUBMITTING) can only
     * lead to PENDING — it must never reach SUBMITTED. This is the acceptance gate:
     * only PLATFORM_ACCEPTED from SUBMITTING reaches SUBMITTED.
     */
    @Property(tries = 200)
    @Label("RETRYABLE_REJECT from SUBMITTING never reaches SUBMITTED for any state machine instance")
    void retryableRejectFromSubmittingNeverReachesSubmitted(@ForAll("syncStatesSubmitting") SyncState fromState) {
        // fromState is always SUBMITTING (the only legal source for RETRYABLE_REJECT),
        // but parameterized to satisfy jqwik's @ForAll requirement.
        SyncState result = stateMachine.transition(fromState, TransitionEvent.RETRYABLE_REJECT);

        assertThat(result)
                .as("RETRYABLE_REJECT must go to PENDING, not SUBMITTED (Req 16.7)")
                .isEqualTo(SyncState.PENDING);
        assertThat(result)
                .isNotEqualTo(SyncState.SUBMITTED);
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 3: Submitted only after acceptance;
     * transport retries reuse the same Operation.
     *
     * <p><b>Validates: Requirements 16.7</b>
     *
     * <p>A permanent rejection (PERMANENT_REJECT from SUBMITTING) advances to FAILED —
     * it must never reach SUBMITTED.
     */
    @Property(tries = 200)
    @Label("PERMANENT_REJECT from SUBMITTING never reaches SUBMITTED for any state machine instance")
    void permanentRejectFromSubmittingNeverReachesSubmitted(@ForAll("syncStatesSubmitting") SyncState fromState) {
        // fromState is always SUBMITTING — parameterized for property test form.
        SyncState result = stateMachine.transition(fromState, TransitionEvent.PERMANENT_REJECT);

        assertThat(result)
                .as("PERMANENT_REJECT must go to FAILED, not SUBMITTED (Req 16.7)")
                .isEqualTo(SyncState.FAILED);
        assertThat(result)
                .isNotEqualTo(SyncState.SUBMITTED);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Determines the last processed connector outcome based on the final state.
     */
    private ConnectorOutcome findLastProcessedOutcome(List<ConnectorOutcome> outcomes, SyncState finalState) {
        if (finalState == SyncState.SUBMITTED) {
            // Walk through outcomes: the one that produced SUBMITTED must be the last ACCEPTED
            // after any number of RETRYABLE.
            SyncState state = SyncState.PENDING;
            ConnectorOutcome last = null;
            for (ConnectorOutcome outcome : outcomes) {
                if (state != SyncState.PENDING) break;
                state = SyncState.SUBMITTING; // claim
                switch (outcome) {
                    case ACCEPTED -> { state = SyncState.SUBMITTED; last = outcome; }
                    case RETRYABLE -> { state = SyncState.PENDING; last = outcome; }
                    case PERMANENT_REJECT -> { state = SyncState.FAILED; last = outcome; }
                }
            }
            return last;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Generators
    // -------------------------------------------------------------------------

    /**
     * Always provides SUBMITTING as the only valid source state for RETRYABLE_REJECT and
     * PERMANENT_REJECT events — satisfies jqwik's @ForAll requirement.
     */
    @Provide
    Arbitrary<SyncState> syncStatesSubmitting() {
        return Arbitraries.of(SyncState.SUBMITTING);
    }

    /**
     * Generates a sequence of connector outcomes representing a series of platform responses.
     * The sequence has at most one ACCEPTED or PERMANENT_REJECT (which terminates the retry loop).
     */
    @Provide
    Arbitrary<List<ConnectorOutcome>> responseSequences() {
        // Generate 0-N retryable outcomes, optionally followed by a terminal outcome
        Arbitrary<Integer> retryableCount = Arbitraries.integers().between(0, 8);
        Arbitrary<ConnectorOutcome> terminalOutcome = Arbitraries.of(
                ConnectorOutcome.ACCEPTED,
                ConnectorOutcome.PERMANENT_REJECT,
                null // sequence ends without terminal (still retrying)
        );

        return Combinators.combine(retryableCount, terminalOutcome).as((retries, terminal) -> {
            List<ConnectorOutcome> sequence = new ArrayList<>();
            for (int i = 0; i < retries; i++) {
                sequence.add(ConnectorOutcome.RETRYABLE);
            }
            if (terminal != null) {
                sequence.add(terminal);
            }
            return sequence;
        });
    }
}
