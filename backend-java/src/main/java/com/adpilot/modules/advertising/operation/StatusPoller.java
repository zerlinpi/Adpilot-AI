package com.adpilot.modules.advertising.operation;

import com.adpilot.common.resilience.CircuitBreaker;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformCancelResult;
import com.adpilot.modules.apisync.model.PlatformStatusResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code StatusPoller} / {@code TimeoutSweeper} (task 10.2): the scheduled component that drives
 * the asynchronous, platform-side resolution of in-flight {@code platform_mutation} Operations
 * (Req 4.4, 4.9, 4.10, 55.3, 56.5).
 *
 * <p>Where the {@code OutboxWorker} (task 10.1) submits {@code pending} Operations and the
 * {@code CallbackController} (task 10.3) reacts to inbound platform callbacks, this sweeper closes
 * the loop for Operations whose final state must be discovered by <em>actively querying the
 * platform</em> rather than waiting for a push. It runs three independent phases on each tick, each
 * isolated so a single failing row never aborts the sweep:</p>
 *
 * <ol>
 *   <li><b>In-flight poll + timeout</b> — for {@code submitted} / {@code amazon-processing}
 *       Operations: if the Operation has sat in flight past the configurable timeout (Req 4.4,
 *       default 15 minutes) it is transitioned to {@code expired} via {@link TransitionEvent#TIMEOUT};
 *       otherwise the platform is polled with {@link PlatformWriteConnector#queryStatus queryStatus}
 *       (idempotent, non-mutating, Req 55.2/55.6) and the mapped status drives the legal
 *       acknowledgement transition (processing / effective / failed).</li>
 *   <li><b>cancel_requested resolution</b> — for {@code cancel_requested} Operations: the platform
 *       cancellation outcome ({@link PlatformWriteConnector#requestCancel}) and a status poll resolve
 *       the Operation to exactly one of {@code cancelled}, {@code effective}, or
 *       {@code reconciliation_required}; a failed/unsupported/errored cancellation NEVER marks the
 *       original Operation {@code failed} (Req 4.9, 55.3). This is the production realization of the
 *       resolution proven by Property 7 (task 10.6).</li>
 *   <li><b>expired / reconciliation_required resolution</b> — for {@code expired} and
 *       {@code reconciliation_required} Operations: the platform's actual state is queried BEFORE any
 *       retry and the Operation resolves to {@code effective}, {@code failed}, or {@code cancelled}
 *       per the reported state, holding in {@code reconciliation_required} when the platform state
 *       cannot yet be determined; {@code expired} never maps directly to {@code failed} without a
 *       platform query (Req 4.10, 56.5). This is the production realization of the resolution proven
 *       by Property 8 (task 10.7).</li>
 * </ol>
 *
 * <p>The {@link OperationStateMachine} remains the single transition authority: every state change is
 * applied through {@link OperationService#transition}, never by mutating {@code sync_state} directly.
 * All platform reads ({@code queryStatus} / {@code requestCancel}) happen OUTSIDE any DB transaction;
 * the resulting transition is the only thing persisted (Req 6.3). A Store that is not write-capable
 * is never polled, so a not-write-capable Store can never be advanced into a platform Sync_State by
 * the sweeper (Req 53.2, Property 12).</p>
 *
 * <p>Scheduling is enabled application-wide via {@code SchedulerConfig} ({@code @EnableScheduling});
 * the cadence is configurable through {@code adpilot.operation.poll-ms} (default 60s) and the
 * in-flight timeout through {@code adpilot.operation.timeout-minutes} (default 15, Req 4.4).</p>
 *
 * <p>Validates: Requirements 4.4, 4.9, 4.10, 55.3, 56.5.</p>
 */
@Slf4j
@Component
public class StatusPoller {

    /** The connection status treated as a valid active connection (mirrors WriteCapabilityServiceImpl). */
    private static final String STATUS_CONNECTED = ConnectionStatus.CONNECTED;

    /** platform_mutation scope marker, matched against the Operation's stored scope value. */
    private static final String SCOPE_PLATFORM_MUTATION =
            OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION);

    private final OperationMapper operationMapper;
    private final PlatformConnectionMapper platformConnectionMapper;
    private final OperationService operationService;
    private final WriteCapabilityService writeCapabilityService;
    private final ObjectMapper objectMapper;
    private final CryptoUtil cryptoUtil;
    private final CircuitBreaker circuitBreaker;

    /** platform key -> write connector, built once from all injected connector beans. */
    private final Map<String, PlatformWriteConnector> writeConnectors = new ConcurrentHashMap<>();

    /** In-flight timeout before an Operation is swept to {@code expired} (Req 4.4); configurable. */
    private final long timeoutMinutes;

    public StatusPoller(OperationMapper operationMapper,
                        PlatformConnectionMapper platformConnectionMapper,
                        OperationService operationService,
                        WriteCapabilityService writeCapabilityService,
                        ObjectMapper objectMapper,
                        CryptoUtil cryptoUtil,
                        CircuitBreaker circuitBreaker,
                        List<PlatformWriteConnector> writeConnectorBeans,
                        @Value("${adpilot.operation.timeout-minutes:15}") long timeoutMinutes) {
        this.operationMapper = operationMapper;
        this.platformConnectionMapper = platformConnectionMapper;
        this.operationService = operationService;
        this.writeCapabilityService = writeCapabilityService;
        this.objectMapper = objectMapper;
        this.cryptoUtil = cryptoUtil;
        this.circuitBreaker = circuitBreaker;
        this.timeoutMinutes = timeoutMinutes;
        for (PlatformWriteConnector connector : writeConnectorBeans) {
            this.writeConnectors.put(connector.platform(), connector);
        }
    }

    /**
     * One sweep tick. Each phase is isolated so a failing phase or row never aborts the others (the
     * scheduler keeps ticking, mirroring {@code AiHostingOptimizer} / {@code ApprovalExpirationSweeper}).
     */
    @Scheduled(fixedDelayString = "${adpilot.operation.poll-ms:60000}")
    public void poll() {
        try {
            sweepInFlight();
        } catch (Exception ex) {
            log.warn("StatusPoller in-flight sweep failed", ex);
        }
        try {
            resolveCancelRequested();
        } catch (Exception ex) {
            log.warn("StatusPoller cancel_requested resolution failed", ex);
        }
        try {
            resolveExpiredAndReconciliation();
        } catch (Exception ex) {
            log.warn("StatusPoller expired/reconciliation resolution failed", ex);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 1 — in-flight poll + timeout (submitted / amazon-processing). Req 4.4.
    // ---------------------------------------------------------------------------------------------

    private void sweepInFlight() {
        for (OperationEntity op : findBySyncStates(SyncState.SUBMITTED, SyncState.AMAZON_PROCESSING)) {
            try {
                SyncState current = OperationMachineValues.toSyncState(op.getSyncState());

                // Req 4.4: an Operation that has sat in flight past the timeout is swept to expired.
                // The timeout transition is local — it requires no platform query — so it applies even
                // when the platform is momentarily unreachable. expired is later resolved (phase 3) by
                // an actual platform query, never directly to a terminal failure (Req 4.10/56.5).
                if (hasTimedOut(op)) {
                    applyTransitionSafely(op, TransitionEvent.TIMEOUT,
                            "操作在平台处理超时，已转入 expired，待查询平台真实状态后再行解析");
                    continue;
                }

                // Poll the platform's current status (idempotent / non-mutating, Req 55.2/55.6).
                ResolvedConnector resolved = resolveConnector(op);
                if (resolved == null || !hasText(op.getPlatformReference())) {
                    continue;
                }
                // H3: skip polling a known-down dependency this tick; the Operation is left
                // untouched (still in flight) to be retried on a later tick once the breaker
                // half-opens. Per-item isolation is preserved.
                String breakerKey = breakerKey(resolved);
                if (!circuitBreaker.allow(breakerKey)) {
                    log.debug("StatusPoller: circuit OPEN for {}, skipping in-flight Operation {} this tick",
                            breakerKey, op.getId());
                    continue;
                }
                PlatformStatusResult status = queryStatusGuarded(resolved, op.getPlatformReference(), breakerKey);
                if (status == null || !status.found()) {
                    // The platform does not yet recognize the reference: leave the Operation in flight;
                    // it will be polled again next tick or swept to expired once the timeout elapses.
                    continue;
                }

                SyncState reported = resolved.connector().mapPlatformStatus(status.platformStatus());
                TransitionEvent event = inFlightEvent(current, reported);
                if (event != null) {
                    applyTransitionSafely(op, event, status.message());
                }
            } catch (RuntimeException ex) {
                log.warn("StatusPoller failed to poll in-flight Operation {}: {}", op.getId(), ex.getMessage());
            }
        }
    }

    /**
     * The legal acknowledgement event for a polled in-flight Operation. Only the platform-driven
     * acknowledgements an in-flight Operation can legally take are mapped; a still-in-flight or
     * indeterminate status yields {@code null} (no-op, poll again next tick). {@code amazon-processing}
     * is only legal from {@code submitted}.
     */
    private TransitionEvent inFlightEvent(SyncState current, SyncState reported) {
        return switch (reported) {
            case EFFECTIVE -> TransitionEvent.PLATFORM_EFFECTIVE;
            case FAILED -> TransitionEvent.PLATFORM_FAILED;
            case AMAZON_PROCESSING -> current == SyncState.SUBMITTED ? TransitionEvent.PLATFORM_PROCESSING : null;
            // submitted/pending (still in flight) and reconciliation/cancelled (not a legal direct
            // acknowledgement from an in-flight state) wait for a later, determinate poll.
            default -> null;
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 2 — cancel_requested resolution. Req 4.9, 55.3 (the production form of Property 7).
    // ---------------------------------------------------------------------------------------------

    private void resolveCancelRequested() {
        for (OperationEntity op : findBySyncStates(SyncState.CANCEL_REQUESTED)) {
            try {
                ResolvedConnector resolved = resolveConnector(op);
                if (resolved == null) {
                    // Cannot reach the platform to confirm the cancellation: hold (do NOT mark failed,
                    // Req 4.9). It is retried next tick.
                    continue;
                }
                // H3: a known-down dependency is skipped this tick; the cancel_requested Operation is
                // left as-is (NOT failed, Req 4.9) to be resolved on a later tick.
                String breakerKey = breakerKey(resolved);
                if (!circuitBreaker.allow(breakerKey)) {
                    log.debug("StatusPoller: circuit OPEN for {}, skipping cancel_requested Operation {} this tick",
                            breakerKey, op.getId());
                    continue;
                }
                PlatformWriteConnector connector = resolved.connector();
                String reference = op.getPlatformReference();

                // Ask the platform to cancel (if it supports cancellation), then poll its actual state.
                // Both platform reads run under the breaker: a thrown failure records against the key
                // and re-propagates to the per-item catch below (the Operation is left for next tick).
                PlatformCancelResult.Outcome cancelOutcome;
                SyncState polledMapped = null;
                try {
                    if (connector.supportsCancel()) {
                        PlatformCancelResult cancel = connector.requestCancel(resolved.ctx(), reference);
                        cancelOutcome = (cancel != null) ? cancel.outcome() : PlatformCancelResult.Outcome.ERROR;
                    } else {
                        cancelOutcome = PlatformCancelResult.Outcome.UNSUPPORTED;
                    }
                    if (hasText(reference)) {
                        PlatformStatusResult status = connector.queryStatus(resolved.ctx(), reference);
                        if (status != null && status.found()) {
                            polledMapped = connector.mapPlatformStatus(status.platformStatus());
                        }
                    }
                    circuitBreaker.recordSuccess(breakerKey);
                } catch (RuntimeException ex) {
                    circuitBreaker.recordFailure(breakerKey);
                    throw ex;
                }

                // Resolve via the documented PlatformCancelResult.Outcome contract (Req 4.9): a failed
                // cancellation reconciles, it never fails the original Operation.
                TransitionEvent event = cancelResolutionEvent(cancelOutcome, polledMapped);
                applyTransitionSafely(op, event,
                        "cancel_requested 解析：取消结果=" + cancelOutcome + "，平台状态=" + polledMapped);
            } catch (RuntimeException ex) {
                log.warn("StatusPoller failed to resolve cancel_requested Operation {}: {}",
                        op.getId(), ex.getMessage());
            }
        }
    }

    /**
     * The event applied to a {@code cancel_requested} Operation, following the documented
     * {@link PlatformCancelResult.Outcome} contract and Requirement 4.9. By construction this NEVER
     * returns {@link TransitionEvent#PLATFORM_FAILED}: a failed, unsupported, or errored cancellation —
     * or a poll that maps to {@code failed} or an as-yet-unresolved state — routes to
     * {@link TransitionEvent#RECONCILE}, never to a failure of the original Operation (Req 4.9, 55.3).
     *
     * <p>This mirrors the resolution oracle of {@code CancelRequestedResolutionPropertyTest}
     * (Property 7, task 10.6).</p>
     */
    private TransitionEvent cancelResolutionEvent(PlatformCancelResult.Outcome outcome, SyncState polledMapped) {
        switch (outcome) {
            case CANCELLED:
                // Platform supports cancel and confirmed the change was NOT applied -> cancelled.
                return TransitionEvent.CANCEL_CONFIRMED;
            case ALREADY_APPLIED:
                // Platform reports the change already became effective -> effective (a compensating
                // rollback Operation is created separately per Req 8, not by a local state flip).
                return TransitionEvent.PLATFORM_EFFECTIVE;
            case ERROR:
                // The cancellation request itself errored or could not be confirmed -> reconcile;
                // NEVER mark the original Operation failed (Req 4.9).
                return TransitionEvent.RECONCILE;
            case REQUESTED:
            case UNSUPPORTED:
            default:
                // The platform accepted the request (final state pending) or does not support cancel:
                // resolve by the platform's actual polled state.
                if (polledMapped == SyncState.EFFECTIVE) {
                    return TransitionEvent.PLATFORM_EFFECTIVE;
                }
                if (polledMapped == SyncState.CANCELLED) {
                    return TransitionEvent.CANCEL_CONFIRMED;
                }
                // A poll mapping to failed, still-in-flight, or unknown does NOT fail the original
                // Operation (Req 4.9): reconcile.
                return TransitionEvent.RECONCILE;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 3 — expired / reconciliation_required resolution. Req 4.10, 56.5 (Property 8).
    // ---------------------------------------------------------------------------------------------

    private void resolveExpiredAndReconciliation() {
        for (OperationEntity op : findBySyncStates(SyncState.EXPIRED, SyncState.RECONCILIATION_REQUIRED)) {
            try {
                SyncState current = OperationMachineValues.toSyncState(op.getSyncState());

                ResolvedConnector resolved = resolveConnector(op);
                if (resolved == null || !hasText(op.getPlatformReference())) {
                    // Cannot query the platform yet: hold in reconciliation rather than retry or fail
                    // (Req 4.10). expired holds by being moved to reconciliation_required; an already
                    // reconciliation_required Operation simply waits for a later determinate query.
                    if (current == SyncState.EXPIRED) {
                        applyTransitionSafely(op, TransitionEvent.RECONCILE,
                                "expired 暂无法查询平台状态，转入 reconciliation_required 等待人工/后续查询");
                    }
                    continue;
                }

                // H3: when the dependency is known-down, skip this tick and leave the Operation
                // as-is (expired stays expired, reconciliation_required stays put) rather than
                // querying; it is retried once the breaker half-opens.
                String breakerKey = breakerKey(resolved);
                if (!circuitBreaker.allow(breakerKey)) {
                    log.debug("StatusPoller: circuit OPEN for {}, skipping {} Operation {} this tick",
                            breakerKey, op.getSyncState(), op.getId());
                    continue;
                }

                // Query the platform's actual state BEFORE any retry (Req 4.10). The mapping is total
                // and never throws, so a query always yields a single decision input.
                PlatformStatusResult status = queryStatusGuarded(
                        resolved, op.getPlatformReference(), breakerKey);
                SyncState reported = (status != null && status.found())
                        ? resolved.connector().mapPlatformStatus(status.platformStatus())
                        : SyncState.RECONCILIATION_REQUIRED; // not found -> indeterminate, hold.

                resolveQueryResolvedState(op, current, reported,
                        status != null ? status.message() : null);
            } catch (RuntimeException ex) {
                log.warn("StatusPoller failed to resolve {} Operation {}: {}",
                        op.getSyncState(), op.getId(), ex.getMessage());
            }
        }
    }

    /**
     * Drive an {@code expired} / {@code reconciliation_required} Operation to its query-determined
     * resolution, applying each step through the state machine. Mirrors the resolution oracle of
     * {@code ExpiredReconciliationResolutionPropertyTest} (Property 8, task 10.7): a determinate
     * platform state resolves to exactly {@code effective} / {@code failed} / {@code cancelled}; an
     * indeterminate state holds in {@code reconciliation_required}; and {@code expired} never reaches
     * {@code failed} except via an actual platform-query failure.
     */
    private void resolveQueryResolvedState(OperationEntity op, SyncState current,
                                           SyncState reported, String message) {
        switch (reported) {
            case EFFECTIVE -> applyTransitionSafely(op, TransitionEvent.PLATFORM_EFFECTIVE, message);
            case FAILED -> applyTransitionSafely(op, TransitionEvent.PLATFORM_FAILED, message);
            case CANCELLED -> {
                // The platform confirms the change was not applied. expired cannot go directly to
                // cancelled; it first reconciles, then confirms. reconciliation_required confirms
                // directly. Re-read the (now updated) state before the second step so the state
                // machine sees the correct source state.
                if (current == SyncState.EXPIRED) {
                    applyTransitionSafely(op, TransitionEvent.RECONCILE,
                            "expired 平台查询显示未生效，转入 reconciliation_required 以确认取消");
                    OperationEntity reloaded = operationMapper.selectById(op.getId());
                    if (reloaded == null
                            || OperationMachineValues.toSyncState(reloaded.getSyncState())
                            != SyncState.RECONCILIATION_REQUIRED) {
                        return;
                    }
                    op = reloaded;
                }
                applyTransitionSafely(op, TransitionEvent.CANCEL_CONFIRMED, message);
            }
            default -> {
                // INDETERMINATE (submitted / amazon-processing / reconciliation_required / unknown):
                // the platform's final state is not yet determinable. Hold for reconciliation rather
                // than retry or fail (Req 4.10). expired moves into reconciliation_required; an
                // already-reconciling Operation stays put until a later determinate query.
                if (current == SyncState.EXPIRED) {
                    applyTransitionSafely(op, TransitionEvent.RECONCILE,
                            "expired 平台状态暂不可判定，转入 reconciliation_required 等待后续查询");
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Apply a transition through {@link OperationService#transition} — the single transition authority
     * — isolating any illegal/out-of-order transition (for example a callback already advanced the
     * Operation between the query and this write) as a non-mutating no-op rather than aborting the
     * sweep. The optional reason is recorded by the service for failed/expired/cancel-requested
     * /reconciliation states.
     */
    private void applyTransitionSafely(OperationEntity op, TransitionEvent event, String reason) {
        try {
            log.debug("StatusPoller applying {} to Operation {} ({})", event, op.getId(), reason);
            operationService.transition(op.getId(), event);
        } catch (RuntimeException ex) {
            log.debug("StatusPoller transition {} on Operation {} was a no-op: {}",
                    event, op.getId(), ex.getMessage());
        }
    }

    /**
     * @return {@code true} when the Operation has remained in its current in-flight state longer than
     *         the configured timeout (Req 4.4). {@code updated_at} is the time the Operation entered
     *         its current Sync_State (refreshed by {@code @UpdateTimestamp} on each transition), so it
     *         is the basis for the in-flight age.
     */
    private boolean hasTimedOut(OperationEntity op) {
        LocalDateTime since = op.getUpdatedAt() != null ? op.getUpdatedAt() : op.getCreatedAt();
        if (since == null) {
            return false;
        }
        return Duration.between(since, LocalDateTime.now()).toMinutes() >= timeoutMinutes;
    }

    /**
     * Load the {@code platform_mutation} Operations currently in any of the supplied Sync_States.
     */
    private List<OperationEntity> findBySyncStates(SyncState... states) {
        String[] values = new String[states.length];
        for (int i = 0; i < states.length; i++) {
            values[i] = OperationMachineValues.toValue(states[i]);
        }
        return operationMapper.selectList(new LambdaQueryWrapper<OperationEntity>()
                .eq(OperationEntity::getOperationScope, SCOPE_PLATFORM_MUTATION)
                .in(OperationEntity::getSyncState, (Object[]) values));
    }

    /**
     * Query the platform status under the circuit breaker: a thrown failure is recorded against
     * {@code breakerKey} (so a persistently-down dependency eventually opens the breaker) and then
     * re-propagated to the per-item catch, which leaves the Operation as-is for the next tick. A
     * successful read records success, closing/keeping the breaker closed.
     */
    private PlatformStatusResult queryStatusGuarded(ResolvedConnector resolved, String reference,
                                                    String breakerKey) {
        try {
            PlatformStatusResult status = resolved.connector().queryStatus(resolved.ctx(), reference);
            circuitBreaker.recordSuccess(breakerKey);
            return status;
        } catch (RuntimeException ex) {
            circuitBreaker.recordFailure(breakerKey);
            throw ex;
        }
    }

    /**
     * Circuit-breaker key for a resolved connection. Keyed by dependency + connection so a single
     * down store connection opens the breaker only for itself, never for a healthy store's calls
     * (H3). The {@code status} suffix covers the poller's queryStatus / requestCancel reads.
     */
    private static String breakerKey(ResolvedConnector resolved) {
        return resolved.ctx().platform() + ":status:" + resolved.ctx().connectionId();
    }

    /**
     * Resolve the Store's connector and a {@link ConnectionContext} for a platform read. A Store that
     * is NOT write-capable, or has no registered connector, is never polled — so a not-write-capable
     * Store can never be advanced into a platform Sync_State by the sweeper (Req 53.2, Property 12).
     *
     * @return the resolved connector + context, or {@code null} when the Store cannot be polled
     */
    private ResolvedConnector resolveConnector(OperationEntity op) {
        UUID storeId = op.getStoreId();
        if (storeId == null || !writeCapabilityService.isWriteCapable(storeId)) {
            return null;
        }
        PlatformConnectionEntity connection = platformConnectionMapper.selectList(
                        new LambdaQueryWrapper<PlatformConnectionEntity>()
                                .eq(PlatformConnectionEntity::getStoreId, storeId)
                                .eq(PlatformConnectionEntity::getStatus, STATUS_CONNECTED)
                                .orderByDesc(PlatformConnectionEntity::getUpdatedAt))
                .stream()
                .findFirst()
                .orElse(null);
        if (connection == null || !hasText(connection.getPlatform())) {
            return null;
        }
        PlatformWriteConnector connector = writeConnectors.get(connection.getPlatform());
        if (connector == null) {
            return null;
        }
        ConnectionContext ctx = new ConnectionContext(
                connection.getId(), storeId, connection.getPlatform(),
                decryptConfig(connection.getConfigEncrypted()));
        return new ResolvedConnector(connector, ctx);
    }

    private Map<String, String> decryptConfig(String stored) {
        if (stored == null || stored.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> encrypted = objectMapper.readValue(
                    stored, new TypeReference<LinkedHashMap<String, String>>() {});
            Map<String, String> plain = new LinkedHashMap<>();
            encrypted.forEach((k, v) -> plain.put(k, v == null ? null : cryptoUtil.decrypt(v)));
            return plain;
        } catch (Exception e) {
            log.warn("StatusPoller failed to read platform config: {}", e.getMessage());
            return Map.of();
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** A resolved platform connector together with the connection context to call it with. */
    private record ResolvedConnector(PlatformWriteConnector connector, ConnectionContext ctx) {
    }
}
