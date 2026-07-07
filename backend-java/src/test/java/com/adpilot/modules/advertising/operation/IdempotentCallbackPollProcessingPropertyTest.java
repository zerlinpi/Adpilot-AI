package com.adpilot.modules.advertising.operation;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.operation.callback.CallbackOutcome;
import com.adpilot.modules.advertising.operation.callback.OperationCallbackServiceImpl;
import com.adpilot.modules.advertising.operation.callback.PlatformCallback;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
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
import net.jqwik.api.constraints.IntRange;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for idempotent inbound platform callback / poll-result processing
 * (task 10.10).
 *
 * <p>Feature: advertising-workspace-rework, Property 18: Callback/poll processing is idempotent.
 *
 * <p>Validates: Requirements 5.7, 55.6.
 *
 * <p>Property 18 (transcribed from the design's Correctness Properties section): <em>For any
 * platform result, processing it more than once produces the same final Sync_State as processing it
 * once.</em>
 *
 * <p>A platform result reaches the system either as an inbound platform callback or as a
 * {@code queryStatus} poll result; both are normalized to the same {@link PlatformCallback} and
 * driven through {@link OperationCallbackServiceImpl}. The {@code StatusPoller} /
 * {@code CallbackController} funnel every delivery — including duplicates and re-deliveries that
 * carry an already-processed {@code submissionIdempotencyKey} (Req 5.7) — through that one
 * processing path, and {@code queryStatus} is itself idempotent and non-mutating (Req 55.6). This
 * property pins the resulting invariant.</p>
 *
 * <p>The test exercises the <strong>real</strong> {@link OperationCallbackServiceImpl} with the
 * <strong>real</strong> {@link OperationStateMachine} as the sole transition authority (a stateful
 * fake {@link OperationService} routes every transition through it and mutates the in-memory
 * Operation exactly as production persistence would), and the <strong>real</strong> total
 * {@link PlatformWriteConnector#mapPlatformStatus(String)} status mapping. The Store is
 * write-capable so the platform-driven path is fully reachable.</p>
 *
 * <p>For each generated (starting Sync_State, raw platform status, repeat count) it asserts that
 * processing the same platform result {@code K >= 2} times lands the Operation in exactly the same
 * final Sync_State as processing it once, and that at most one actual Sync_State transition is ever
 * driven regardless of how many times the result is re-delivered — every delivery after the first is
 * a non-mutating no-op (Req 5.7 / Property 18).</p>
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 18: Callback/poll processing is idempotent")
class IdempotentCallbackPollProcessingPropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    /** The platform key shared by the test connector and the Store's active connection. */
    private static final String PLATFORM = "amazon_ads";

    /** The real, authoritative transition table — shared, stateless. */
    private static final OperationStateMachine STATE_MACHINE = new OperationStateMachine();

    /**
     * The test platform connector: real default {@link PlatformWriteConnector#mapPlatformStatus} and
     * default cancel behaviour; {@code submit} is never invoked by callback processing.
     */
    private static final PlatformWriteConnector CONNECTOR = new PlatformWriteConnector() {
        @Override
        public String platform() {
            return PLATFORM;
        }

        @Override
        public PlatformWriteResult submit(ConnectionContext ctx, PlatformChange change) {
            throw new UnsupportedOperationException("submit is not exercised by Property 18");
        }
    };

    /**
     * Feature: advertising-workspace-rework, Property 18: Callback/poll processing is idempotent.
     *
     * <p>Validates: Requirements 5.7, 55.6.
     *
     * <p>For any platform result (a raw platform status arriving against an Operation in any starting
     * Sync_State), processing it {@code K >= 2} times produces the same final Sync_State as processing
     * it once, and drives the same number of actual transitions (at most one).
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 18: re-processing the same platform result yields the same final Sync_State")
    void reprocessingSameResultYieldsSameFinalState(
            @ForAll("startingStates") SyncState startingState,
            @ForAll("platformStatuses") String platformStatus,
            @ForAll @IntRange(min = 2, max = 6) int repeats) {

        PlatformCallback result = new PlatformCallback(
                PLATFORM,
                "subkey-" + UUID.randomUUID(),
                "ref-" + UUID.randomUUID(),
                platformStatus,
                "platform result");

        // Scenario A: process the platform result exactly once.
        Harness once = new Harness(startingState);
        CallbackOutcome outcomeOnce = once.process(result);
        SyncState finalOnce = once.currentState();
        int transitionsOnce = once.transitionCount();

        // Scenario B: process the SAME platform result repeats (>= 2) times against an Operation that
        // started in the SAME Sync_State.
        Harness many = new Harness(startingState);
        CallbackOutcome firstMany = null;
        for (int i = 0; i < repeats; i++) {
            CallbackOutcome outcome = many.process(result);
            if (i == 0) {
                firstMany = outcome;
            } else {
                // Every re-delivery after the first is a deliberate non-mutating no-op: it never
                // produces a duplicate platform request and never re-drives a transition (Req 5.7).
                assertThat(outcome)
                        .as("re-delivery #%d of an already-processed platform result must be a no-op", i)
                        .isEqualTo(CallbackOutcome.IGNORED_DUPLICATE);
            }
        }
        SyncState finalMany = many.currentState();
        int transitionsMany = many.transitionCount();

        // The core invariant: processing more than once produces the same final Sync_State as once.
        assertThat(finalMany)
                .as("processing the same platform result %d times must land in the same final "
                        + "Sync_State as processing it once (start=%s, status=%s)",
                        repeats, startingState, platformStatus)
                .isEqualTo(finalOnce);

        // Idempotency is realized by re-deliveries being no-ops: the number of actual Sync_State
        // transitions is identical whether processed once or many times, and is at most one.
        assertThat(transitionsMany)
                .as("re-processing must drive no additional transitions beyond the single-processing case")
                .isEqualTo(transitionsOnce);
        assertThat(transitionsOnce)
                .as("a single platform result drives at most one Sync_State transition")
                .isLessThanOrEqualTo(1);

        // The first processing of each scenario reaches the same outcome (same correlate/gate/map path).
        assertThat(firstMany)
                .as("the first processing must behave identically in both scenarios")
                .isEqualTo(outcomeOnce);
    }

    // ---------------------------------------------------------------------------------------------
    // Harness — a stateful fake Operation backed by the REAL state machine and the REAL callback
    // service, so processing a callback mutates Sync_State exactly as production persistence would.
    // ---------------------------------------------------------------------------------------------

    private static final class Harness {

        private final OperationEntity operation;
        private final OperationCallbackServiceImpl service;
        private final AtomicInteger transitions = new AtomicInteger(0);

        Harness(SyncState startingState) {
            this.operation = OperationEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(UUID.randomUUID())
                    .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                    .syncState(OperationMachineValues.toValue(startingState))
                    .submissionIdempotencyKey("subkey-" + UUID.randomUUID())
                    .platformReference("ref-" + UUID.randomUUID())
                    .build();

            OperationMapper operationMapper = Mockito.mock(OperationMapper.class);
            WriteCapabilityService writeCapabilityService = Mockito.mock(WriteCapabilityService.class);
            PlatformConnectionMapper platformConnectionMapper = Mockito.mock(PlatformConnectionMapper.class);
            OperationService operationService = Mockito.mock(OperationService.class);

            // The callback always correlates to our single in-memory Operation, which reflects the
            // CURRENT (possibly already-transitioned) Sync_State on every read.
            when(operationMapper.selectOne(any())).thenReturn(operation);
            // The Store is write-capable so the platform-driven path is fully reachable (Req 53.2).
            when(writeCapabilityService.isWriteCapable(any())).thenReturn(true);
            // The Store has a valid active connection on the test platform, resolving the connector.
            when(platformConnectionMapper.selectList(any())).thenReturn(List.of(
                    PlatformConnectionEntity.builder()
                            .id(UUID.randomUUID())
                            .storeId(operation.getStoreId())
                            .platform(PLATFORM)
                            .status("connected")
                            .build()));

            // A stateful fake: route each transition through the REAL state machine and mutate the
            // in-memory Operation, exactly as the production OperationService persistence would. An
            // illegal transition throws (as production does), which the callback service treats as a
            // non-mutating no-op.
            Mockito.doAnswer(invocation -> {
                TransitionEvent event = invocation.getArgument(1);
                SyncState from = OperationMachineValues.toSyncState(operation.getSyncState());
                SyncState to = STATE_MACHINE.transition(from, event);
                operation.setSyncState(OperationMachineValues.toValue(to));
                transitions.incrementAndGet();
                return null;
            }).when(operationService).transition(any(), any());

            this.service = new OperationCallbackServiceImpl(
                    operationMapper, writeCapabilityService, platformConnectionMapper, operationService,
                    List.<PlatformWriteConnector>of(CONNECTOR));
        }

        CallbackOutcome process(PlatformCallback callback) {
            return service.process(callback);
        }

        SyncState currentState() {
            return OperationMachineValues.toSyncState(operation.getSyncState());
        }

        int transitionCount() {
            return transitions.get();
        }
    }

    // --- generators ------------------------------------------------------------------------------

    /**
     * Starting Sync_States spanning the platform-reachable in-flight states a callback can advance
     * ({@code submitted}, {@code amazon-processing}, {@code cancel_requested}, {@code expired},
     * {@code reconciliation_required}) as well as the settled terminal states ({@code effective},
     * {@code failed}, {@code cancelled}, {@code superseded}) which a re-delivered result must never
     * regress.
     */
    @Provide
    Arbitrary<SyncState> startingStates() {
        return Arbitraries.of(
                SyncState.SUBMITTED,
                SyncState.AMAZON_PROCESSING,
                SyncState.CANCEL_REQUESTED,
                SyncState.EXPIRED,
                SyncState.RECONCILIATION_REQUIRED,
                SyncState.EFFECTIVE,
                SyncState.FAILED,
                SyncState.CANCELLED,
                SyncState.SUPERSEDED);
    }

    /**
     * Raw platform status strings spanning every branch of the real {@code mapPlatformStatus}: known
     * success / in-flight / failure / cancelled statuses (in varied casing), {@code null}, blank, and
     * arbitrary freeform text — so re-processing is exercised across the full status space, including
     * statuses that map to {@code effective}, {@code failed}, {@code cancelled},
     * {@code amazon-processing}, and {@code reconciliation_required}.
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
