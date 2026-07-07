package com.adpilot.modules.approval.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Maps the {@code approval_requests} table (defined in V1__init_schema.sql and
 * extended in V10__approval_workflow.sql).
 *
 * <p>Represents a request for human approval of a governed action. Two creation
 * paths share this entity:
 * <ul>
 *   <li>the store-centric path used by {@code ApprovalService} /
 *       {@code ApprovalController} (store/requester/title/request_type), and</li>
 *   <li>the policy-driven path used by {@code ApprovalAspect}
 *       (policy/module/action_type/amount/initiated_by/current_level), Req 12.1.1.</li>
 * </ul>
 * Routing, approve/reject, level sequencing, and watching are handled by the
 * approval services.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("approval_requests")
@Table(name = "approval_requests")
public class ApprovalRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "requester_id", nullable = false, columnDefinition = "char(36)")
    private UUID requesterId;

    @Column(name = "approver_id", columnDefinition = "char(36)")
    private UUID approverId;

    @Column(name = "request_type", nullable = false, length = 100)
    private String requestType;

    @Column(name = "related_entity_type", length = 100)
    private String relatedEntityType;

    @Column(name = "related_entity_id", columnDefinition = "char(36)")
    private UUID relatedEntityId;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "payload", columnDefinition = "json")
    private String payload;

    @Column(name = "risk_level", length = 20)
    @Builder.Default
    private String riskLevel = "low";

    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = "pending";

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    // --- Policy-driven governed-action fields (V10), populated by ApprovalAspect ---

    /** The approval policy that gated the action; null for store-centric requests. */
    @Column(name = "policy_id", columnDefinition = "char(36)")
    private UUID policyId;

    /** Governed module (matches a policy's module), e.g. {@code "advertising"}. */
    @Column(name = "module", length = 100)
    private String module;

    /** Governed action type (matches a policy's action_type), e.g. {@code "bid_change"}. */
    @Column(name = "action_type", length = 100)
    private String actionType;

    /** Optional monetary amount associated with the action (threshold evaluation). */
    @Column(name = "amount", precision = 18, scale = 4)
    private BigDecimal amount;

    /** The user who initiated the governed action that was gated. */
    @Column(name = "initiated_by", columnDefinition = "char(36)")
    private UUID initiatedBy;

    /** Current approval level being awaited (1-based). */
    @Column(name = "current_level")
    @Builder.Default
    private Integer currentLevel = 1;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
