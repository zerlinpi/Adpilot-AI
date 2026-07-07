package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Operation_Record — the authoritative record of every write Operation (Req 8).
 *
 * <p>Separates {@code execution_status} (the terminal status of a
 * {@code local_configuration} Operation) from {@code sync_state} (the platform
 * lifecycle of a {@code platform_mutation} Operation). {@code operation_source}
 * and {@code operation_scope} are IMMUTABLE audit facts recorded at creation and
 * are never rewritten (Req 12.6, enforced in the service layer).</p>
 *
 * <p>The string-valued lifecycle/classification columns ({@code operation_source},
 * {@code operation_scope}, {@code sync_state}, {@code execution_status}) hold the
 * canonical lowercase machine values defined by the corresponding enums under
 * {@code com.adpilot.modules.advertising.operation}
 * ({@link com.adpilot.modules.advertising.operation.OperationSource},
 * {@link com.adpilot.modules.advertising.operation.OperationScope},
 * {@link com.adpilot.modules.advertising.operation.SyncState},
 * {@link com.adpilot.modules.advertising.operation.ExecutionStatus}). They are
 * stored as {@code String} to match the project convention for status columns;
 * enum conversion is owned by the service / state-machine layer.</p>
 *
 * <p>Backed by the {@code operations} table (Req 8.1).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("operations")
@Table(name = "operations")
public class OperationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    /** IMMUTABLE: manual | recommendation | one_click_optimize | ai_hosting | creation. */
    @Column(name = "operation_source", nullable = false, length = 20)
    private String operationSource;

    /** IMMUTABLE: platform_mutation | local_configuration. */
    @Column(name = "operation_scope", nullable = false, length = 20)
    private String operationScope;

    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;

    @Column(name = "entity_id", nullable = false, columnDefinition = "char(36)")
    private UUID entityId;

    /** Writable field changed; {@code null} for multi-field operations. */
    @Column(name = "field", length = 40)
    private String field;

    /** Stable across attempts of the same logical change (Req 5, 7.8). */
    @Column(name = "logical_operation_id", nullable = false, columnDefinition = "char(36)")
    private UUID logicalOperationId;

    /** Click-coalescing key, unique-ish per logical change (Req 5.3). */
    @Column(name = "logical_idempotency_key", nullable = false, length = 120)
    private String logicalIdempotencyKey;

    @Column(name = "attempt_id", nullable = false, columnDefinition = "char(36)")
    private UUID attemptId;

    /** Per-platform-submission key; rotated on retry (Req 5.2, 5.7). */
    @Column(name = "submission_idempotency_key", length = 120)
    private String submissionIdempotencyKey;

    @Column(name = "attempt_number", nullable = false)
    @Builder.Default
    private Integer attemptNumber = 1;

    /** publish -> local-only draft link (Req 12.10); {@code null} otherwise. */
    @Column(name = "parent_operation_id", columnDefinition = "char(36)")
    private UUID parentOperationId;

    /** Amazon-confirmed value at creation, as a JSON string. */
    @Column(name = "before_value", columnDefinition = "json")
    private String beforeValue;

    /** Requested pending value, as a JSON string. */
    @Column(name = "after_value", columnDefinition = "json")
    private String afterValue;

    /** Undo eligibility (Req 8.4). */
    @Column(name = "reversible", nullable = false)
    @Builder.Default
    private Boolean reversible = false;

    /** Bulk affected object count. */
    @Column(name = "affected_count", nullable = false)
    @Builder.Default
    private Integer affectedCount = 1;

    /** Acting user from the security context (Req 24.4). */
    @Column(name = "acting_user_id", columnDefinition = "char(36)")
    private UUID actingUserId;

    /** platform_mutation lifecycle; {@code null} for local_configuration. */
    @Column(name = "sync_state", length = 30)
    private String syncState;

    /** local_configuration only: applied | failed | cancelled. */
    @Column(name = "execution_status", length = 20)
    private String executionStatus;

    /** Connector correlation reference (Req 55.7). */
    @Column(name = "platform_reference", length = 120)
    private String platformReference;

    /** Platform result payload where provided, as a JSON string. */
    @Column(name = "platform_result", columnDefinition = "json")
    private String platformResult;

    /** Reason for failed / cancelled / expired / cancel_requested / reconciliation_required. */
    @Column(name = "status_reason", length = 500)
    private String statusReason;

    /** AI decision rule version (Req 49.9). */
    @Column(name = "personality_rule_version", length = 40)
    private String personalityRuleVersion;

    /** Trigger metric, resolved personality, allowed/actual magnitude, reason, predicted impact (JSON). */
    @Column(name = "ai_decision", columnDefinition = "json")
    private String aiDecision;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
