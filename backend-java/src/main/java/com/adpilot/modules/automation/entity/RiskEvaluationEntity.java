package com.adpilot.modules.automation.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("risk_evaluations")
@Table(name = "risk_evaluations")
public class RiskEvaluationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "action_type", nullable = false, length = 100)
    private String actionType;

    @Column(name = "entity_type", length = 100)
    private String entityType;

    @Column(name = "entity_id", columnDefinition = "char(36)")
    private UUID entityId;

    @Column(name = "risk_level", length = 20)
    @Builder.Default
    private String riskLevel = "low";

    @Column(columnDefinition = "json")
    private String reasons;

    @Builder.Default
    private Boolean blocked = false;

    @Column(name = "requires_approval")
    @Builder.Default
    private Boolean requiresApproval = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
