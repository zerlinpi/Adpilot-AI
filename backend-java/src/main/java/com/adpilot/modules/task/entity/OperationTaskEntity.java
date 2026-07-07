package com.adpilot.modules.task.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("operation_tasks")
@Table(name = "operation_tasks")
public class OperationTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "task_type", nullable = false, length = 50)
    private String taskType;

    @Column(name = "source_type", length = 50)
    @Builder.Default
    private String sourceType = "manual";

    @Column(name = "related_entity_type", length = 50)
    private String relatedEntityType;

    @Column(name = "related_entity_id", columnDefinition = "char(36)")
    private UUID relatedEntityId;

    @Column(name = "priority", length = 20)
    @Builder.Default
    private String priority = "medium";

    @Column(name = "risk_level", length = 20)
    @Builder.Default
    private String riskLevel = "low";

    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = "open";

    @Column(name = "assigned_to_user_id", columnDefinition = "char(36)")
    private UUID assignedToUserId;

    @Column(name = "due_date")
    private LocalDateTime dueDate;

    @Column(name = "expected_impact", columnDefinition = "TEXT")
    private String expectedImpact;

    @Column(name = "suggested_action", columnDefinition = "TEXT")
    private String suggestedAction;

    @Column(name = "approval_required")
    @Builder.Default
    private Boolean approvalRequired = false;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_by", columnDefinition = "char(36)")
    private UUID updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
