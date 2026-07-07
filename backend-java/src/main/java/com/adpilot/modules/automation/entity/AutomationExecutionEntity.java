package com.adpilot.modules.automation.entity;

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
@TableName("automation_executions")
@Table(name = "automation_executions")
public class AutomationExecutionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(length = 50)
    @Builder.Default
    private String source = "app";

    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    @Column(name = "entity_id", columnDefinition = "char(36)")
    private UUID entityId;

    @Column(name = "action_type", nullable = false, length = 100)
    private String actionType;

    @Column(name = "before_snapshot", columnDefinition = "json")
    private String beforeSnapshot;

    @Column(name = "after_snapshot", columnDefinition = "json")
    private String afterSnapshot;

    @Column(name = "risk_level", length = 20)
    @Builder.Default
    private String riskLevel = "low";

    @Column(name = "approval_request_id", columnDefinition = "char(36)")
    private UUID approvalRequestId;

    @Column(name = "task_id", columnDefinition = "char(36)")
    private UUID taskId;

    @Column(length = 30)
    @Builder.Default
    private String status = "pending";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
