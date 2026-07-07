package com.adpilot.modules.advertising.operation.callback;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.operation.OperationMachineValues;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.advertising.operation.TransitionEvent;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link OperationCallbackService}.
 *
 * <p>The processing pipeline runs strictly in this order so the idempotency (Req 5.7) and
 * write-capability (Req 53.2) guarantees hold for every delivery, including duplicates and
 * re-deliveries:</p>
 *
 * <ol>
 *   <li><b>Correlate</b> — find the Operation by {@code submissionIdempotencyKey} (the key the
 *       Operation was submitted with, Req 5.7), falling back to {@code platformReference}
 *       (Req 55.7). An unknown key mutates nothing.</li>
 *   <li><b>Write-capability gate</b> — if the correlated Operation's Store is NOT write-capable, the
 *       callback is not processed (Req 53.2, Property 12). This also defends the invariant that a
 *       not-write-capable Store never enters platform Sync_States.</li>
 *   <li><b>Map status</b> — resolve the platform's raw status to a single Sync_State via the Store's
 *       platform connector ({@code mapPlatformStatus}, Req 55.4). The connector is resolved from the
 *       Store's active connection, never from the (untrusted) callback body.</li>
 *   <li><b>Idempotent transition</b> — if the Operation is already in the mapped target state, or is
 *       already settled in a terminal state, the callback is a redundant no-op (Req 5.7, Property
 *       18); otherwise the corresponding {@link TransitionEvent} is applied through
 *       {@link OperationService#transition} so the state machine stays the sole authority. An illegal
 *       (out-of-order) transition is treated as a non-mutating no-op rather than an error.</li>
 * </ol>
 *
 * <p>Validates: Requirements 5.7, 53.2, 55.6.</p>
 */
@Slf4j
@Service
public class OperationCallbackServiceImpl implements OperationCallbackService {

    /** The connection status treated as a valid active connection (mirrors WriteCapabilityServiceImpl). */
    private static final String STATUS_CONNECTED = ConnectionStatus.CONNECTED;

    /** platform_mutation scope marker, matched against the Operation's stored scope value. */
    private static final String SCOPE_PLATFORM_MUTATION =
            OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION);

    /**
     * Settled terminal Sync_States. A callback never regresses an Operation out of one of these —
     * re-processing yields the same final state (Req 5.7, Property 18).
     */
    private static final Set<SyncState> TERMINAL_SETTLED = EnumSet.of(
            SyncState.LOCAL_ONLY, SyncState.EFFECTIVE, SyncState.FAILED,
            SyncState.CANCELLED, SyncState.SUPERSEDED);

    private final OperationMapper operationMapper;
    private final WriteCapabilityService writeCapabilityService;
    private final PlatformConnectionMapper platformConnectionMapper;
    private final OperationService operationService;

    /** platform key -> write connector, built once from all injected connector beans. */
    private final Map<String, PlatformWriteConnector> writeConnectors = new ConcurrentHashMap<>();

    public OperationCallbackServiceImpl(OperationMapper operationMapper,
                                        WriteCapabilityService writeCapabilityService,
                                        PlatformConnectionMapper platformConnectionMapper,
                                        OperationService operationService,
                                        List<PlatformWriteConnector> writeConnectorBeans) {
        this.operationMapper = operationMapper;
        this.writeCapabilityService = writeCapabilityService;
        this.platformConnectionMapper = platformConnectionMapper;
        this.operationService = operationService;
        for (PlatformWriteConnector connector : writeConnectorBeans) {
            this.writeConnectors.put(connector.platform(), connector);
        }
    }

    @Override
    public CallbackOutcome process(PlatformCallback callback) {
        if (callback == null) {
            return CallbackOutcome.IGNORED_UNKNOWN_OPERATION;
        }

        // 1. Correlate the callback to its Operation (Req 5.7, 55.7).
        Optional<OperationEntity> match = correlate(callback);
        if (match.isEmpty()) {
            log.debug("Platform callback ignored: no Operation for submissionIdempotencyKey={} / platformReference={}",
                    callback.submissionIdempotencyKey(), callback.platformReference());
            return CallbackOutcome.IGNORED_UNKNOWN_OPERATION;
        }
        OperationEntity operation = match.get();

        // 2. Write-capability gate (Req 53.2, Property 12): never process a platform callback for a
        // Store that is not write-capable, and never process a non-platform_mutation Operation.
        if (!SCOPE_PLATFORM_MUTATION.equals(operation.getOperationScope())) {
            log.debug("Platform callback ignored: Operation {} is not a platform_mutation", operation.getId());
            return CallbackOutcome.IGNORED_NOT_WRITE_CAPABLE;
        }
        if (!writeCapabilityService.isWriteCapable(operation.getStoreId())) {
            log.warn("Platform callback ignored: Store {} is not write-capable (Operation {})",
                    operation.getStoreId(), operation.getId());
            return CallbackOutcome.IGNORED_NOT_WRITE_CAPABLE;
        }

        // 3. Map the raw platform status to a single Sync_State via the Store's connector (Req 55.4).
        // The connector is resolved from the Store's active connection, NOT the untrusted callback body.
        PlatformWriteConnector connector = resolveConnector(operation.getStoreId());
        if (connector == null) {
            log.warn("Platform callback ignored: no connector for Store {} (Operation {})",
                    operation.getStoreId(), operation.getId());
            return CallbackOutcome.IGNORED_NOT_WRITE_CAPABLE;
        }
        SyncState target = connector.mapPlatformStatus(callback.platformStatus());

        // 4. Idempotent transition (Req 5.7, Property 18).
        SyncState current = OperationMachineValues.toSyncState(operation.getSyncState());

        // Already in the mapped target state, or already settled — a redundant delivery is a no-op
        // that yields the same final state and never regresses a settled Operation.
        if (current == target || TERMINAL_SETTLED.contains(current)) {
            return CallbackOutcome.IGNORED_DUPLICATE;
        }

        Optional<TransitionEvent> event = eventForTarget(target);
        if (event.isEmpty()) {
            // No driving event for this target from a callback (e.g. SUBMITTED/PENDING) — no-op.
            return CallbackOutcome.IGNORED_DUPLICATE;
        }

        try {
            operationService.transition(operation.getId(), event.get());
            return CallbackOutcome.PROCESSED;
        } catch (RuntimeException e) {
            // An out-of-order or otherwise illegal transition is treated as a non-mutating no-op
            // rather than surfacing an error to the platform, preserving idempotent re-delivery.
            log.warn("Platform callback for Operation {} produced no transition ({}->{}): {}",
                    operation.getId(), current, target, e.getMessage());
            return CallbackOutcome.IGNORED_DUPLICATE;
        }
    }

    /**
     * Correlate the callback to its Operation by {@code submissionIdempotencyKey} (the primary
     * submission correlation key, Req 5.7), falling back to {@code platformReference} (Req 55.7).
     */
    private Optional<OperationEntity> correlate(PlatformCallback callback) {
        if (hasText(callback.submissionIdempotencyKey())) {
            OperationEntity bySubmission = operationMapper.selectOne(
                    new LambdaQueryWrapper<OperationEntity>()
                            .eq(OperationEntity::getSubmissionIdempotencyKey, callback.submissionIdempotencyKey())
                            .last("LIMIT 1"));
            if (bySubmission != null) {
                return Optional.of(bySubmission);
            }
        }
        if (hasText(callback.platformReference())) {
            OperationEntity byReference = operationMapper.selectOne(
                    new LambdaQueryWrapper<OperationEntity>()
                            .eq(OperationEntity::getPlatformReference, callback.platformReference())
                            .last("LIMIT 1"));
            if (byReference != null) {
                return Optional.of(byReference);
            }
        }
        return Optional.empty();
    }

    /** Resolve the platform connector for the Store's valid active connection, or {@code null}. */
    private PlatformWriteConnector resolveConnector(java.util.UUID storeId) {
        if (storeId == null) {
            return null;
        }
        return platformConnectionMapper.selectList(
                        new LambdaQueryWrapper<PlatformConnectionEntity>()
                                .eq(PlatformConnectionEntity::getStoreId, storeId)
                                .eq(PlatformConnectionEntity::getStatus, STATUS_CONNECTED))
                .stream()
                .map(PlatformConnectionEntity::getPlatform)
                .filter(OperationCallbackServiceImpl::hasText)
                .map(writeConnectors::get)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * The {@link TransitionEvent} that drives an Operation toward the callback's mapped target
     * Sync_State. Only the platform-driven targets a callback can legitimately report are mapped;
     * other targets (e.g. {@code submitted}, {@code pending}) have no callback-driven event and are
     * treated as no-ops by the caller.
     */
    private Optional<TransitionEvent> eventForTarget(SyncState target) {
        return switch (target) {
            case EFFECTIVE -> Optional.of(TransitionEvent.PLATFORM_EFFECTIVE);
            case FAILED -> Optional.of(TransitionEvent.PLATFORM_FAILED);
            case AMAZON_PROCESSING -> Optional.of(TransitionEvent.PLATFORM_PROCESSING);
            case CANCELLED -> Optional.of(TransitionEvent.CANCEL_CONFIRMED);
            case RECONCILIATION_REQUIRED -> Optional.of(TransitionEvent.RECONCILE);
            default -> Optional.empty();
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
