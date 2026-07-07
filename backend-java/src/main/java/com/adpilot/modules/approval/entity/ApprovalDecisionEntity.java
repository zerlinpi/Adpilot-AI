package com.adpilot.modules.approval.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One approver's decision at a given approval level for an {@link ApprovalRequestEntity}.
 *
 * <p>Persisted by the ApprovalService (task 24.2) as levels are approved or
 * rejected (Req 12.1.3, 12.1.4, 12.1.5).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("approval_decisions")
@Table(name = "approval_decisions")
public class ApprovalDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "request_id", nullable = false, columnDefinition = "char(36)")
    private UUID requestId;

    @Column(name = "level", nullable = false)
    private Integer level;

    @Column(name = "approver_id", nullable = false, columnDefinition = "char(36)")
    private UUID approverId;

    /** approved|rejected */
    @Column(name = "decision", nullable = false, length = 20)
    private String decision;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
