package com.adpilot.modules.advertising.operation;

import com.adpilot.modules.advertising.entity.OperationEntity;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Builds a complete, audit-consistent {@link OperationEntity} from an {@link OperationRecordCommand},
 * converting domain enums to canonical machine-value strings (via {@link OperationMachineValues}) and
 * raw before/after/platform-result/ai-decision values to JSON (via {@link OperationJsonCodec}).
 *
 * <p>This is a <strong>pure</strong> mapping component: it performs no persistence and has no
 * external side effects, so it is unit- and property-testable in isolation. It is the single place
 * that enforces, at construction time, the two correctness invariants of the Operation_Record:</p>
 *
 * <ol>
 *   <li><strong>Audit-field completeness (Req 8.1).</strong> Every required audit field
 *       (store, source, scope, entity, the logical/attempt key model, acting user, the resulting
 *       state, and — for the terminal/failure states — the {@code statusReason}) must be present, or
 *       an {@link OperationRecordValidationException} is thrown. The AI-decision fields
 *       ({@code personalityRuleVersion}, {@code aiDecision}) are required for an {@code ai_hosting}
 *       Operation (Req 49.9/22.10).</li>
 *   <li><strong>operationScope ↔ state-field separation (Req 3.7/3.8).</strong> A
 *       {@code platform_mutation} Operation MUST carry a {@link SyncState} and MUST NOT carry an
 *       {@link ExecutionStatus}; a {@code local_configuration} Operation MUST carry an
 *       {@link ExecutionStatus} and MUST NOT carry a {@link SyncState}. A violation (for example a
 *       platform Sync_State assigned to a {@code local_configuration}) is rejected rather than
 *       silently coerced.</li>
 * </ol>
 *
 * <p>Validates: Requirements 8.1, 3.7, 3.8, 22.10, 49.9, 49.15.</p>
 */
@Component
public class OperationRecordAssembler {

    /**
     * The Sync_States for which a human-readable {@code statusReason} MUST be recorded (Req 8.1):
     * {@code failed}, {@code cancelled}, {@code expired}, {@code cancel_requested}, and
     * {@code reconciliation_required} — not only {@code failed}.
     */
    static final Set<SyncState> SYNC_STATES_REQUIRING_REASON = EnumSet.of(
            SyncState.FAILED,
            SyncState.CANCELLED,
            SyncState.EXPIRED,
            SyncState.CANCEL_REQUESTED,
            SyncState.RECONCILIATION_REQUIRED);

    /**
     * The terminal {@code local_configuration} execution statuses for which a {@code statusReason}
     * MUST be recorded — the failure/cancellation outcomes, mirroring the Sync_State rule (Req 8.1).
     */
    static final Set<ExecutionStatus> EXEC_STATUSES_REQUIRING_REASON = EnumSet.of(
            ExecutionStatus.FAILED,
            ExecutionStatus.CANCELLED);

    private final OperationJsonCodec jsonCodec;

