package com.adpilot.modules.approval.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Maps the pre-existing {@code approval_policies} table (defined in V1).
 *
 * <p>Read by {@link com.adpilot.modules.approval.aspect.ApprovalAspect} to decide
 * whether a governed action must be gated: an action is gated when an enabled
 * policy exists for the caller's organization that matches the action's
 * {@code module}/{@code action_type} and requires approval (Req 12.1.1).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("approval_policies")
@Table(name = "approval_policies")
public class ApprovalPolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "org_id", nullable = false, columnDefinition = "char(36)")
    private UUID orgId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "module", nullable = false, length = 100)
    private String module;

    @Column(name = "action_type", nullable = false, length = 100)
    private String actionType;

    @Column(name = "risk_level", length = 20)
    @Builder.Default
    private String riskLevel = "low";

    /** none|direct_manager|role|department|multi_level|feishu */
    @Column(name = "approval_type", length = 50)
    @Builder.Default
    private String approvalType = "none";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "approver_role_ids", columnDefinition = "json")
    @Builder.Default
    private List<String> approverRoleIds = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "approver_user_ids", columnDefinition = "json")
    @Builder.Default
    private List<String> approverUserIds = List.of();

    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
