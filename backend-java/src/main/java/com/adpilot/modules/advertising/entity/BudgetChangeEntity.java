package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("budget_changes")
@Table(name = "budget_changes")
public class BudgetChangeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "campaign_id", nullable = false, columnDefinition = "char(36)")
    private UUID campaignId;

    @Column(name = "old_budget", precision = 10, scale = 2)
    private BigDecimal oldBudget;

    @Column(name = "new_budget", precision = 10, scale = 2)
    private BigDecimal newBudget;

    @Column(name = "change_reason", length = 100)
    private String changeReason;

    @Column(name = "changed_by", columnDefinition = "char(36)")
    private UUID changedBy;

    @Column(name = "is_automated")
    @Builder.Default
    private Boolean isAutomated = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
