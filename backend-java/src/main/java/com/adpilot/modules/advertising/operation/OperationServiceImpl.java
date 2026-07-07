package com.adpilot.modules.advertising.operation;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.PermissionChecker;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.OperationOutboxEntity;
import com.adpilot.modules.advertising.entity.OperationPendingChangeEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.mapper.OperationPendingChangeMapper;
import com.adpilot.modules.advertising.operation.alert.WriteBackAlerting;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Default {@link OperationService}.
 *
 * <p>{@link #createOperation(CreateOperationCommand)} runs the creation pipeline in the exact order
 * mandated by the design and Requirement set, then performs the single-transaction persistence of
 * Requirement 6.1. The pipeline is:</p>
 *
 * <ol>
 *   <li><b>Permission check</b> (Req 27) — verify the {@code requiredPermission} through the
 *       Permission_Service; reject with 403 when absent.</li>
 *   <li><b>Data-scope + ownership validation</b> (Req 24, 25) — assert the acting user may write the
 *       owning Store; reject with 403 when out of scope. The acting user is resolved from the
 *       authenticated security context (Req 24.4).</li>
 *   <li><b>In-flight conflict lock</b> (Req 5.6) — for a {@code platform_mutation}, reject a new
 *       conflicting Operation against an object that already has an Unsettled_State Operation. A
 *       repeated activation that carries the SAME {@code logicalIdempotencyKey} is NOT a conflict:
 *       it is coalesced (Req 5.3) rather than rejected.</li>
 *   <li><b>Idempotency coalescing</b> (Req 5.3) — when the caller supplied a
 *       {@code logicalIdempotencyKey} that already names a logical Operation in the Store, reuse it
 *       instead of creating a second one.</li>
 *   <li><b>Optimistic-version check</b> (Req 5.4/5.5) — claim the versioned entity at the version the
 *       operator's view loaded; reject a stale view. This bumps only the {@code version} column and
 *       never the entity's confirmed value (Req 6.1).</li>
 *   <li><b>Approval-threshold evaluation</b> (Req 4.1, 22.8) — when the change requires approval and
 *       the Store is write-capable, the Operation is created {@code awaiting_approval}.</li>
 *   <li><b>Write-capability resolution</b> (Req 53) — when the Store is NOT write-capable, the
 *       {@code platform_mutation} resolves to the terminal {@code local-only} Sync_State and NO
 *       Outbox entry is written (Req 2.4, 3.2, 9.4, 53.3).</li>
 * </ol>
 *
 * <p>The persistence step writes, in ONE transaction and with NO platform call (Req 6.1, 6.3): the
 * Operation_Record, the pending-change record (for a {@code platform_mutation} with a target field),
 * the audit log entry, and — only for a write-capable {@code platform_mutation} resolved to
 * {@code pending} — the Outbox entry. A {@code local_configuration} Operation carries an
 * {@code executionStatus} of {@code applied}, never a Sync_State, and never writes an Outbox entry
 * (Req 3.7, 3.8, 6.2).</p>
 *
 * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.6, 3.2, 3.7, 6.1, 6.2, 6.3, 9.4, 53.3.</p>
 */
@Slf4j
@Service
public class OperationServiceImpl implements OperationService {

    /** The connection status treated as a valid active connection (mirrors WriteCapabilityServiceImpl). */
    private static final String STATUS_CONNECTED = ConnectionStatus.CONNECTED;

    /** Audit action recorded for an Operation creation. */
    private static final String AUDIT_ACTION_CREATE = "CREATE_OPERATION";

    private final PermissionChecker permissionChecker;
    private final DataScopeService dataScopeService;
    private final InFlightConflictLock inFlightConflictLock;
    private final IdempotencyService idempotencyService;
    private final EntityVersionGuard entityVersionGuard;
    private final WriteCapabilityService writeCapabilityService;
    private final OperationRecordService operationRecordService;
    private final OperationMapper operationMapper;
    private final OperationStateMachine stateMachine;
    private final ConfirmedValueWriter confirmedValueWriter;
    private final OperationPendingChangeMapper pendingChangeMapper;
    private final OperationOutboxMapper outboxMapper;
    private final PlatformConnectionMapper platformConnectionMapper;
    private final AuditLogService auditLogService;
    private final OperationJsonCodec jsonCodec;
    private final WriteBackAlerting writeBackAlerting;

    public OperationServiceImpl(PermissionChecker permissionChecker,
                                DataScopeService dataScopeService,
                                InFlightConflictLock inFlightConflictLock,
                                IdempotencyService idempotencyService,
                                EntityVersionGuard entityVersionGuard,
                                WriteCapabilityService writeCapabilityService,
                                OperationRecordService operationRecordService,
                                OperationMapper operationMapper,
                                OperationStateMachine stateMachine,
                                ConfirmedValueWriter confirmedValueWriter,
                                OperationPendingChangeMapper pendingChangeMapper,
                                OperationOutboxMapper outboxMapper,
                                PlatformConnectionMapper platformConnectionMapper,
                                AuditLogService auditLogService,
                                OperationJsonCodec jsonCodec,
                                WriteBackAlerting writeBackAlerting) {
        this.permissionChecker = permissionChecker;
        this.dataScopeService = dataScopeService;
        this.inFlightConflictLock = inFlightConflictLock;
        this.idempotencyService = idempotencyService;
        this.entityVersionGuard = entityVersionGuard;
        this.writeCapabilityService = writeCapabilityService;
        this.operationRecordService = operationRecordService;
        this.operationMapper = operationMapper;
        this.stateMachine = stateMachine;
        this.confirmedValueWriter = confirmedValueWriter;
        this.pendingChangeMapper = pendingChangeMapper;
        this.outboxMapper = outboxMapper;
        this.platformConnectionMapper = platformConnectionMapper;
        this.auditLogService = auditLogService;
        this.jsonCodec = jsonCodec;
        this.writeBackAlerting = writeBackAlerting;
    }

    @Override
    @Transactional
    public OperationResult createOperation(CreateOperationCommand cmd) {
        validateCommand(cmd);

        boolean platformMutation = cmd.getOperationScope() == OperationScope.PLATFORM_MUTATION;
        UUID actingUserId = resolveActingUserId();

        // 1. Permission check (Req 27).
        checkPermission(cmd);

        // 2. Data-scope + per-record ownership validation (Req 24, 25). The acting user is resolved
        // from the authenticated security context (Req 24.4); the owning Store must be within the
        // caller's effective data scope.
        validateOwnership(cmd);

        // 3. In-flight conflict lock (Req 5.6) — platform_mutation only. A repeated activation that
        // shares the logicalIdempotencyKey is coalesced (Req 5.3), not rejected as a conflict.
        if (platformMutation) {
            Optional<OperationResult> coalescedByConflict = checkInFlightConflict(cmd);
            if (coalescedByConflict.isPresent()) {
                return coalescedByConflict.get();
            }
        }

        // 4. Idempotency coalescing by logicalIdempotencyKey (Req 5.3).
        Optional<OperationResult> coalesced = coalesce(cmd);
        if (coalesced.isPresent()) {
            return coalesced.get();
        }

        // 5. Optimistic-version check (Req 5.4/5.5) — claim the entity at the loaded version.
        entityVersionGuard.guardIfVersioned(cmd.getEntityType(), cmd.getEntityId(), cmd.getExpectedVersion());

        // 6 + 7. Approval-threshold evaluation (Req 4.1, 22.8) and write-capability resolution (Req 53).
        SyncState syncState = null;
        ExecutionStatus executionStatus = null;
        boolean writeCapable = false;
        if (platformMutation) {
            writeCapable = writeCapabilityService.isWriteCapable(cmd.getStoreId());
            syncState = resolveSyncState(writeCapable, Boolean.TRUE.equals(cmd.getApprovalRequired()));
        } else {
            // local_configuration applies immediately and carries an executionStatus, never a
            // Sync_State, and never an Outbox entry (Req 3.7, 3.8, 6.2).
            executionStatus = ExecutionStatus.APPLIED;
        }

        // ---- Single-transaction persistence (Req 6.1, 6.3): Operation + pending-change + audit
        // (+ Outbox for a write-capable, submission-ready platform_mutation). NO platform call. ----

        UUID logicalOperationId = UUID.randomUUID();
        String effectiveLogicalKey = hasText(cmd.getLogicalIdempotencyKey())
                ? cmd.getLogicalIdempotencyKey()
                : UUID.randomUUID().toString();

        OperationRecordCommand recordCommand = OperationRecordCommand.builder()
                .storeId(cmd.getStoreId())
                .operationSource(cmd.getOperationSource())
                .operationScope(cmd.getOperationScope())
                .entityType(cmd.getEntityType())
                .entityId(cmd.getEntityId())
                .field(cmd.getField())
                .logicalOperationId(logicalOperationId)
                .logicalIdempotencyKey(effectiveLogicalKey)
                .attemptId(UUID.randomUUID())
                // submissionIdempotencyKey is null until the Operation is actually submitted (Req 5.2).
                .submissionIdempotencyKey(null)
                .attemptNumber(1)
                .parentOperationId(cmd.getParentOperationId())
                .beforeValue(cmd.getBeforeValue())
                .afterValue(cmd.getAfterValue())
                .reversible(cmd.getReversible())
                .affectedCount(cmd.getAffectedCount())
                .actingUserId(actingUserId)
                .syncState(syncState)
                .executionStatus(executionStatus)
                .personalityRuleVersion(cmd.getPersonalityRuleVersion())
                .aiDecision(cmd.getAiDecision())
                .build();

        OperationEntity operation = operationRecordService.record(recordCommand);

        // Pending-change record: the Pending_Overlay basis for a platform_mutation against a field
        // (Req 6.1, 7). local_configuration carries no overlay.
        if (platformMutation && hasText(cmd.getField())) {
            writePendingChange(operation, cmd);
        }

        // Audit log entry (Req 6.1 / 6.2 — written for both scopes).
        writeAuditLog(operation, actingUserId, syncState, executionStatus);

        // Outbox entry ONLY for a write-capable platform_mutation that is ready to submit (pending).
        // A local-only or awaiting_approval Operation writes no Outbox; a local_configuration never
        // writes one (Req 2.4, 2.6, 3.2, 3.7, 6.2, 9.4, 53.3).
        if (platformMutation && writeCapable && syncState == SyncState.PENDING) {
            writeOutbox(operation, cmd);
        }

        return OperationResult.from(operation, false);
    }

    // ---------------------------------------------------------------------------------------------
    // Pipeline steps
    // ---------------------------------------------------------------------------------------------

    private void validateCommand(CreateOperationCommand cmd) {
        if (cmd == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "CreateOperationCommand must not be null");
        }
        if (cmd.getStoreId() == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "storeId is required");
        }
        if (cmd.getOperationScope() == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "operationScope is required");
        }
        if (cmd.getOperationSource() == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "operationSource is required");
        }
        if (!hasText(cmd.getEntityType())) {
            throw new BusinessException(400, "INVALID_OPERATION", "entityType is required");
        }
        if (cmd.getEntityId() == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "entityId is required");
        }
    }

    private void checkPermission(CreateOperationCommand cmd) {
        String permission = cmd.getRequiredPermission();
        if (!hasText(permission)) {
            return; // internally-initiated, non-interactive Operation: no permission gate.
        }
        if (!permissionChecker.hasPermission(permission)) {
            throw new BusinessException(403, "FORBIDDEN",
                    "缺少执行该操作所需的权限：" + permission);
        }
    }

    private void validateOwnership(CreateOperationCommand cmd) {
        if (!SecurityUtils.isAuthenticated()) {
            // Non-interactive actor (scheduled job / background trigger) runs as the reserved system
            // actor (Req 12.5); per-user data-scope ownership validation does not apply to it.
            return;
        }
        CurrentUser user = SecurityUtils.getCurrentUser();
        // Validate the owning Store is within the caller's effective data scope (Req 24.1, 25.1).
        // assertCanWrite reads the storeId dimension off the supplied record via reflection.
        dataScopeService.assertCanWrite(new StoreScoped(cmd.getStoreId()), user);
    }

    /**
     * In-flight conflict lock (Req 5.6) with coalescing awareness (Req 5.3): when an Unsettled_State
     * Operation already exists on the object, a repeated activation carrying the SAME
     * {@code logicalIdempotencyKey} coalesces to it (returned), while a genuinely different change is
     * rejected with an {@link InFlightConflictException}.
     */
    private Optional<OperationResult> checkInFlightConflict(CreateOperationCommand cmd) {
        Optional<OperationEntity> existing = inFlightConflictLock.findInFlightOperation(
                cmd.getEntityType(), cmd.getEntityId(), cmd.getField());
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        OperationEntity inFlight = existing.get();
        if (hasText(cmd.getLogicalIdempotencyKey())
                && cmd.getLogicalIdempotencyKey().equals(inFlight.getLogicalIdempotencyKey())) {
            // Repeated activation of the same logical change — coalesce rather than reject (Req 5.3).
            return Optional.of(OperationResult.from(inFlight, true));
        }
        throw new InFlightConflictException(
                cmd.getEntityType(),
                cmd.getEntityId(),
                inFlight.getId(),
                OperationMachineValues.toSyncState(inFlight.getSyncState()));
    }

    private Optional<OperationResult> coalesce(CreateOperationCommand cmd) {
        if (!hasText(cmd.getLogicalIdempotencyKey())) {
            return Optional.empty();
        }
        return idempotencyService
                .findLogicalOperation(cmd.getStoreId(), cmd.getLogicalIdempotencyKey())
                .map(existing -> OperationResult.from(existing, true));
    }

    private SyncState resolveSyncState(boolean writeCapable, boolean approvalRequired) {
        if (!writeCapable) {
            // Not write-capable: resolve to the terminal local-only state, no submission (Req 2.4, 3.2, 53.3).
            return SyncState.LOCAL_ONLY;
        }
        if (approvalRequired) {
            // Held for operator approval before submission (Req 4.1, 22.8).
            return SyncState.AWAITING_APPROVAL;
        }
        return SyncState.PENDING;
    }

    // ---------------------------------------------------------------------------------------------
    // Persistence helpers (all run inside the createOperation transaction)
    // ---------------------------------------------------------------------------------------------

    private void writePendingChange(OperationEntity operation, CreateOperationCommand cmd) {
        OperationPendingChangeEntity pendingChange = OperationPendingChangeEntity.builder()
                .operationId(operation.getId())
                .entityType(cmd.getEntityType())
                .entityId(cmd.getEntityId())
                .field(cmd.getField())
                .beforeValue(jsonCodec.toJson(cmd.getBeforeValue()))
                .afterValue(jsonCodec.toJson(cmd.getAfterValue()))
                .status("open")
                .build();
        pendingChangeMapper.insert(pendingChange);
    }

    /**
     * Pending-change overlay for a retry attempt. The before/after values are copied from the
     * original failed record, which already holds them as JSON text, so they are set directly without
     * re-serialization (Req 6.1, 7, 7.8).
     */
    private void writeRetryPendingChange(OperationEntity retryAttempt, OperationEntity original) {
        OperationPendingChangeEntity pendingChange = OperationPendingChangeEntity.builder()
                .operationId(retryAttempt.getId())
                .entityType(retryAttempt.getEntityType())
                .entityId(retryAttempt.getEntityId())
                .field(retryAttempt.getField())
                .beforeValue(original.getBeforeValue())
                .afterValue(original.getAfterValue())
                .status("open")
                .build();
        pendingChangeMapper.insert(pendingChange);
    }

    /**
     * Outbox entry for a retry attempt, carrying the attempt's freshly minted
     * {@code submissionIdempotencyKey} so re-submission is never deduped by the prior attempt's key
     * (Req 5.2/5.7). The original's {@code after_value} is stored as JSON text, so it is decoded back
     * to a domain value before being placed in the payload to avoid double-encoding.
     */
    private void writeRetryOutbox(OperationEntity retryAttempt, OperationEntity original) {
        // The original's after_value is stored as JSON text; decode it back to a domain value before
        // placing it in the payload to avoid double-encoding.
        insertOutbox(retryAttempt.getId(), original.getStoreId(), retryAttempt.getEntityType(),
                retryAttempt.getEntityId(), retryAttempt.getField(),
                () -> jsonCodec.fromJson(original.getAfterValue(), Object.class),
                retryAttempt.getSubmissionIdempotencyKey());
    }

    private void writeAuditLog(OperationEntity operation,
                               UUID actingUserId,
                               SyncState syncState,
                               ExecutionStatus executionStatus) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("operationId", operation.getId() != null ? operation.getId().toString() : null);
        details.put("operationSource", operation.getOperationSource());
        details.put("operationScope", operation.getOperationScope());
        details.put("logicalOperationId",
                operation.getLogicalOperationId() != null ? operation.getLogicalOperationId().toString() : null);
        details.put("field", operation.getField());
        details.put("syncState", OperationMachineValues.toValue(syncState));
        details.put("executionStatus", OperationMachineValues.toValue(executionStatus));
        auditLogService.createLog(
                actingUserId,
                resolveOrgId(),
                AUDIT_ACTION_CREATE,
                operation.getEntityType(),
                operation.getEntityId(),
                details);
    }

    private void writeOutbox(OperationEntity operation, CreateOperationCommand cmd) {
        // submissionIdempotencyKey is minted by the Outbox worker when it submits (Req 5.2). The
        // command's afterValue is already a domain value, so it is used as-is (no decode).
        insertOutbox(operation.getId(), cmd.getStoreId(), cmd.getEntityType(), cmd.getEntityId(),
                cmd.getField(), cmd::getAfterValue, null);
    }

    /**
     * Shared Outbox-row assembly for the three write paths ({@link #writeOutbox},
     * {@link #writeRetryOutbox}, {@link #writeApprovalOutbox}). Resolves the Store's active platform,
     * builds the JSON payload (operationId, entityType, entityId, field, afterValue in that order),
     * and inserts a {@code pending} Outbox entry with {@code attemptCount} 0.
     *
     * <p>The {@code afterValue} is supplied lazily so it is evaluated <em>after</em>
     * {@link #resolveActivePlatform(UUID)}, preserving the exact ordering of the original inlined
     * assembly (the retry/approval paths decode the persisted JSON text into a domain value here).</p>
     */
    private void insertOutbox(UUID operationId, UUID storeId, String entityType, UUID entityId,
                              String field, Supplier<Object> afterValue, String submissionIdempotencyKey) {
        String platform = resolveActivePlatform(storeId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operationId", operationId != null ? operationId.toString() : null);
        payload.put("entityType", entityType);
        payload.put("entityId", entityId != null ? entityId.toString() : null);
        payload.put("field", field);
        payload.put("afterValue", afterValue.get());

        OperationOutboxEntity outbox = OperationOutboxEntity.builder()
                .operationId(operationId)
                .storeId(storeId)
                .platform(platform)
                .payload(jsonCodec.toJson(payload))
                .submissionIdempotencyKey(submissionIdempotencyKey)
                .status("pending")
                .attemptCount(0)
                .build();
        outboxMapper.insert(outbox);
    }

    // ---------------------------------------------------------------------------------------------
    // Resolution helpers
    // ---------------------------------------------------------------------------------------------

    private UUID resolveActingUserId() {
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        if (hasText(userId)) {
            UUID parsed = parseUuidOrNull(userId);
            if (parsed != null) {
                return parsed;
            }
        }
        // Non-interactive actor (scheduled job / background trigger) — the reserved system actor (Req 12.5).
        return UUID.fromString(SecurityUtils.SYSTEM_ACTOR_ID);
    }

    private UUID resolveOrgId() {
        if (!SecurityUtils.isAuthenticated()) {
            return null;
        }
        return parseUuidOrNull(SecurityUtils.getCurrentOrgId());
    }

    /**
     * Resolve the platform of the Store's valid active connection for the Outbox row. Because the
     * Outbox is only written when the Store is write-capable, a connected connection exists.
     */
    private String resolveActivePlatform(UUID storeId) {
        PlatformConnectionEntity connection = platformConnectionMapper.selectList(
                        new LambdaQueryWrapper<PlatformConnectionEntity>()
                                .eq(PlatformConnectionEntity::getStoreId, storeId)
                                .eq(PlatformConnectionEntity::getStatus, STATUS_CONNECTED)
                                .last("LIMIT 1"))
                .stream()
                .findFirst()
                .orElse(null);
        if (connection == null || !hasText(connection.getPlatform())) {
            // Defensive: a write-capable Store must have a connected connection.
            throw new BusinessException(409, "STORE_NOT_WRITE_CAPABLE",
                    "无法解析店铺的有效平台连接，无法创建平台提交任务");
        }
        return connection.getPlatform();
    }

    private static UUID parseUuidOrNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle methods — implemented by the Requirement 4/8/12 tasks (section 7).
    // ---------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public OperationResult transition(UUID operationId, TransitionEvent event) {
        if (operationId == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "operationId is required");
        }
        if (event == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "transition event is required");
        }
        OperationEntity operation = operationRecordService.findById(operationId)
                .orElseThrow(() -> new BusinessException(404, "OPERATION_NOT_FOUND",
                        "未找到要流转的操作记录：" + operationId));
        return applyTransition(operation, event, null);
    }

    /**
     * The single, reusable internal transition helper that every Sync_State change routes through.
     *
     * <p>It consults the {@link OperationStateMachine} — the sole authority for legal transitions
     * (Req 4.1) — for the resulting Sync_State, persists that state (and an optional status reason)
     * on the Operation_Record, and then enforces the confirmed-value invariant: the affected entity's
     * Amazon-confirmed value is set to the Operation's after value EXACTLY when (and only when) the
     * Operation becomes {@code effective}; for every other resulting state the confirmed value is left
     * unchanged (Req 3.4, 3.5, 4.4, 4.7, 4.11, 6.4, 7.4, 7.5, 12.1). The pending-change record is
     * closed when the Operation settles ({@code effective}, {@code cancelled}, or {@code superseded})
     * and left open while the Operation is still in an Unsettled_State so the Pending_Overlay keeps
     * surfacing it (Req 7.3, 7.5).</p>
     *
     * <p><b>Req 5.8 — effective-with-external-version-change reconciliation.</b> When the state machine
     * would land in {@code effective} but the affected entity's CURRENT confirmed value has diverged
     * from this Operation's recorded {@code before_value} (another writer changed the same object while
     * this Operation was in flight), the helper does NOT apply last-writer-wins. It records BOTH the
     * platform-confirmed value and the conflicting local value on the Operation's {@code platform_result}
     * and routes the Operation to {@code reconciliation_required} instead, requiring explicit operator
     * resolution before the confirmed value is updated. With no concurrent change detected, the
     * effective transition proceeds normally.</p>
     *
     * <p>The cancel/retry/undo/supersede/approve/reject/publish entry points (the remaining section 7
     * tasks) build on this helper rather than mutating {@code sync_state} themselves.</p>
     *
     * @param operation    the Operation_Record to transition; must be a {@code platform_mutation}
     *                     carrying a Sync_State
     * @param event        the lifecycle event driving the transition
     * @param statusReason optional human-readable reason recorded for failed/expired/cancel-requested
     *                     /reconciliation states; may be {@code null}
     * @return the result reflecting the Operation's new Sync_State
     */
    OperationResult applyTransition(OperationEntity operation, TransitionEvent event, String statusReason) {
        SyncState from = resolveCurrentSyncState(operation);

        // The state machine is the single authority for the resulting Sync_State (Req 4.1). An illegal
        // (state, event) pair is rejected rather than silently applied.
        SyncState to;
        try {
            to = stateMachine.transition(from, event);
        } catch (IllegalStateException e) {
            throw new BusinessException(409, "ILLEGAL_OPERATION_TRANSITION", e.getMessage());
        }

        // Req 5.8: effective-with-external-version-change reconciliation. When the platform confirms
        // the change effective BUT the local object's confirmed value changed during the external
        // execution (a newer local change moved it away from this Operation's recorded before value),
        // the Advertising_Module MUST NOT silently overwrite the local object with the
        // platform-confirmed value (no last-writer-wins). Instead it records BOTH the
        // platform-confirmed value and the conflicting local change and routes the Operation to
        // reconciliation_required, which requires explicit operator resolution before the confirmed
        // value is updated. When no concurrent local change is detected, the effective transition
        // proceeds normally (last-writer-wins is fine when nothing else changed).
        if (to == SyncState.EFFECTIVE
                && confirmedValueWriter.confirmedValueChangedSince(
                        operation.getEntityType(), operation.getEntityId(),
                        operation.getField(), operation.getBeforeValue())) {
            Object platformConfirmedValue = jsonCodec.fromJson(operation.getAfterValue(), Object.class);
            Object conflictingLocalValue = confirmedValueWriter.readConfirmedValue(
                    operation.getEntityType(), operation.getEntityId(), operation.getField());
            recordReconciliationConflict(operation, platformConfirmedValue, conflictingLocalValue);
            to = SyncState.RECONCILIATION_REQUIRED;
            statusReason = "操作在平台已生效，但本地确认值在执行期间被其他写入修改，"
                    + "为避免后写覆盖，已记录平台确认值与冲突的本地值并转入待人工核对（reconciliation_required）。";
        }

        // Persist the new Sync_State (and optional reason) on the Operation_Record. updated_at is
        // refreshed by the @UpdateTimestamp column (Req 4.6).
        persistSyncState(operation, to, statusReason);
        operation.setSyncState(OperationMachineValues.toValue(to));
        if (hasText(statusReason)) {
            operation.setStatusReason(statusReason);
        }

        // CRITICAL INVARIANT (Req 3.4/3.5/6.4/7.4/7.5/12.1, 5.8): the entity's confirmed (Amazon-truth)
        // value is set to the Operation's after value EXACTLY when the Operation becomes effective,
        // and is left unchanged for every other resulting state — including the reconciliation_required
        // outcome of the Req 5.8 guard above, which deliberately does NOT apply the platform value.
        if (to == SyncState.EFFECTIVE) {
            confirmedValueWriter.applyConfirmedValue(
                    operation.getEntityType(), operation.getEntityId(),
                    operation.getField(), operation.getAfterValue());
        }

        // Close the pending-change record once the Operation settles. effective settles applied;
        // cancelled/superseded settle never-applied. failed/expired/reconciliation_required stay open:
        // failed is retained for retry/discard (Req 7.8), and expired/reconciliation_required are NOT
        // terminal and may still reach effective later (Req 7.5).
        if (to == SyncState.EFFECTIVE || to == SyncState.CANCELLED || to == SyncState.SUPERSEDED) {
            closePendingChange(operation.getId());
        }

        // Report the terminal write-back outcome to the failure-alerting monitor (Req 51.7). A
        // transition to effective is a success and to failed is a failure for this Store +
        // Write_Connector; every other resulting state is in-flight and reports nothing. Alerting is
        // a best-effort side effect — it must never break or roll back this transition.
        recordWriteBackOutcome(operation, to);

        // Forensic AUDIT-TRAIL entry for the state transition (additive, best-effort). Every
        // lifecycle change (approve/reject/cancel/undo/supersede/reconcile) routes through this
        // single chokepoint, so one write here captures them all. It must never break or roll back
        // the transition, and carries only non-secret state metadata.
        auditTransition(operation, event, from, to, statusReason);

        return OperationResult.from(operation, false);
    }

    /**
     * Write a forensic AUDIT-TRAIL entry for an Operation Sync_State transition. Auditing is additive
     * and best-effort: any failure here is logged and swallowed so it can never break the transition,
     * alter its transaction boundary, or change its return value. Details carry only non-secret state
     * metadata (event, fromState, toState, reason). This is distinct from the CREATE audit written by
     * {@link #createOperation}, which does not route through this helper.
     */
    private void auditTransition(OperationEntity operation, TransitionEvent event,
                                 SyncState from, SyncState to, String reason) {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("event", event != null ? event.name() : null);
            details.put("fromState", OperationMachineValues.toValue(from));
            details.put("toState", OperationMachineValues.toValue(to));
            details.put("reason", reason);
            auditLogService.createLog(
                    resolveActingUserId(),
                    resolveOrgId(),
                    "OPERATION_TRANSITION",
                    "operation",
                    operation != null ? operation.getId() : null,
                    details);
        } catch (Exception ex) {
            log.warn("Failed to write audit log action=OPERATION_TRANSITION operationId={}: {}",
                    operation != null ? operation.getId() : null, ex.getMessage());
        }
    }

    /**
     * Resolve the Operation's current Sync_State, rejecting a {@code local_configuration} Operation:
     * the Sync_State lifecycle and this transition path apply only to {@code platform_mutation}
     * Operations (Req 3.7).
     */
    private SyncState resolveCurrentSyncState(OperationEntity operation) {
        if (!hasText(operation.getSyncState())) {
            throw new BusinessException(409, "NOT_A_PLATFORM_MUTATION",
                    "该操作不是平台变更操作，没有可流转的同步状态：" + operation.getId());
        }
        return OperationMachineValues.toSyncState(operation.getSyncState());
    }

    private void persistSyncState(OperationEntity operation, SyncState to, String statusReason) {
        UpdateWrapper<OperationEntity> update = new UpdateWrapper<OperationEntity>()
                .set("sync_state", OperationMachineValues.toValue(to))
                .eq("id", operation.getId().toString());
        if (hasText(statusReason)) {
            update.set("status_reason", statusReason);
        }
        operationMapper.update(null, update);
    }

    private void closePendingChange(UUID operationId) {
        UpdateWrapper<OperationPendingChangeEntity> update = new UpdateWrapper<OperationPendingChangeEntity>()
                .set("status", "closed")
                .eq("operation_id", operationId.toString())
                .eq("status", "open");
        pendingChangeMapper.update(null, update);
    }

    /**
     * Report a terminal write-back outcome to {@link WriteBackAlerting} so the rolling failure-rate /
     * consecutive-failure alert (Req 51.7) and its deduped notification lifecycle (Req 23.2/23.3) are
     * driven off the single transition chokepoint. Only {@code effective} (success) and {@code failed}
     * (failure) are outcomes; every other resulting Sync_State is still in-flight and reports nothing.
     * The connector is the platform of the Store's active connection. Any failure here is swallowed so
     * alerting can never roll back or break the Operation transition that produced the outcome.
     */
    private void recordWriteBackOutcome(OperationEntity operation, SyncState to) {
        if (to != SyncState.EFFECTIVE && to != SyncState.FAILED) {
            return;
        }
        try {
            String connector = resolveActivePlatform(operation.getStoreId());
            writeBackAlerting.recordOutcome(operation.getStoreId(), connector, to == SyncState.EFFECTIVE);
        } catch (RuntimeException e) {
            log.warn("Write-back alerting skipped for Operation {} ({}): {}",
                    operation.getId(), to, e.getMessage());
        }
    }

    /**
     * Record BOTH the platform-confirmed value and the conflicting local value on the Operation's
     * {@code platform_result} for the effective-with-external-version-change reconciliation of
     * Requirement 5.8. The captured payload is what an operator uses to resolve (merge/reconcile) the
     * divergence before the confirmed value is ever updated; the entity's confirmed value is left
     * untouched until that explicit resolution.
     *
     * @param operation              the Operation being routed to {@code reconciliation_required}
     * @param platformConfirmedValue the value the platform confirmed effective (the Operation's after value)
     * @param conflictingLocalValue  the entity's current confirmed value, changed by another writer during execution
     */
    private void recordReconciliationConflict(OperationEntity operation,
                                              Object platformConfirmedValue,
                                              Object conflictingLocalValue) {
        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("reconciliationReason", "EFFECTIVE_WITH_EXTERNAL_VERSION_CHANGE");
        // The value the platform confirmed applied (this Operation's requested after value).
        conflict.put("platformConfirmedValue", platformConfirmedValue);
        // The conflicting local change made by another writer while this Operation was in flight.
        conflict.put("conflictingLocalValue", conflictingLocalValue);
        // The baseline this Operation was created against (its recorded before value) for context.
        conflict.put("operationBaselineValue", jsonCodec.fromJson(operation.getBeforeValue(), Object.class));

        String resultJson = jsonCodec.toJson(conflict);
        UpdateWrapper<OperationEntity> update = new UpdateWrapper<OperationEntity>()
                .set("platform_result", resultJson)
                .eq("id", operation.getId().toString());
        operationMapper.update(null, update);
        operation.setPlatformResult(resultJson);
    }

    /**
     * Cancel an Operation, routing strictly by its current submission state (Req 4.7, 4.8, 56.4).
     *
     * <ul>
     *   <li>{@code pending} / {@code awaiting_approval} (NOT yet submitted) → the Operation is
     *       transitioned DIRECTLY to {@code cancelled} via the {@link TransitionEvent#CANCEL} event
     *       (Req 4.7).</li>
     *   <li>{@code submitted} / {@code amazon-processing} (already submitted or in flight) → the
     *       Operation is transitioned to {@code cancel_requested} via the
     *       {@link TransitionEvent#REQUEST_CANCEL} event, NEVER directly to {@code cancelled},
     *       because a local cancel does NOT guarantee the platform stops; the final platform state is
     *       resolved asynchronously (Req 4.8, 56.4).</li>
     * </ul>
     *
     * <p>Cancel is only legal from the four states above (the Req 56 action matrix offers Cancel only
     * there); any other current Sync_State is rejected. The routing selects the event and defers to
     * the shared {@link #applyTransition(OperationEntity, TransitionEvent, String)} helper, so the
     * {@link OperationStateMachine} remains the sole transition authority and the confirmed-value
     * invariant is preserved — neither {@code cancelled} nor {@code cancel_requested} touches the
     * affected record's confirmed value (Req 4.7).</p>
     *
     * <p>Validates: Requirements 4.7, 4.8, 56.4.</p>
     */
    @Override
    @Transactional
    public OperationResult cancel(UUID operationId) {
        if (operationId == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "operationId is required");
        }
        OperationEntity operation = operationRecordService.findById(operationId)
                .orElseThrow(() -> new BusinessException(404, "OPERATION_NOT_FOUND",
                        "未找到要取消的操作记录：" + operationId));

        SyncState current = resolveCurrentSyncState(operation);
        switch (current) {
            case PENDING:
            case AWAITING_APPROVAL:
                // Not yet submitted: cancel locally and directly settle as cancelled (Req 4.7). No
                // statusReason — a direct operator cancel of a not-yet-submitted Operation is settled.
                return applyTransition(operation, TransitionEvent.CANCEL, null);
            case SUBMITTED:
            case AMAZON_PROCESSING:
                // Already submitted / in flight: a local cancel cannot guarantee the platform stops,
                // so request cancellation and let the platform resolution path settle it (Req 4.8,
                // 56.4). The reason is recorded for the cancel_requested state.
                return applyTransition(operation, TransitionEvent.REQUEST_CANCEL,
                        "操作员请求取消已提交的操作，等待平台确认最终状态");
            default:
                // Cancel is not a legal action for any other Sync_State (Req 56 action matrix).
                throw new BusinessException(409, "ILLEGAL_OPERATION_TRANSITION",
                        "当前同步状态不支持取消操作：" + OperationMachineValues.toValue(current));
        }
    }

    /**
     * Supersede an in-flight Operation that a newer Operation replaces against the same object,
     * routing strictly by its current submission state (Req 4.11, 49.14).
     *
     * <p>A supersession happens when a newer Operation replaces an Operation that has not yet finally
     * settled on the platform — most notably after an AI_Personality switch that recomputes hosting
     * Operations for the affected Campaign(s) (Req 49.14). The fate of the replaced Operation is
     * decided purely by whether it has already been submitted:</p>
     *
     * <ul>
     *   <li>{@code pending} / {@code awaiting_approval} (NOT yet submitted) → the replaced Operation
     *       is transitioned DIRECTLY to {@code superseded} via the {@link TransitionEvent#SUPERSEDE}
     *       event; it never reached the platform, so it can be settled locally (Req 4.11).</li>
     *   <li>{@code submitted} / {@code amazon-processing} (already submitted or in flight) → the
     *       replaced Operation is routed through the {@code cancel_requested} path via the
     *       {@link TransitionEvent#REQUEST_CANCEL} event, NEVER directly to {@code superseded},
     *       because the change may already be applying on the platform; the final platform state is
     *       resolved asynchronously by the cancel-request resolution path of Req 4.8/4.9 (Req 4.11).</li>
     * </ul>
     *
     * <p>Supersession is only meaningful for an Operation still in one of those four in-flight states;
     * any other current Sync_State (a settled or already-cancel-requested Operation) is rejected. The
     * routing selects the event and defers to the shared
     * {@link #applyTransition(OperationEntity, TransitionEvent, String)} helper, so the
     * {@link OperationStateMachine} remains the sole transition authority and the confirmed-value
     * invariant is preserved — neither {@code superseded} nor {@code cancel_requested} touches the
     * affected record's confirmed value (Req 4.11).</p>
     *
     * <p>Validates: Requirements 4.11, 49.14.</p>
     */
    @Override
    @Transactional
    public OperationResult supersede(UUID operationId) {
        if (operationId == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "operationId is required");
        }
        OperationEntity operation = operationRecordService.findById(operationId)
                .orElseThrow(() -> new BusinessException(404, "OPERATION_NOT_FOUND",
                        "未找到要被替代的操作记录：" + operationId));

        SyncState current = resolveCurrentSyncState(operation);
        switch (current) {
            case PENDING:
            case AWAITING_APPROVAL:
                // Not yet submitted: the replaced Operation never reached the platform, so settle it
                // directly as superseded (Req 4.11). No statusReason — a not-yet-submitted supersession
                // is settled outright.
                return applyTransition(operation, TransitionEvent.SUPERSEDE, null);
            case SUBMITTED:
            case AMAZON_PROCESSING:
                // Already submitted / in flight: a newer Operation cannot locally guarantee the
                // platform stops the in-flight change, so route through the cancel_requested path
                // rather than flipping straight to superseded (Req 4.11). The reason is recorded for
                // the cancel_requested state.
                return applyTransition(operation, TransitionEvent.REQUEST_CANCEL,
                        "操作被更新的操作替代（例如人格切换后重算），已提交的操作改走取消请求路径等待平台确认最终状态");
            default:
                // Supersession is only meaningful for an in-flight, not-yet-settled Operation; any
                // other Sync_State (settled, cancel_requested, expired, reconciliation_required) is
                // rejected rather than silently no-op'd.
                throw new BusinessException(409, "ILLEGAL_OPERATION_TRANSITION",
                        "当前同步状态不支持替代操作：" + OperationMachineValues.toValue(current));
        }
    }

    /**
     * Retry a {@code failed} Operation as a FRESH attempt (Req 4.2, 7.8).
     *
     * <p>The original {@code failed} Operation_Record is left completely UNCHANGED — it is retained
     * for audit and never mutated. A brand-new attempt is created in the {@code pending} state under
     * the SAME {@code logicalOperationId} and {@code logicalIdempotencyKey} (so the full attempt
     * history of the one logical change stays grouped), but with a NEW {@code attemptId}, a NEW
     * {@code submissionIdempotencyKey} (so this attempt is never blocked or deduped by the prior
     * attempt's submission key, Req 5.2/5.7), and an incremented {@code attemptNumber}. The new
     * attempt copies the original's scope/source/entity/field and its before/after values (the failed
     * pending value is retried, Req 7.8); the Amazon-confirmed value is left unchanged in both the
     * retry and discard paths.</p>
     *
     * <p>Persistence mirrors {@code createOperation} and runs in ONE transaction with NO platform
     * call: the new Operation_Record, its pending-change overlay, an audit entry, and — only when the
     * Store is write-capable — the Outbox entry that drives re-submission (Req 6.1, 6.3). Retry is
     * only legal from {@code failed} (the Req 56 action matrix offers Retry only there); any other
     * current Sync_State is rejected.</p>
     *
     * <p>Validates: Requirements 4.2, 7.8.</p>
     */
    @Override
    @Transactional
    public OperationResult retry(UUID operationId) {
        if (operationId == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "operationId is required");
        }
        OperationEntity original = operationRecordService.findById(operationId)
                .orElseThrow(() -> new BusinessException(404, "OPERATION_NOT_FOUND",
                        "未找到要重试的操作记录：" + operationId));

        // Retry is only legal from the failed state (Req 4.2, Req 56 action matrix). A
        // local_configuration Operation carries no Sync_State and is rejected by resolveCurrentSyncState.
        SyncState current = resolveCurrentSyncState(original);
        if (current != SyncState.FAILED) {
            throw new BusinessException(409, "ILLEGAL_OPERATION_TRANSITION",
                    "仅可重试处于 failed 状态的操作，当前状态：" + OperationMachineValues.toValue(current));
        }

        UUID actingUserId = resolveActingUserId();
        int nextAttemptNumber = (original.getAttemptNumber() != null ? original.getAttemptNumber() : 1) + 1;

        // A FRESH attempt: new attemptId + new submissionIdempotencyKey + incremented attemptNumber,
        // under the SAME logicalOperationId / logicalIdempotencyKey. The original failed record is NOT
        // touched (Req 4.2, 7.8).
        OperationRecordCommand recordCommand = OperationRecordCommand.builder()
                .storeId(original.getStoreId())
                .operationSource(OperationMachineValues.toOperationSource(original.getOperationSource()))
                .operationScope(OperationMachineValues.toOperationScope(original.getOperationScope()))
                .entityType(original.getEntityType())
                .entityId(original.getEntityId())
                .field(original.getField())
                .logicalOperationId(original.getLogicalOperationId())
                .logicalIdempotencyKey(original.getLogicalIdempotencyKey())
                .attemptId(UUID.randomUUID())
                .submissionIdempotencyKey(idempotencyService.newSubmissionIdempotencyKey())
                .attemptNumber(nextAttemptNumber)
                .parentOperationId(original.getParentOperationId())
                // before/after carry the failed pending value forward (Req 7.8). The entity stores
                // them as JSON text; round-trip through the codec so the assembler re-serializes a
                // domain value rather than double-encoding the stored string.
                .beforeValue(jsonCodec.fromJson(original.getBeforeValue(), Object.class))
                .afterValue(jsonCodec.fromJson(original.getAfterValue(), Object.class))
                .reversible(original.getReversible())
                .affectedCount(original.getAffectedCount())
                .actingUserId(actingUserId)
                .syncState(SyncState.PENDING)
                // platform_mutation carries no executionStatus (Req 3.7/3.8).
                .executionStatus(null)
                // Preserve the AI-decision audit fields so an ai_hosting retry stays audit-complete (Req 49.9).
                .personalityRuleVersion(original.getPersonalityRuleVersion())
                .aiDecision(jsonCodec.fromJson(original.getAiDecision(), AiDecision.class))
                .build();

        OperationEntity retryAttempt = operationRecordService.record(recordCommand);

        // Pending-change overlay for the new pending attempt (mirrors createOperation; Req 6.1, 7).
        if (hasText(retryAttempt.getField())) {
            writeRetryPendingChange(retryAttempt, original);
        }

        // Audit the retry as a new Operation creation (Req 6.1).
        writeAuditLog(retryAttempt, actingUserId, SyncState.PENDING, null);

        // Outbox entry to drive re-submission — only when the Store is write-capable (Req 6.1, 53.3).
        if (writeCapabilityService.isWriteCapable(original.getStoreId())) {
            writeRetryOutbox(retryAttempt, original);
        }

        return OperationResult.from(retryAttempt, false);
    }

    /**
     * Undo an {@code effective} Operation by recording a NEW compensating Operation_Record that
     * restores the original's before value (Req 8.4, 8.5, 56.3, 22.10).
     *
     * <p>Undo is offered — and accepted — ONLY when all three conditions of the Req 56 action matrix
     * and Req 8 criterion 4 hold:</p>
     *
     * <ol>
     *   <li>the original Operation's Sync_State is {@code effective} (a {@code local_configuration}
     *       Operation carries no Sync_State and is rejected by {@link #resolveCurrentSyncState});</li>
     *   <li>its {@code reversible} flag is {@code true};</li>
     *   <li>its before value is still valid — i.e. the field has NOT since been changed by a newer
     *       {@code effective} Operation, so the affected entity's current confirmed value still equals
     *       this Operation's after value and the compensating Operation's before value is therefore
     *       consistent.</li>
     * </ol>
     *
     * <p>When any condition fails, undo is rejected with a clear error rather than silently no-op'd.
     * When all hold, a brand-new compensating {@code platform_mutation} Operation is created through
     * the normal {@link #createOperation(CreateOperationCommand)} path — so it gets its own
     * lifecycle, Outbox entry, and audit record (Req 22.10) — with its before/after values SWAPPED
     * relative to the original (before = original.after, after = original.before) to roll the field
     * back. The original Operation_Record is NEVER deleted or mutated (Req 8.5); the compensating
     * action is recorded as a distinct new record under a fresh logical identity.</p>
     *
     * <p>Validates: Requirements 8.4, 8.5, 56.3, 22.10.</p>
     */
    @Override
    @Transactional
    public OperationResult undo(UUID operationId) {
        if (operationId == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "operationId is required");
        }
        OperationEntity original = operationRecordService.findById(operationId)
                .orElseThrow(() -> new BusinessException(404, "OPERATION_NOT_FOUND",
                        "未找到要撤销的操作记录：" + operationId));

        // Condition 1 (Req 56.3): undo is legal only from effective. A local_configuration Operation
        // carries no Sync_State and is rejected by resolveCurrentSyncState.
        SyncState current = resolveCurrentSyncState(original);
        if (current != SyncState.EFFECTIVE) {
            throw new BusinessException(409, "ILLEGAL_OPERATION_TRANSITION",
                    "仅可撤销处于 effective 状态的操作，当前状态：" + OperationMachineValues.toValue(current));
        }

        // Condition 2 (Req 8.4): the Operation must be reversible.
        if (!Boolean.TRUE.equals(original.getReversible())) {
            throw new BusinessException(409, "OPERATION_NOT_REVERSIBLE",
                    "该操作不可撤销（reversible=false），无法创建补偿操作");
        }

        // Condition 3 (Req 8.4): the before value must still be valid — no newer effective Operation
        // has since changed the same field, so the entity's current confirmed value still equals this
        // Operation's after value and the compensating before value stays consistent.
        if (hasNewerEffectiveOperationForField(original)) {
            throw new BusinessException(409, "UNDO_BEFORE_VALUE_STALE",
                    "该字段已被更新的生效操作修改，撤销的初始值已失效，无法撤销");
        }

        UUID actingUserId = resolveActingUserId();

        // Record the compensating action as a NEW Operation_Record (Req 8.5) routed through the normal
        // creation path so it gets its own Sync_State lifecycle, Outbox entry, and audit (Req 22.10).
        // The before/after values are SWAPPED to roll the field back. The original's values are stored
        // as JSON text; round-trip through the codec so the assembler re-serializes a domain value
        // rather than double-encoding the stored string (mirrors retry).
        CreateOperationCommand compensating = CreateOperationCommand.builder()
                .storeId(original.getStoreId())
                .operationSource(OperationMachineValues.toOperationSource(original.getOperationSource()))
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType(original.getEntityType())
                .entityId(original.getEntityId())
                .field(original.getField())
                // SWAP: compensating before = original after; compensating after = original before.
                .beforeValue(jsonCodec.fromJson(original.getAfterValue(), Object.class))
                .afterValue(jsonCodec.fromJson(original.getBeforeValue(), Object.class))
                // The compensating Operation is itself reversible so an undo can be undone.
                .reversible(Boolean.TRUE)
                .affectedCount(original.getAffectedCount())
                // Fresh logical identity: a compensating Operation is a new logical change, never a
                // coalesced repeat — leave logicalIdempotencyKey null so createOperation mints one.
                .build();

        log.info("Recording compensating undo Operation for original {} on {}#{} field={} by user {}",
                original.getId(), original.getEntityType(), original.getEntityId(),
                original.getField(), actingUserId);

        return createOperation(compensating);
    }

    /**
     * @return {@code true} when an {@code effective} Operation against the SAME object newer than
     *         {@code original} has since changed the field, invalidating the original's before value
     *         for undo (Req 8.4). A field-scoped original is invalidated by a newer effective change
     *         to the same field OR by a newer whole-object (null-field) effective Operation.
     */
    private boolean hasNewerEffectiveOperationForField(OperationEntity original) {
        LambdaQueryWrapper<OperationEntity> wrapper = new LambdaQueryWrapper<OperationEntity>()
                .eq(OperationEntity::getEntityType, original.getEntityType())
                .eq(OperationEntity::getEntityId, original.getEntityId())
                .eq(OperationEntity::getSyncState, OperationMachineValues.toValue(SyncState.EFFECTIVE))
                // strictly newer than the original, excluding the original itself.
                .ne(OperationEntity::getId, original.getId().toString())
                .gt(OperationEntity::getCreatedAt, original.getCreatedAt());

        if (hasText(original.getField())) {
            // A whole-object (null-field) effective Operation also changes the field; a field-scoped
            // newer Operation against the same field does too.
            wrapper.and(w -> w.eq(OperationEntity::getField, original.getField())
                    .or()
                    .isNull(OperationEntity::getField));
        }

        wrapper.last("LIMIT 1");
        return operationMapper.selectOne(wrapper) != null;
    }

    /**
     * Reconcile an Operation whose final platform state is uncertain against the platform's actual
     * state, transitioning it into {@code reconciliation_required} for explicit operator resolution
     * (Req 4.10, 56.5).
     *
     * <p>An Operation reaches an uncertain, not-yet-settled state in exactly two ways: a
     * submitted/in-flight change was asked to cancel and the platform's final state is not yet known
     * ({@code cancel_requested}), or a submitted change timed out before the platform confirmed an
     * outcome ({@code expired}). These are precisely the two states from which the
     * {@link OperationStateMachine} allows the {@link TransitionEvent#RECONCILE} edge. Reconcile
     * routes that event through the shared
     * {@link #applyTransition(OperationEntity, TransitionEvent, String)} helper, so the state machine
     * remains the sole transition authority and the confirmed-value invariant is preserved:
     * {@code reconciliation_required} is NOT {@code effective}, so the affected record's confirmed
     * value is left unchanged and the pending-change overlay stays open pending operator resolution
     * (Req 3.4, 3.5, 4.10).</p>
     *
     * <p>Reconcile is only legal from {@code cancel_requested} or {@code expired} (the only states
     * carrying a RECONCILE edge, matching the Req 56 action matrix); any other current Sync_State
     * (and a {@code local_configuration} Operation, which carries no Sync_State) is rejected rather
     * than silently no-op'd. Failing fast here yields a clearer message than the generic illegal-edge
     * rejection {@code applyTransition} would otherwise raise.</p>
     *
     * <p>Validates: Requirements 4.10, 56.5.</p>
     */
    @Override
    @Transactional
    public OperationResult reconcile(UUID operationId) {
        if (operationId == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "operationId is required");
        }
        OperationEntity operation = operationRecordService.findById(operationId)
                .orElseThrow(() -> new BusinessException(404, "OPERATION_NOT_FOUND",
                        "未找到要核对的操作记录：" + operationId));

        // Reconcile is only legal from cancel_requested or expired (the only states with a RECONCILE
        // edge, Req 4.10, 56.5). resolveCurrentSyncState rejects a local_configuration Operation
        // (no Sync_State).
        SyncState current = resolveCurrentSyncState(operation);
        if (current != SyncState.CANCEL_REQUESTED && current != SyncState.EXPIRED) {
            throw new BusinessException(409, "ILLEGAL_OPERATION_TRANSITION",
                    "仅可核对处于 cancel_requested 或 expired 状态的操作，当前状态：" + OperationMachineValues.toValue(current));
        }

        // cancel_requested | expired -> reconciliation_required via the RECONCILE event (Req 4.10).
        // The confirmed value is left unchanged — applyTransition writes it only on effective — and
        // the pending-change overlay stays open until an operator resolves the divergence.
        return applyTransition(operation, TransitionEvent.RECONCILE,
                "操作的最终平台状态不确定，已转入待人工核对（reconciliation_required）以对齐平台实际状态");
    }

    /**
     * Approve an Operation that is held for operator approval, transitioning it from
     * {@code awaiting_approval} to {@code submitted} (Req 4.1, 22.8).
     *
     * <p>An Operation lands in {@code awaiting_approval} when its absolute change ratio meets/exceeds
     * the approval threshold (Req 22.8); {@link #createOperation(CreateOperationCommand)}
     * deliberately skips the Outbox for such an Operation, so a held Operation has NO Outbox entry
     * and is therefore not yet submittable. Approval routes the {@link TransitionEvent#APPROVE} event
     * through the shared {@link #applyTransition(OperationEntity, TransitionEvent, String)} helper —
     * so the {@link OperationStateMachine} stays the sole transition authority and the confirmed-value
     * invariant is preserved (submission does not touch the confirmed value) — and then writes the
     * Outbox entry now (mirroring {@code createOperation}'s {@code writeOutbox}) so the OutboxWorker
     * actually submits the approved change to the platform (Req 6.1, 7.7, 24.2).</p>
     *
     * <p>Approval is only legal from {@code awaiting_approval}; any other current Sync_State (and a
     * {@code local_configuration} Operation, which carries no Sync_State) is rejected. Because the
     * transition lands in {@code pending} (Req 7.7/24.2) and the Outbox will claim and submit, it is
     * gated on the Store still being write-capable (Req 4.12, 53): if the Store is no longer
     * write-capable the approval is rejected rather than driving the Operation into a submission
     * path that cannot be honored.</p>
     *
     * <p>Validates: Requirements 4.1, 7.7, 22.8, 24.2.</p>
     */
    @Override
    @Transactional
    public OperationResult approve(UUID operationId) {
        if (operationId == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "operationId is required");
        }
        OperationEntity operation = operationRecordService.findById(operationId)
                .orElseThrow(() -> new BusinessException(404, "OPERATION_NOT_FOUND",
                        "未找到要审批通过的操作记录：" + operationId));

        // Approve is only legal from awaiting_approval (Req 4.1). resolveCurrentSyncState rejects a
        // local_configuration Operation (no Sync_State); applyTransition would also reject an illegal
        // (state, APPROVE) pair, but failing fast here yields a clearer message.
        SyncState current = resolveCurrentSyncState(operation);
        if (current != SyncState.AWAITING_APPROVAL) {
            throw new BusinessException(409, "ILLEGAL_OPERATION_TRANSITION",
                    "仅可审批通过处于 awaiting_approval 状态的操作，当前状态：" + OperationMachineValues.toValue(current));
        }

        // The transition lands in pending, which leads to platform-execution via the Outbox: it is
        // only legal while the Store is write-capable (Req 4.12, 53). A held Operation was created
        // write-capable, but capability may have lapsed since; reject rather than drive it into
        // a submission path.
        if (!writeCapabilityService.isWriteCapable(operation.getStoreId())) {
            throw new BusinessException(409, "STORE_NOT_WRITE_CAPABLE",
                    "店铺当前不可写入，无法审批通过并提交该操作");
        }

        // awaiting_approval -> pending via the APPROVE event (Req 7.7, 24.2). The confirmed value is
        // left unchanged — applyTransition writes it only on effective.
        OperationResult result = applyTransition(operation, TransitionEvent.APPROVE, null);

        // createOperation skips the Outbox for an awaiting_approval Operation, so the now-approved
        // Operation has no Outbox entry yet. Write it now (mirrors createOperation's writeOutbox) so
        // the OutboxWorker submits the approved change to the platform (Req 6.1). The Operation stores
        // its after value as JSON text; round-trip it through the codec so the payload carries a
        // domain value rather than a double-encoded string.
        writeApprovalOutbox(operation);

        return result;
    }

    /**
     * Reject an Operation that is held for operator approval, transitioning it from
     * {@code awaiting_approval} to {@code cancelled} (Req 4.1, 22.8).
     *
     * <p>Rejection routes the {@link TransitionEvent#REJECT} event through the shared
     * {@link #applyTransition(OperationEntity, TransitionEvent, String)} helper, so the
     * {@link OperationStateMachine} stays the sole transition authority. The Operation never reached
     * the platform, so NO Outbox entry and NO platform submission occur, and the affected record's
     * confirmed value is left unchanged (applyTransition writes it only on {@code effective}); the
     * pending-change overlay is closed as the Operation settles as {@code cancelled}. A status reason
     * is recorded so a rejection is distinguishable from a plain operator cancel in the cancelled
     * state.</p>
     *
     * <p>Rejection is only legal from {@code awaiting_approval}; any other current Sync_State (and a
     * {@code local_configuration} Operation, which carries no Sync_State) is rejected.</p>
     *
     * <p>Validates: Requirements 4.1, 22.8.</p>
     */
    @Override
    @Transactional
    public OperationResult reject(UUID operationId) {
        if (operationId == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "operationId is required");
        }
        OperationEntity operation = operationRecordService.findById(operationId)
                .orElseThrow(() -> new BusinessException(404, "OPERATION_NOT_FOUND",
                        "未找到要驳回的操作记录：" + operationId));

        // Reject is only legal from awaiting_approval (Req 4.1). resolveCurrentSyncState rejects a
        // local_configuration Operation (no Sync_State).
        SyncState current = resolveCurrentSyncState(operation);
        if (current != SyncState.AWAITING_APPROVAL) {
            throw new BusinessException(409, "ILLEGAL_OPERATION_TRANSITION",
                    "仅可驳回处于 awaiting_approval 状态的操作，当前状态：" + OperationMachineValues.toValue(current));
        }

        // awaiting_approval -> cancelled via the REJECT event (Req 4.1, 22.8). No platform submission;
        // the confirmed value is left unchanged. The reason marks this as a rejection.
        return applyTransition(operation, TransitionEvent.REJECT, "操作员驳回了待审批的操作");
    }

    /**
     * Write the Outbox entry for an Operation that was just approved out of {@code awaiting_approval}
     * into {@code submitted}. Mirrors {@link #writeOutbox(OperationEntity, CreateOperationCommand)}
     * but reads the change values off the persisted {@link OperationEntity} (the after value is stored
     * as JSON text, so it is decoded back to a domain value to avoid double-encoding, mirroring
     * {@link #writeRetryOutbox(OperationEntity, OperationEntity)}). The
     * {@code submissionIdempotencyKey} is left null and minted by the Outbox worker on submission
     * (Req 5.2).
     */
    private void writeApprovalOutbox(OperationEntity operation) {
        // The Operation stores its after value as JSON text; decode it back to a domain value to avoid
        // double-encoding (mirrors writeRetryOutbox). submissionIdempotencyKey is minted by the Outbox
        // worker on submission (Req 5.2).
        insertOutbox(operation.getId(), operation.getStoreId(), operation.getEntityType(),
                operation.getEntityId(), operation.getField(),
                () -> jsonCodec.fromJson(operation.getAfterValue(), Object.class), null);
    }

    /**
     * Publish a terminal {@code local-only} draft Operation to the platform once the Store has become
     * write-capable (Req 12.10).
     *
     * <p>The {@code local-only} Sync_State is TERMINAL with no outgoing transitions (Req 12). When an
     * operator clicks "submit to Amazon" on such a draft, the draft is NOT transitioned; instead a
     * brand-new {@code platform_mutation} Operation — the publish — is created through the normal
     * {@link #createOperation(CreateOperationCommand)} path so it gets its own Sync_State lifecycle,
     * Outbox entry, and audit record. The publish copies the draft's scope-irrelevant change facts
     * (entity/field/before/after/source/affected count and the AI-decision audit fields), mints its
     * OWN new {@code logicalOperationId} (createOperation does this when no
     * {@code logicalIdempotencyKey} is supplied), and carries a {@code parentOperationId} referencing
     * the original draft so the draft and its later platform publish can be correlated and audited
     * (Req 12.10). The original {@code local-only} Operation_Record is NEVER deleted, transitioned, or
     * otherwise mutated.</p>
     *
     * <p>Publish is only legal on an Operation whose Sync_State is the terminal {@code local-only}
     * draft; any other current Sync_State (and a {@code local_configuration} Operation, which carries
     * no Sync_State) is rejected. createOperation re-resolves write-capability: only when the Store is
     * now write-capable does the publish land {@code pending} with an Outbox entry; if the Store is
     * still not write-capable it resolves to {@code local-only} again per createOperation's logic.</p>
     *
     * <p>Validates: Requirements 12.10.</p>
     */
    @Override
    @Transactional
    public OperationResult publishLocalDraft(UUID localOnlyOperationId) {
        if (localOnlyOperationId == null) {
            throw new BusinessException(400, "INVALID_OPERATION", "operationId is required");
        }
        OperationEntity draft = operationRecordService.findById(localOnlyOperationId)
                .orElseThrow(() -> new BusinessException(404, "OPERATION_NOT_FOUND",
                        "未找到要发布的本地草稿操作记录：" + localOnlyOperationId));

        // Publish is only legal on a terminal local-only draft (Req 12.10). resolveCurrentSyncState
        // rejects a local_configuration Operation (no Sync_State); any other Sync_State is rejected
        // rather than silently no-op'd.
        SyncState current = resolveCurrentSyncState(draft);
        if (current != SyncState.LOCAL_ONLY) {
            throw new BusinessException(409, "ILLEGAL_OPERATION_TRANSITION",
                    "仅可发布处于 local-only 状态的本地草稿操作，当前状态：" + OperationMachineValues.toValue(current));
        }

        // Record the publish as a NEW platform_mutation Operation (Req 12.10) routed through the
        // normal creation path so it gets its own Sync_State lifecycle, Outbox entry, and audit. The
        // draft's before/after values are stored as JSON text; round-trip them through the codec so
        // the assembler re-serializes a domain value rather than double-encoding the stored string
        // (mirrors retry/undo). A null logicalIdempotencyKey makes createOperation mint a fresh
        // logicalOperationId; parentOperationId links back to the original draft without transitioning
        // it.
        CreateOperationCommand publish = CreateOperationCommand.builder()
                .storeId(draft.getStoreId())
                .operationSource(OperationMachineValues.toOperationSource(draft.getOperationSource()))
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType(draft.getEntityType())
                .entityId(draft.getEntityId())
                .field(draft.getField())
                .beforeValue(jsonCodec.fromJson(draft.getBeforeValue(), Object.class))
                .afterValue(jsonCodec.fromJson(draft.getAfterValue(), Object.class))
                .reversible(draft.getReversible())
                .affectedCount(draft.getAffectedCount())
                // Fresh logical identity: leave logicalIdempotencyKey null so createOperation mints a
                // new logicalOperationId for the publish (Req 12.10).
                // Link the publish back to the terminal draft without transitioning it (Req 12.10).
                .parentOperationId(draft.getId())
                // Preserve the AI-decision audit fields so an ai_hosting draft's publish stays
                // audit-complete (mirrors retry; Req 49.9).
                .personalityRuleVersion(draft.getPersonalityRuleVersion())
                .aiDecision(jsonCodec.fromJson(draft.getAiDecision(), AiDecision.class))
                .build();

        log.info("Publishing local-only draft Operation {} as a new platform_mutation on {}#{} field={}",
                draft.getId(), draft.getEntityType(), draft.getEntityId(), draft.getField());

        return createOperation(publish);
    }

    /**
     * Minimal store-bearing holder so {@link DataScopeService#assertCanWrite(Object, CurrentUser)}
     * can read the {@code storeId} dimension off it via its conventional field name.
     */
    private static final class StoreScoped {
        @SuppressWarnings("unused")
        private final UUID storeId;

        private StoreScoped(UUID storeId) {
            this.storeId = storeId;
        }
    }
}
