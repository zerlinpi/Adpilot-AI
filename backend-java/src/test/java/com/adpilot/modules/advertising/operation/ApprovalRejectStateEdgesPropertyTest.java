package com.adpilot.modules.advertising.operation;

import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.NotEmpty;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based test for approval and rejection state edges in the
 * {@link OperationStateMachine}.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 22: Approve routes to pending,
 * reject to cancelled.
 *
 * <p><b>Validates: Requirements 7.7, 7.8, 24.2</b>
 *
 * <p>This test validates four properties of the APPROVE and REJECT events:
 * <ol>
 *   <li>APPROVE event on AWAITING_APPROVAL always transitions to PENDING (not SUBMITTED)</li>
 *   <li>REJECT event on AWAITING_APPROVAL always transitions to CANCELLED</li>
 *   <li>APPROVE is only legal from AWAITING_APPROVAL</li>
 *   <li>REJECT is only legal from AWAITING_APPROVAL</li>
 * </ol>
 *
 * <p>Requirement 7.7 specifies that approval routes an Operation to {@code pending} so the
 * Outbox can claim it for submission (not directly to {@code submitted}). Requirement 7.8
 * specifies rejection routes to {@code cancelled}. Requirement 24.2 reinforces that
 * approval transitions to pending and the Outbox row must exist at that point.
 */
@Label("Feature: amazon-ads-ai-hosting-system, Property 22: Approve routes to pending, reject to cancelled")
class ApprovalRejectStateEdgesPropertyTest {

    /** Minimum property iterations as required by the spec. */
    private static final int MIN_ITERATIONS = 100;

    /** All states OTHER than AWAITING_APPROVAL from which APPROVE/REJECT must be illegal. */
    private static final Set<SyncState> NON_AWAITING_STATES = EnumSet.complementOf(
            EnumSet.of(SyncState.AWAITING_APPROVAL));

    private final OperationStateMachine stateMachine = new OperationStateMachine();

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 22: Approve routes to pending,
     * reject to cancelled.
     *
     * <p><b>Validates: Requirements 7.7, 24.2</b>
     *
     * <p>APPROVE event on AWAITING_APPROVAL always transitions to PENDING (not SUBMITTED).
     * This ensures an approved Operation goes back into the Outbox queue for submission
     * rather than skipping directly to the submitted state.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("APPROVE on AWAITING_APPROVAL always transitions to PENDING")
    void approveOnAwaitingApprovalAlwaysTransitionsToPending() {
        SyncState result = stateMachine.transition(SyncState.AWAITING_APPROVAL, TransitionEvent.APPROVE);

        assertThat(result)
                .as("APPROVE from AWAITING_APPROVAL must land on PENDING (Req 7.7, 24.2)")
                .isEqualTo(SyncState.PENDING);

        // Explicitly verify it does NOT go to SUBMITTED
        assertThat(result)
                .as("APPROVE must NOT route directly to SUBMITTED")
                .isNotEqualTo(SyncState.SUBMITTED);
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 22: Approve routes to pending,
     * reject to cancelled.
     *
     * <p><b>Validates: Requirements 7.8</b>
     *
     * <p>REJECT event on AWAITING_APPROVAL always transitions to CANCELLED.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("REJECT on AWAITING_APPROVAL always transitions to CANCELLED")
    void rejectOnAwaitingApprovalAlwaysTransitionsToCancelled() {
        SyncState result = stateMachine.transition(SyncState.AWAITING_APPROVAL, TransitionEvent.REJECT);

        assertThat(result)
                .as("REJECT from AWAITING_APPROVAL must land on CANCELLED (Req 7.8)")
                .isEqualTo(SyncState.CANCELLED);
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 22: Approve routes to pending,
     * reject to cancelled.
     *
     * <p><b>Validates: Requirements 7.7, 24.2</b>
     *
     * <p>APPROVE is only legal from AWAITING_APPROVAL. Applying APPROVE from any other
     * state must throw an {@link IllegalStateException}, confirming that the approval
     * workflow is exclusive to operations that have been explicitly held for review.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("APPROVE is only legal from AWAITING_APPROVAL")
    void approveIsOnlyLegalFromAwaitingApproval(@ForAll SyncState from) {
        if (from == SyncState.AWAITING_APPROVAL) {
            // Legal: must succeed and return PENDING
            SyncState result = stateMachine.transition(from, TransitionEvent.APPROVE);
            assertThat(result).isEqualTo(SyncState.PENDING);
        } else {
            // Illegal: must throw
            assertThatThrownBy(() -> stateMachine.transition(from, TransitionEvent.APPROVE))
                    .as("APPROVE from %s must be illegal", from)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 22: Approve routes to pending,
     * reject to cancelled.
     *
     * <p><b>Validates: Requirements 7.8</b>
     *
     * <p>REJECT is only legal from AWAITING_APPROVAL. Applying REJECT from any other
     * state must throw an {@link IllegalStateException}, confirming that rejection is
     * exclusive to operations awaiting approval.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("REJECT is only legal from AWAITING_APPROVAL")
    void rejectIsOnlyLegalFromAwaitingApproval(@ForAll SyncState from) {
        if (from == SyncState.AWAITING_APPROVAL) {
            // Legal: must succeed and return CANCELLED
            SyncState result = stateMachine.transition(from, TransitionEvent.REJECT);
            assertThat(result).isEqualTo(SyncState.CANCELLED);
        } else {
            // Illegal: must throw
            assertThatThrownBy(() -> stateMachine.transition(from, TransitionEvent.REJECT))
                    .as("REJECT from %s must be illegal", from)
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