    public OperationRecordAssembler(OperationJsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    /**
     * Validate and map the command to a persistence-ready entity.
     *
     * @param cmd the domain command; must not be {@code null}
     * @return a fully populated {@link OperationEntity} (id/timestamps assigned by the DB on insert)
     * @throws OperationRecordValidationException if a required audit field is missing or the
     *         scope/state separation invariant is violated
     */
    public OperationEntity toEntity(OperationRecordCommand cmd) {
        if (cmd == null) {
            throw new OperationRecordValidationException("OperationRecordCommand must not be null");
        }

        validateRequiredAuditFields(cmd);
        validateScopeStateSeparation(cmd);
        validateAiDecisionCompleteness(cmd);

        int attemptNumber = cmd.getAttemptNumber() != null ? cmd.getAttemptNumber() : 1;
        if (attemptNumber < 1) {
            throw new OperationRecordValidationException("attemptNumber must be >= 1 but was " + attemptNumber);
        }
        int affectedCount = cmd.getAffectedCount() != null ? cmd.getAffectedCount() : 1;
        if (affectedCount < 1) {
            throw new OperationRecordValidationException("affectedCount must be >= 1 but was " + affectedCount);
        }
        boolean reversible = Boolean.TRUE.equals(cmd.getReversible());

        return OperationEntity.builder()
                .storeId(cmd.getStoreId())
                .operationSource(OperationMachineValues.toValue(cmd.getOperationSource()))
                .operationScope(OperationMachineValues.toValue(cmd.getOperationScope()))
                .entityType(cmd.getEntityType())
                .entityId(cmd.getEntityId())
                .field(cmd.getField())
                .logicalOperationId(cmd.getLogicalOperationId())
                .logicalIdempotencyKey(cmd.getLogicalIdempotencyKey())
                .attemptId(cmd.getAttemptId())
                .submissionIdempotencyKey(cmd.getSubmissionIdempotencyKey())
                .attemptNumber(attemptNumber)
                .parentOperationId(cmd.getParentOperationId())
                .beforeValue(jsonCodec.toJson(cmd.getBeforeValue()))
                .afterValue(jsonCodec.toJson(cmd.getAfterValue()))
                .reversible(reversible)
                .affectedCount(affectedCount)
                .actingUserId(cmd.getActingUserId())
                // Separation invariant: exactly one of sync_state / execution_status is populated.
                .syncState(OperationMachineValues.toValue(cmd.getSyncState()))
                .executionStatus(OperationMachineValues.toValue(cmd.getExecutionStatus()))
                .platformReference(cmd.getPlatformReference())
                .platformResult(jsonCodec.toJson(cmd.getPlatformResult()))
                .statusReason(cmd.getStatusReason())
                .personalityRuleVersion(cmd.getPersonalityRuleVersion())
                .aiDecision(jsonCodec.toJson(cmd.getAiDecision()))
                .build();
    }

    private void validateRequiredAuditFields(OperationRecordCommand cmd) {
        requireField(cmd.getStoreId(), "storeId");
        requireField(cmd.getOperationSource(), "operationSource");
        requireField(cmd.getOperationScope(), "operationScope");
        requireField(cmd.getEntityType(), "entityType");
        requireField(cmd.getEntityId(), "entityId");
        requireField(cmd.getLogicalOperationId(), "logicalOperationId");
        requireField(cmd.getLogicalIdempotencyKey(), "logicalIdempotencyKey");
        requireField(cmd.getAttemptId(), "attemptId");
        requireField(cmd.getActingUserId(), "actingUserId");
    }

    /**
     * Enforce the operationScope ↔ state-field separation invariant of Requirements 3.7 and 3.8, and
     * the statusReason coverage of Requirement 8.1.
     */
    private void validateScopeStateSeparation(OperationRecordCommand cmd) {
        OperationScope scope = cmd.getOperationScope();
        SyncState syncState = cmd.getSyncState();
        ExecutionStatus executionStatus = cmd.getExecutionStatus();

        if (scope == OperationScope.PLATFORM_MUTATION) {
            if (syncState == null) {
                throw new OperationRecordValidationException(
                        "platform_mutation Operation must carry a syncState (Req 3.1)");
            }
            if (executionStatus != null) {
                throw new OperationRecordValidationException(
                        "platform_mutation Operation must not carry an executionStatus (Req 3.7/3.8); got "
                                + executionStatus);
            }
            if (SYNC_STATES_REQUIRING_REASON.contains(syncState) && isBlank(cmd.getStatusReason())) {
                throw new OperationRecordValidationException(
                        "statusReason is required for Sync_State " + OperationMachineValues.toValue(syncState)
                                + " (Req 8.1)");
            }
        } else if (scope == OperationScope.LOCAL_CONFIGURATION) {
            if (executionStatus == null) {
                throw new OperationRecordValidationException(
                        "local_configuration Operation must carry an executionStatus (Req 3.8)");
            }
            if (syncState != null) {
                throw new OperationRecordValidationException(
                        "local_configuration Operation must not carry a platform syncState (Req 3.7/3.8); got "
                                + OperationMachineValues.toValue(syncState));
            }
            if (EXEC_STATUSES_REQUIRING_REASON.contains(executionStatus) && isBlank(cmd.getStatusReason())) {
                throw new OperationRecordValidationException(
                        "statusReason is required for executionStatus "
                                + OperationMachineValues.toValue(executionStatus) + " (Req 8.1)");
            }
        }
    }

    /**
     * An {@code ai_hosting} Operation records an AI decision, so its decision audit fields are
     * mandatory: the Personality_Rule_Version and the structured {@link AiDecision} (Req 49.9, 22.10).
     */
    private void validateAiDecisionCompleteness(OperationRecordCommand cmd) {
        if (cmd.getOperationSource() == OperationSource.AI_HOSTING) {
            if (isBlank(cmd.getPersonalityRuleVersion())) {
                throw new OperationRecordValidationException(
                        "ai_hosting Operation must record personalityRuleVersion (Req 49.9)");
            }
            if (cmd.getAiDecision() == null) {
                throw new OperationRecordValidationException(
                        "ai_hosting Operation must record an aiDecision (Req 49.9)");
            }
        }
    }

    private static void requireField(Object value, String name) {
        if (value == null) {
            throw new OperationRecordValidationException("Required Operation_Record field '" + name + "' is missing");
        }
        if (value instanceof String s && s.isBlank()) {
            throw new OperationRecordValidationException("Required Operation_Record field '" + name + "' is blank");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
