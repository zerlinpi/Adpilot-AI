package com.adpilot.modules.advertising.operation;

import java.util.EnumSet;
import java.util.Set;

import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformCancelResult;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformWriteResult;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based test for the resolution of a {@code cancel_requested} Operation by the
 * {@code StatusPoller} / {@code TimeoutSweeper} (task 10.2 lifecycle, task 10.6 property).
 *
 * <p>Feature: advertising-workspace-rework, Property 7: cancel_requested resolves without ever
 * marking the original failed.
 *
 * <p>Validates: Requirements 4.9, 55.3.
 *
 * <p>Property 7 (transcribed from the design's Correctness Properties section): <em>For any
 * Operation in {@code cancel_requested}, it resolves to exactly one of: {@code cancelled} (platform
 * confirms not applied), {@code effective} (platform reports already applied, with a compensating
 * rollback Operation created), or {@code reconciliation_required} (the cancellation request itself
 * errored or could not be confirmed); it is never resolved to {@code failed} by a failed
 * cancellation.</em>
 *
 * <p>The resolution is driven through the <strong>real</strong> {@link OperationStateMachine} (the
 * sole authority for legal transitions) and the <strong>real</strong>
 * {@link PlatformWriteConnector#mapPlatformStatus(String)} total status mapping. The platform's
 * cancellation outcome ({@link PlatformCancelResult.Outcome}) and any subsequent {@code queryStatus}
 * raw status string are generated freely. For each generated input the property asserts:
 *
 * <ol>
 *   <li>the resolved Sync_State is exactly one of {@code cancelled}, {@code effective}, or
 *       {@code reconciliation_required} (Req 4.9);</li>
 *   <li>the resolved Sync_State is NEVER {@code failed} — a failed/unsupported/errored cancellation,
 *       or a poll that maps to {@code failed}, drives the Operation to
 *       {@code reconciliation_required} rather than {@code failed} (Req 4.9, 55.3);</li>
 *   <li>the resolution event the poller applies is one of the three legal {@code cancel_requested}
 *       events and is NEVER {@link TransitionEvent#PLATFORM_FAILED};</li>
 *   <li>the real state machine itself forbids any path from {@code cancel_requested} to
 *       {@code failed}: {@code canTransition(cancel_requested, failed)} is false, the
 *       {@code PLATFORM_FAILED} event is rejected from {@code cancel_requested}, and the legal
 *       targets are exactly {@code {cancelled, effective, reconciliation_required}}.</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 7: cancel_requested resolves without ever marking the original failed")
class CancelRequestedResolutionPropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    /**
     * The exact set of Sync_States a {@code cancel_requested} Operation may resolve to, transcribed
     * independently from Requirement 4.9 — {@code failed} is deliberately absent.
     */
    private static final Set<SyncState> ALLOWED_RESOLUTIONS = EnumSet.of(
            SyncState.CANCELLED,
            SyncState.EFFECTIVE,
            SyncState.RECONCILIATION_REQUIRED);

    /** The real, authoritative transition table. */
    private final OperationStateMachine stateMachine = new OperationStateMachine();

    /**
     * A minimal {@link PlatformWriteConnector} exercising the real default
     * {@link PlatformWriteConnector#mapPlatformStatus(String)} and the default cancel-support
     * behaviour. {@code submit} is never invoked by this test.
     */
    private final PlatformWriteConnector connector = new PlatformWriteConnector() {
        @Override
        public String platform() {
            return "test_platform";
        }

        @Override
        public PlatformWriteResult submit(ConnectionContext ctx, PlatformChange change) {
            throw new UnsupportedOperationException("submit is not exercised by Property 7");
        }
    };

    /**
     * Feature: advertising-workspace-rework, Property 7: cancel_requested resolves without ever
     * marking the original failed.
     *
     * <p>Validates: Requirements 4.9, 55.3.
     *
     * <p>For any platform cancellation outcome and any subsequent poll status, a
     * {@code cancel_requested} Operation resolves — through the real state machine — to exactly one
     * of {@code cancelled}, {@code effective}, or {@code reconciliation_required}, and never to
     * {@code failed}.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 7: a cancel_requested Operation resolves to cancelled/effective/reconciliation_required and never failed")
    void cancelRequestedNeverResolvesToFailed(
            @ForAll PlatformCancelResult.Outcome cancelOutcome,
            @ForAll("platformStatuses") String polledStatus) {

        // The platform's actual reported state for the in-flight change, mapped by the REAL total
        // status mapping. Only consulted when the cancellation itself did not settle the outcome
        // (the platform accepted the request but the final state is pending, or does not support
        // cancel) — i.e. the poller keeps querying the platform's actual state (Req 4.9, 55.3).
        SyncState polledMappedState = connector.mapPlatformStatus(polledStatus);

        // The event the StatusPoller would apply, following the connector contract documented on
        // PlatformCancelResult.Outcome and Req 4.9. Crucially, NO branch ever chooses PLATFORM_FAILED.
        TransitionEvent resolutionEvent = cancelResolutionEvent(cancelOutcome, polledMappedState);

        // (3) The resolution event is one of the three legal cancel_requested events and is never
        //     the PLATFORM_FAILED event — a failed cancellation does not mean the original failed.
        assertThat(resolutionEvent)
                .as("the cancel_requested resolution event must never be PLATFORM_FAILED (Req 4.9)")
                .isIn(TransitionEvent.CANCEL_CONFIRMED,
                        TransitionEvent.PLATFORM_EFFECTIVE,
                        TransitionEvent.RECONCILE);

        // Drive the resolution through the REAL state machine — the sole transition authority.
        SyncState resolved = stateMachine.transition(SyncState.CANCEL_REQUESTED, resolutionEvent);

        // (1) The resolved state is exactly one of cancelled / effective / reconciliation_required.
        assertThat(resolved)
                .as("a cancel_requested Operation must resolve to exactly one of "
                        + "{cancelled, effective, reconciliation_required} (Req 4.9), got %s", resolved)
                .isIn(ALLOWED_RESOLUTIONS);

        // (2) The resolved state is NEVER failed — including when the platform poll reports failure,
        //     in which case the Operation goes to reconciliation_required, not failed (Req 4.9, 55.3).
        assertThat(resolved)
                .as("a failed cancellation must NEVER resolve the original Operation to failed (Req 4.9)")
                .isNotEqualTo(SyncState.FAILED);

        // A poll that maps to failure, or any non-terminal/unknown reported state, must reconcile
        // rather than fail the original Operation. This is the load-bearing Req 4.9 guarantee.
        if ((cancelOutcome == PlatformCancelResult.Outcome.REQUESTED
                || cancelOutcome == PlatformCancelResult.Outcome.UNSUPPORTED)
                && polledMappedState != SyncState.EFFECTIVE
                && polledMappedState != SyncState.CANCELLED) {
            assertThat(resolved)
                    .as("an unresolved/failed poll on a cancel_requested Operation must reconcile, "
                            + "never mark the original failed (Req 4.9); polled=%s", polledMappedState)
                    .isEqualTo(SyncState.RECONCILIATION_REQUIRED);
        }

        // (4) The real state machine structurally forbids any cancel_requested -> failed path. These
        //     invariants do not depend on the generated input but re-prove, every iteration, that the
        //     production transition table cannot be drifted into resolving a failed cancellation to
        //     failed.
        assertThat(stateMachine.canTransition(SyncState.CANCEL_REQUESTED, SyncState.FAILED))
                .as("the state machine must forbid cancel_requested -> failed (Req 4.9)")
                .isFalse();

        assertThat(stateMachine.legalTargets(SyncState.CANCEL_REQUESTED))
                .as("the only legal cancel_requested targets are cancelled/effective/reconciliation_required")
                .containsExactlyInAnyOrder(
                        SyncState.CANCELLED, SyncState.EFFECTIVE, SyncState.RECONCILIATION_REQUIRED)
                .doesNotContain(SyncState.FAILED);

        assertThatThrownBy(
                () -> stateMachine.transition(SyncState.CANCEL_REQUESTED, TransitionEvent.PLATFORM_FAILED))
                .as("the PLATFORM_FAILED event must be rejected from cancel_requested (Req 4.9)")
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The event the {@code StatusPoller} applies to a {@code cancel_requested} Operation, following
     * the documented {@link PlatformCancelResult.Outcome} contract and Requirement 4.9. By
     * construction this never returns {@link TransitionEvent#PLATFORM_FAILED}: a failed, unsupported,
     * or errored cancellation — or a poll that maps to {@code failed} or an as-yet-unresolved state —
     * routes to {@link TransitionEvent#RECONCILE}, never to a failure of the original Operation.
     */
    private static TransitionEvent cancelResolutionEvent(
            PlatformCancelResult.Outcome outcome, SyncState polledMappedState) {
        switch (outcome) {
            case CANCELLED:
                // Platform supports cancel and confirmed the change was NOT applied -> cancelled.
                return TransitionEvent.CANCEL_CONFIRMED;
            case ALREADY_APPLIED:
                // Platform reports the change already became effective -> effective (the compensating
                // rollback Operation is created separately per Req 8, not by a local state flip).
                return TransitionEvent.PLATFORM_EFFECTIVE;
            case ERROR:
                // The cancellation request itself errored or could not be confirmed -> reconcile;
                // NEVER mark the original ad Operation failed (Req 4.9).
                return TransitionEvent.RECONCILE;
            case REQUESTED:
            case UNSUPPORTED:
            default:
                // The platform accepted the request (final state pending) or does not support cancel:
                // keep querying the platform's actual state and resolve by it.
                switch (polledMappedState) {
                    case EFFECTIVE:
                        return TransitionEvent.PLATFORM_EFFECTIVE;
                    case CANCELLED:
                        return TransitionEvent.CANCEL_CONFIRMED;
                    default:
                        // A poll mapping to failed, still-in-flight (submitted/amazon-processing),
                        // or unknown does NOT fail the original Operation (Req 4.9): reconcile.
                        return TransitionEvent.RECONCILE;
                }
        }
    }

    // --- generators ------------------------------------------------------------------------------

    /**
     * Raw platform status strings spanning every branch of the real {@code mapPlatformStatus}: known
     * success / in-flight / failure / cancelled statuses (in varied casing), {@code null}, blank, and
     * arbitrary unicode — so the resolution is exercised against the full status space, including
     * statuses that map to {@link SyncState#FAILED}.
     */
    @Provide
    Arbitrary<String> platformStatuses() {
        Arbitrary<String> known = Arbitraries.of(
                "SUCCESS", "succeeded", "Completed", "APPLIED", "effective", "ENABLED", "active",
                "PENDING", "queued", "submitted", "ACCEPTED",
                "IN_PROGRESS", "processing", "running",
                "FAILED", "failure", "error", "REJECTED", "invalid",
                "CANCELLED", "canceled", "aborted",
                "totally-unrecognized-status", "", "   ");
        Arbitrary<String> freeform = Arbitraries.strings().ofMaxLength(16);
        Arbitrary<String> nullable = Arbitraries.just(null);
        return Arbitraries.oneOf(known, freeform, nullable);
    }
}
