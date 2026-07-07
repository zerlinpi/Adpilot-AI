package com.adpilot.modules.advertising.operation;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * An immutable domain command describing one {@code Operation_Record} to persist, decoupled from the
 * MyBatis-Plus {@link OperationEntity} and from the {@code operations} table's storage details
 * (machine-value strings and JSON columns).
 *
 * <p>This is the input to {@link OperationRecordService#record(OperationRecordCommand)}. Callers
 * supply strongly-typed domain values — {@link OperationSource}/{@link OperationScope} enums,
 * a {@link SyncState} OR an {@link ExecutionStatus} (never both, see the scope/state separation
 * invariant of Requirements 3.7/3.8), and raw before/after/platform-result objects plus an optional
 * {@link AiDecision} — and the persistence layer owns the conversion to machine-value strings
 * (via {@link OperationMachineValues}) and to JSON (via {@link OperationJsonCodec}).</p>
 *
 * <p>The audit-completeness fields enumerated in Requirement 8.1 are all represented here:
 * {@code operationSource}, {@code operationScope}, the idempotency/attempt key model
 * ({@code logicalOperationId}, {@code logicalIdempotencyKey}, {@code attemptId},
 * {@code submissionIdempotencyKey}, {@code attemptNumber}), {@code parentOperationId},
 * {@code beforeValue}/{@code afterValue}, {@code reversible}, {@code affectedCount},
 * {@code actingUserId}, the resulting {@link SyncState} or {@link ExecutionStatus}, the
 * {@code platformResult}, the {@code statusReason}, and the AI-decision fields
 * ({@code personalityRuleVersion}, {@code aiDecision}) of Requirements 49.9/22.10. The created/updated
 * timestamps are assigned by the database/ORM on insert and are therefore not part of the command.</p>
 *
 * <p>Validates: Requirements 8.1, 3.8, 22.10, 49.9, 49.15.</p>
 */
@Value
@Builder
public class OperationRecordCommand {

    /** Owning store (scope); required. */
    UUID storeId;

    /** IMMUTABLE origin classification (Req 3.8, 12.6); required. */
    OperationSource operationSource;

    /** IMMUTABLE execution scope (Req 3.7/3.8); required. Governs the state-field separation invariant. */
    OperationScope operationScope;

    /** Target entity type (for example {@code campaign}, {@code keyword}); required. */
    String entityType;

    /** Target entity id; required. */
    UUID entityId;

    /** The single writable field changed; {@code null} for a multi-field operation. */
    String field;

    /** Stable across all attempts of the same logical change (Req 5/7.8); required. */
    UUID logicalOperationId;

    /** Click-coalescing key per logical change (Req 5.3); required. */
    String logicalIdempotencyKey;

    /** Per-attempt id; required. */
    UUID attemptId;

    /** Per-platform-submission key, rotated on retry (Req 5.2/5.7); {@code null} until submitted. */
    String submissionIdempotencyKey;

    /** 1-based attempt number; defaults to 1 when not supplied. */
    Integer attemptNumber;

    /** Publish → terminal {@code local-only} draft link (Req 12.10); {@code null} otherwise. */
    UUID parentOperationId;

    /** Amazon-confirmed value at creation (raw domain value, serialized to JSON). */
    Object beforeValue;

    /** Requested pending value (raw domain value, serialized to JSON). */
    Object afterValue;

    /** Undo eligibility (Req 8.4); defaults to {@code false} when not supplied. */
    Boolean reversible;

    /** Bulk affected-object count; defaults to 1 when not supplied. */
    Integer affectedCount;

    /** Acting user from the security context (Req 24.4); required. */
    UUID actingUserId;

    /**
     * The resulting platform Sync_State for a {@code platform_mutation} Operation. MUST be set for a
     * {@code platform_mutation} and MUST be {@code null} for a {@code local_configuration} (Req 3.7).
     */
    SyncState syncState;

    /**
     * The resulting executionStatus for a {@code local_configuration} Operation. MUST be set for a
     * {@code local_configuration} and MUST be {@code null} for a {@code platform_mutation} (Req 3.8).
     */
    ExecutionStatus executionStatus;

    /** Connector correlation reference (Req 55.7); {@code null} until submitted. */
    String platformReference;

    /** Platform result payload where Operation_Write_Back provided one (raw value, serialized to JSON). */
    Object platformResult;

    /**
     * Human-readable reason for the current state. Required (non-blank) when the resulting state is
     * {@code failed}, {@code cancelled}, {@code expired}, {@code cancel_requested}, or
     * {@code reconciliation_required} (Req 8.1).
     */
    String statusReason;

    /** Personality_Rule_Version in effect for an AI decision (Req 49.9); required for {@code ai_hosting}. */
    String personalityRuleVersion;

    /** Structured AI-decision payload (Req 49.9/22.10); required for {@code ai_hosting}. */
    AiDecision aiDecision;
}
