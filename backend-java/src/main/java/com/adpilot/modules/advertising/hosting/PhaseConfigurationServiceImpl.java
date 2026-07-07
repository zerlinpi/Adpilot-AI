package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.OperationOutboxEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.operation.OperationStateMachine;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.advertising.operation.TransitionEvent;
import com.adpilot.modules.advertising.service.HostingAdjustmentType;
import com.adpilot.modules.advertising.service.HostingPhase;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.common.utils.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Implementation of {@link PhaseConfigurationService} managing hosting phase transitions
 * with adjacent-only validation and downgrade cleanup.
 *
 * <p>The phase is stored in the {@code hosting_configs} table at store scope with the
 * key {@code active_phase} in the JSON config payload.</p>
 *
 * <p>Validates: Requirements 17.1, 17.2, 17.3, 17.4, 17.5, 17.6.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhaseConfigurationServiceImpl implements PhaseConfigurationService {

    private final HostingConfigMapper hostingConfigMapper;
    private final OperationMapper operationMapper;
    private final OperationOutboxMapper outboxMapper;
    private final OperationStateMachine stateMachine;
    private final HostingApprovalService approvalService;
    private final AuditLogService auditLogService;

    /**
     * The set of adjacent (legal) phase transitions.
     * Only adjacent phases can transition: V1↔V2, V2↔V3.
     */
    private static final Set<PhaseTransition> ADJACENT_TRANSITIONS = Set.of(
            new PhaseTransition(HostingPhase.V1, HostingPhase.V2),
            new PhaseTransition(HostingPhase.V2, HostingPhase.V3),
            new PhaseTransition(HostingPhase.V2, HostingPhase.V1),
            new PhaseTransition(HostingPhase.V3, HostingPhase.V2)
    );

    @Override
    public HostingPhase getCurrentPhase(UUID storeId) {
        if (storeId == null) {
            return HostingPhase.DEFAULT;
        }

        LambdaQueryWrapper<HostingConfigEntity> query = new LambdaQueryWrapper<>();
        query.eq(HostingConfigEntity::getStoreId, storeId)
                .eq(HostingConfigEntity::getScope, "store")
                .eq(HostingConfigEntity::getScopeId, storeId);

        HostingConfigEntity config = hostingConfigMapper.selectOne(query);
        if (config == null || config.getConfig() == null) {
            return HostingPhase.DEFAULT;
        }

        return extractPhaseFromConfig(config.getConfig());
    }

    @Override
    public boolean validateTransition(HostingPhase currentPhase, HostingPhase newPhase) {
        if (currentPhase == null || newPhase == null) {
            return false;
        }
        if (currentPhase == newPhase) {
            return false;
        }
        return ADJACENT_TRANSITIONS.contains(new PhaseTransition(currentPhase, newPhase));
    }

    @Override
    @Transactional
    public void transitionPhase(UUID storeId, HostingPhase newPhase, UUID actorId) {
        if (storeId == null) {
            throw new BusinessException("HOSTING_INVALID_PARAMETER", "Store ID must not be null");
        }
        if (newPhase == null) {
            throw new BusinessException("HOSTING_INVALID_PARAMETER", "Target phase must not be null");
        }

        HostingPhase currentPhase = getCurrentPhase(storeId);

        if (currentPhase == newPhase) {
            throw new BusinessException("HOSTING_INVALID_PHASE_TRANSITION", "Store is already at phase " + newPhase);
        }

        if (!validateTransition(currentPhase, newPhase)) {
            throw new BusinessException("HOSTING_INVALID_PHASE_TRANSITION",
                    "Invalid phase transition: " + currentPhase + " → " + newPhase
                            + ". Only adjacent transitions are allowed (V1↔V2↔V3).");
        }

        // Determine if this is a downgrade
        boolean isDowngrade = phaseOrdinal(newPhase) < phaseOrdinal(currentPhase);

        Map<String, Integer> cleanupCounts = null;
        if (isDowngrade) {
            // Identify capabilities disabled by the downgrade
            Set<HostingAdjustmentType> disabledCapabilities = computeDisabledCapabilities(currentPhase, newPhase);
            log.info("Phase downgrade {} → {} for store {}: cleaning up operations for disabled capabilities: {}",
                    currentPhase, newPhase, storeId, disabledCapabilities);

            cleanupCounts = performDowngradeCleanup(storeId, disabledCapabilities);
        }

        // Persist the new phase in hosting_configs
        persistPhase(storeId, newPhase, actorId);

        log.info("Phase transition completed: {} → {} for store {} by actor {}",
                currentPhase, newPhase, storeId, actorId);

        recordPhaseAudit(storeId, actorId, currentPhase, newPhase, cleanupCounts);
    }

    /**
     * Write a forensic AUDIT-TRAIL entry for a hosting phase transition. Auditing is additive
     * and best-effort: a failure here is logged and swallowed so it can never break the phase
     * transition. Only phase codes and cleanup counts are recorded — no secret values.
     */
    private void recordPhaseAudit(UUID storeId, UUID actorId, HostingPhase from, HostingPhase to,
                                  Map<String, Integer> cleanupCounts) {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("from", from != null ? from.name() : null);
            details.put("to", to != null ? to.name() : null);
            if (cleanupCounts != null) {
                details.put("cleanupCounts", cleanupCounts);
            }
            auditLogService.createLog(resolveActorId(actorId), resolveOrgId(),
                    "HOSTING_PHASE_CHANGE", "hosting_phase", storeId, details);
        } catch (Exception ex) {
            log.warn("Failed to write phase-change audit for store={}: {}", storeId, ex.getMessage());
        }
    }

    /** Actor id: prefer the explicit actorId, else fall back to the security context. */
    private static UUID resolveActorId(UUID actorId) {
        if (actorId != null) {
            return actorId;
        }
        return parseUuidOrNull(SecurityUtils.getCurrentUserIdOrNull());
    }

    /** Caller org id from the security context, or {@code null} when unauthenticated. */
    private static UUID resolveOrgId() {
        if (!SecurityUtils.isAuthenticated()) {
            return null;
        }
        return parseUuidOrNull(SecurityUtils.getCurrentOrgId());
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Computes which capabilities are disabled when transitioning from currentPhase to newPhase.
     * Disabled capabilities = capabilities in currentPhase that are NOT in newPhase.
     */
    Set<HostingAdjustmentType> computeDisabledCapabilities(HostingPhase currentPhase, HostingPhase newPhase) {
        Set<HostingAdjustmentType> current = currentPhase.capabilities();
        Set<HostingAdjustmentType> target = newPhase.capabilities();
        Set<HostingAdjustmentType> disabled = EnumSet.noneOf(HostingAdjustmentType.class);
        for (HostingAdjustmentType type : current) {
            if (!target.contains(type)) {
                disabled.add(type);
            }
        }
        return disabled;
    }

    /**
     * Performs cleanup for a phase downgrade: cancels awaiting_approval operations,
     * supersedes/cancels pending operations and closes their Outbox rows.
     * Leaves submitted operations untouched (platform cancel/reconciliation).
     */
    Map<String, Integer> performDowngradeCleanup(UUID storeId, Set<HostingAdjustmentType> disabledCapabilities) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("cancelled", 0);
        counts.put("superseded", 0);
        if (disabledCapabilities.isEmpty()) {
            return counts;
        }

        // Map HostingAdjustmentType to the field/changeType values used in operations
        Set<String> disabledChangeTypes = mapToChangeTypes(disabledCapabilities);

        // 1. Cancel awaiting_approval operations for disabled capabilities
        List<OperationEntity> awaitingOps = findOperationsByStoreAndStateAndChangeTypes(
                storeId, SyncState.AWAITING_APPROVAL, disabledChangeTypes);

        for (OperationEntity op : awaitingOps) {
            cancelOperation(op, "PHASE_DOWNGRADE");
        }
        log.info("Phase downgrade: cancelled {} awaiting_approval operations for store {}",
                awaitingOps.size(), storeId);

        // 2. Supersede/cancel pending operations and close their Outbox rows
        List<OperationEntity> pendingOps = findOperationsByStoreAndStateAndChangeTypes(
                storeId, SyncState.PENDING, disabledChangeTypes);

        for (OperationEntity op : pendingOps) {
            supersedeOperation(op, "PHASE_DOWNGRADE");
            closeOutboxRow(op.getId());
        }
        log.info("Phase downgrade: superseded {} pending operations and closed Outbox rows for store {}",
                pendingOps.size(), storeId);

        counts.put("cancelled", awaitingOps.size());
        counts.put("superseded", pendingOps.size());
        return counts;
    }

    /**
     * Finds operations for a store in a given state that match the disabled change types.
     */
    private List<OperationEntity> findOperationsByStoreAndStateAndChangeTypes(
            UUID storeId, SyncState state, Set<String> changeTypes) {
        if (changeTypes.isEmpty()) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<OperationEntity> query = new LambdaQueryWrapper<>();
        query.eq(OperationEntity::getStoreId, storeId)
                .eq(OperationEntity::getSyncState, state.name().toLowerCase())
                .eq(OperationEntity::getOperationSource, "ai_hosting")
                .in(OperationEntity::getField, changeTypes);

        return operationMapper.selectList(query);
    }

    /**
     * Cancels an operation via the CANCEL transition event and closes its approval request.
     */
    private void cancelOperation(OperationEntity op, String reason) {
        SyncState current = SyncState.valueOf(op.getSyncState().toUpperCase());
        SyncState target = stateMachine.transition(current, TransitionEvent.CANCEL);
        op.setSyncState(target.name().toLowerCase());
        op.setStatusReason(reason);
        operationMapper.updateById(op);

        // Close any linked approval request
        approvalService.closeApprovalRequest(op.getId(), reason);
    }

    /**
     * Supersedes an operation via the SUPERSEDE transition event.
     */
    private void supersedeOperation(OperationEntity op, String reason) {
        SyncState current = SyncState.valueOf(op.getSyncState().toUpperCase());
        SyncState target = stateMachine.transition(current, TransitionEvent.SUPERSEDE);
        op.setSyncState(target.name().toLowerCase());
        op.setStatusReason(reason);
        operationMapper.updateById(op);
    }

    /**
     * Closes (marks as done/failed) the Outbox row for an operation that has been
     * superseded or cancelled during downgrade. Uses string-based UpdateWrapper
     * (matching OutboxWorker pattern) to avoid MyBatis-Plus lambda cache requirements.
     */
    private void closeOutboxRow(UUID operationId) {
        UpdateWrapper<OperationOutboxEntity> update = new UpdateWrapper<>();
        update.set("status", "done")
                .set("last_error", "PHASE_DOWNGRADE: operation superseded")
                .eq("operation_id", operationId.toString())
                .in("status", List.of("pending", "claimed"));
        outboxMapper.update(null, update);
    }

    /**
     * Maps HostingAdjustmentType values to the field names used in operation records.
     */
    static Set<String> mapToChangeTypes(Set<HostingAdjustmentType> adjustmentTypes) {
        Set<String> changeTypes = new HashSet<>();
        for (HostingAdjustmentType type : adjustmentTypes) {
            switch (type) {
                case BID:
                    changeTypes.add("bid");
                    break;
                case BUDGET:
                    changeTypes.add("daily_budget");
                    break;
                case KEYWORD:
                    changeTypes.add("keyword");
                    break;
                case NEGATIVE:
                    changeTypes.add("negative_keyword");
                    break;
            }
        }
        return changeTypes;
    }

    /**
     * Persists the phase in the hosting_configs table for the store.
     */
    private void persistPhase(UUID storeId, HostingPhase newPhase, UUID actorId) {
        LambdaQueryWrapper<HostingConfigEntity> query = new LambdaQueryWrapper<>();
        query.eq(HostingConfigEntity::getStoreId, storeId)
                .eq(HostingConfigEntity::getScope, "store")
                .eq(HostingConfigEntity::getScopeId, storeId);

        HostingConfigEntity existing = hostingConfigMapper.selectOne(query);

        if (existing != null) {
            String config = mergePhaseIntoConfig(existing.getConfig(), newPhase);
            existing.setConfig(config);
            existing.setUpdatedBy(actorId);
            hostingConfigMapper.updateById(existing);
        } else {
            HostingConfigEntity entity = HostingConfigEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .scope("store")
                    .scopeId(storeId)
                    .config("{\"active_phase\":\"" + newPhase.name() + "\"}")
                    .createdBy(actorId)
                    .updatedBy(actorId)
                    .build();
            hostingConfigMapper.insert(entity);
        }
    }

    /**
     * Extracts the active_phase from the JSON config string.
     * Parses the simple JSON to avoid heavy dependencies.
     */
    static HostingPhase extractPhaseFromConfig(String config) {
        if (config == null || config.isBlank()) {
            return HostingPhase.DEFAULT;
        }
        // Simple extraction of "active_phase":"Vx" from JSON
        String marker = "\"active_phase\"";
        int idx = config.indexOf(marker);
        if (idx < 0) {
            return HostingPhase.DEFAULT;
        }
        int colonIdx = config.indexOf(':', idx + marker.length());
        if (colonIdx < 0) {
            return HostingPhase.DEFAULT;
        }
        int quoteStart = config.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) {
            return HostingPhase.DEFAULT;
        }
        int quoteEnd = config.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) {
            return HostingPhase.DEFAULT;
        }
        String value = config.substring(quoteStart + 1, quoteEnd);
        return HostingPhase.parse(value);
    }

    /**
     * Merges the new phase into the existing JSON config string.
     */
    private String mergePhaseIntoConfig(String existingConfig, HostingPhase newPhase) {
        if (existingConfig == null || existingConfig.isBlank()) {
            return "{\"active_phase\":\"" + newPhase.name() + "\"}";
        }
        // Replace existing active_phase or add it
        String marker = "\"active_phase\"";
        int idx = existingConfig.indexOf(marker);
        if (idx >= 0) {
            // Find and replace the value
            int colonIdx = existingConfig.indexOf(':', idx + marker.length());
            int quoteStart = existingConfig.indexOf('"', colonIdx + 1);
            int quoteEnd = existingConfig.indexOf('"', quoteStart + 1);
            return existingConfig.substring(0, quoteStart + 1) + newPhase.name()
                    + existingConfig.substring(quoteEnd);
        } else {
            // Add active_phase to the JSON object
            if (existingConfig.endsWith("}")) {
                String prefix = existingConfig.substring(0, existingConfig.length() - 1);
                if (prefix.trim().equals("{")) {
                    return "{\"active_phase\":\"" + newPhase.name() + "\"}";
                }
                return prefix + ",\"active_phase\":\"" + newPhase.name() + "\"}";
            }
            return "{\"active_phase\":\"" + newPhase.name() + "\"}";
        }
    }

    /**
     * Returns the ordinal position of a phase for comparison: V1=1, V2=2, V3=3.
     */
    static int phaseOrdinal(HostingPhase phase) {
        switch (phase) {
            case V1: return 1;
            case V2: return 2;
            case V3: return 3;
            default: return 0;
        }
    }

    /**
     * Value object representing a phase transition (from → to).
     */
    record PhaseTransition(HostingPhase from, HostingPhase to) {}
}
