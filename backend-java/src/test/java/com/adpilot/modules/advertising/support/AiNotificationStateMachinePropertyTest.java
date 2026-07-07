package com.adpilot.modules.advertising.support;

import com.adpilot.modules.advertising.support.AiNotificationStateMachine.Resolution;
import com.adpilot.modules.advertising.support.AiNotificationStateMachine.State;
import com.adpilot.modules.advertising.support.AiNotificationStateMachine.Status;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the pure {@link AiNotificationStateMachine}.
 *
 * <p>Tag: {@code Feature: app-functionality-completion, Property 7: AI
 * notification pending-to-closed transition is idempotent and terminal}
 *
 * <p>Verifies the lifecycle contract: any close (apply / confirm / reject /
 * dismiss) moves a pending item to {@code closed} carrying the matching
 * resolution, and re-applying any close to an already-closed item is a no-op —
 * the closed state and its resolution never change again.
 *
 * <p>Validates: Requirements 23.3, 23.4.
 *
 * <p>The helper is pure and deterministic, so these run against it directly
 * without a Spring context.
 */
@Label("Feature: app-functionality-completion, Property 7: AI notification pending-to-closed transition is idempotent and terminal")
class AiNotificationStateMachinePropertyTest {

    /**
     * Feature: app-functionality-completion, Property 7: AI notification
     * pending-to-closed transition is idempotent and terminal.
     *
     * <p>Req 23.3 / 23.4: closing a pending notification with any resolution
     * transitions it to {@code closed} and records exactly that resolution.
     */
    @Property(tries = 200)
    void closingPendingTransitionsToClosedWithMatchingResolution(
            @ForAll("anyResolution") Resolution resolution) {

        Status result = AiNotificationStateMachine.close(AiNotificationStateMachine.pending(), resolution);

        assertThat(result.isClosed()).isTrue();
        assertThat(result.state()).isEqualTo(State.CLOSED);
        assertThat(result.resolution()).isEqualTo(resolution);
    }

    /**
     * Feature: app-functionality-completion, Property 7: AI notification
     * pending-to-closed transition is idempotent and terminal.
     *
     * <p>Req 23.3: the apply/confirm/reject convenience closers each close a
     * pending item with their own resolution.
     */
    @Property(tries = 200)
    void convenienceClosersRecordTheirResolution(@ForAll("anyAction") Action action) {
        Status result = action.apply(AiNotificationStateMachine.pending());

        assertThat(result.isClosed()).isTrue();
        assertThat(result.resolution()).isEqualTo(action.expected());
    }

    /**
     * Feature: app-functionality-completion, Property 7: AI notification
     * pending-to-closed transition is idempotent and terminal.
     *
     * <p>Req 23.4: once closed, re-applying any close with any resolution is a
     * no-op — the resulting status is identical to the already-closed one,
     * regardless of which resolution is requested the second time.
     */
    @Property(tries = 200)
    void closedIsTerminalAndIdempotent(
            @ForAll("anyResolution") Resolution firstResolution,
            @ForAll("anyResolution") Resolution secondResolution) {

        Status closed = AiNotificationStateMachine.close(AiNotificationStateMachine.pending(), firstResolution);
        Status again = AiNotificationStateMachine.close(closed, secondResolution);

        // Terminal: state and resolution are unchanged by the second close.
        assertThat(again).isEqualTo(closed);
        assertThat(again.state()).isEqualTo(State.CLOSED);
        assertThat(again.resolution()).isEqualTo(firstResolution);
    }

    /**
     * Feature: app-functionality-completion, Property 7: AI notification
     * pending-to-closed transition is idempotent and terminal.
     *
     * <p>Req 23.4: repeating a close through any of the convenience closers on an
     * already-closed item never changes the recorded resolution, no matter how
     * many times or in what order the closers are invoked.
     */
    @Property(tries = 200)
    void repeatedConvenienceClosesNeverChangeAClosedItem(
            @ForAll("anyAction") Action initial,
            @ForAll("actionSequence") java.util.List<Action> subsequent) {

        Status closed = initial.apply(AiNotificationStateMachine.pending());

        Status current = closed;
        for (Action action : subsequent) {
            current = action.apply(current);
            assertThat(current).isEqualTo(closed);
            assertThat(current.resolution()).isEqualTo(initial.expected());
        }
    }

    // ---- Action model: the three convenience closers named by the task ----

    /** Maps a convenience closer to the resolution it must record. */
    private enum Action {
        APPLY(Resolution.APPLIED),
        CONFIRM(Resolution.CONFIRMED),
        REJECT(Resolution.REJECTED);

        private final Resolution expected;

        Action(Resolution expected) {
            this.expected = expected;
        }

        Status apply(Status current) {
            return switch (this) {
                case APPLY -> AiNotificationStateMachine.apply(current);
                case CONFIRM -> AiNotificationStateMachine.confirm(current);
                case REJECT -> AiNotificationStateMachine.reject(current);
            };
        }

        Resolution expected() {
            return expected;
        }
    }

    // ---- Generators ----

    /** Every resolution the state machine recognises, including dismissed. */
    @Provide
    Arbitrary<Resolution> anyResolution() {
        return Arbitraries.of(Resolution.values());
    }

    /** The apply/confirm/reject closers named by the task. */
    @Provide
    Arbitrary<Action> anyAction() {
        return Arbitraries.of(Action.values());
    }

    /** A non-empty sequence of repeated close attempts on a closed item. */
    @Provide
    Arbitrary<java.util.List<Action>> actionSequence() {
        return anyAction().list().ofMinSize(1).ofMaxSize(8);
    }
}
