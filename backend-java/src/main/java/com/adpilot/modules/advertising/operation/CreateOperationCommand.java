package com.adpilot.modules.advertising.operation;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * The immutable input to {@link OperationService#createOperation(CreateOperationCommand)} — one
 * requested write, decoupled from the persistence-level {@link OperationRecordCommand}.
 *
 * <p>Where {@link OperationRecordCommand} is the audit-complete record the persistence building block
 * writes (it already carries the resolved {@link SyncState}/{@link ExecutionStatus}, the minted
 * {@code logicalOperationId}/{@code attemptId}, and the acting user), this command is what a
 * <em>caller</em> supplies: the intent of the change. {@code createOperation} is responsible for the
 * cross-cutting orchestration the persistence layer deliberately does not do — permission check
 * (Req 27), data-scope + ownership validation (Req 24, 25), in-flight conflict lock (Req 5.6),
 * idempotency coalescing (Req 5.3), optimistic-version check (Req 5.4/5.5), approval-threshold
 * evaluation (Req 4, 22.8), and write-capability resolution (Req 53) — before persisting the
 * Operation, pending-change, audit, and Outbox records in one transaction (Req 6.1).</p>
 *
 * <p>Callers therefore do NOT mint the logical/attempt key model or resolve the Sync_State; they
 * declare the change ({@code entityType}/{@code entityId}/{@code field}, the before/after values,
 * the {@link OperationSource}/{@link OperationScope}), the {@code logicalIdempotencyKey} that
 * coalesces repeated activations (Req 5.3), the {@code expectedVersion} the operator's view was
 * loaded with (Req 5.4), the {@code requiredPermission} to gate the action (Req 27), and whether the
 * change requires approval (Req 4.1, 22.8). The pipeline derives everything else.</p>
 *
 * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.6, 3.2, 3.7, 6.1, 6.2, 6.3, 9.4, 53.3.</p>
 */
@Value
@Builder
public class CreateOperationCommand {

    /** Owning store (scope); required. */
    UUID storeId;

    /** IMMUTABLE origin classification (Req 3.8, 12.6); required. */
    OperationSource operationSource;

    /** IMMUTABLE execution scope (Req 3.7/3.8); required. Governs routing and the state field used. */
    OperationScope operationScope;

    /** Target entity type (for example {@code campaign}, {@code keyword}); required. */
    String entityType;

    /** Target entity id; required. */
    UUID entityId;

    /** The single writable field changed; {@code null} for a multi-field operation. */
    String field;

    /**
     * Click-coalescing key per logical change (Req 5.3). When two activations carry the same
     * {@code logicalIdempotencyKey} within the Store, they are the SAME logical Operation and the
     * second is coalesced into the first rather than producing a second logical Operation.
     */
    String logicalIdempotencyKey;

    /** Amazon-confirmed value at creation (raw domain value, serialized to JSON). */
    Object beforeValue;

    /** Requested pending value (raw domain value, serialized to JSON). */
    Object afterValue;

    /** Undo eligibility (Req 8.4); defaults to {@code false} when not supplied. */
    Boolean reversible;

    /** Bulk affected-object count; defaults to 1 when not supplied. */
    Integer affectedCount;

    /**
     * The optimistic-lock version the operator's view was loaded with (Req 5.4). When supplied for a
     * versioned advertising entity, the pipeline guards the entity at this version and rejects the
     * Operation if the object changed since (Req 5.5). {@code null} skips the version guard (for
     * entity types without a version column).
     */
    Long expectedVersion;

    /**
     * The permission code required to perform this action (Req 27, for example
     * {@code advertising:manage}). When supplied, the pipeline verifies it through the
     * Permission_Service and rejects with 403 if the caller lacks it; {@code null} skips the check
     * (for internally-initiated, non-interactive Operations).
     */
    String requiredPermission;

    /**
     * Whether the change's absolute change ratio meets/exceeds the approval threshold (Req 4.1,
     * 22.8, 49.19), as evaluated by the caller against the Personality_Policy / Safety_Boundary.
     * When {@code true} and the Store is write-capable, the Operation is created in
     * {@code awaiting_approval} rather than {@code pending}. Defaults to {@code false}.
     */
    Boolean approvalRequired;

    /** Publish → terminal {@code local-only} draft link (Req 12.10); {@code null} otherwise. */
    UUID parentOperationId;

    /** Personality_Rule_Version in effect for an AI decision (Req 49.9); required for {@code ai_hosting}. */
    String personalityRuleVersion;

    /** Structured AI-decision payload (Req 49.9/22.10); required for {@code ai_hosting}. */
    AiDecision aiDecision;
}
