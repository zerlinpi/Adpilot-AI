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
 * operation_outbox — written in the SAME transaction as the Operation; never
 * written for {@code local_configuration}. Claimed by the OutboxWorker with
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} / version-claim and submitted to the
 * platform outside any DB transaction (Req 6).
 *
 * <p>Backed by the {@code operation_outbox} table (Req 6.1).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("operation_outbox")
@Table(name = "operation_outbox")
public class OperationOutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "operation_id", nullable = false, columnDefinition = "char(36)")
    private UUID operationId;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "platform", nullable = false, length = 40)
    private String platform;

    /** Connector submission payload, as a JSON string. */
    @Column(name = "payload", columnDefinition = "json")
    private String payload;

    @Column(name = "submission_idempotency_key", length = 120)
    private String submissionIdempotencyKey;

    /** pending | claimed | submitted | done | failed. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "pending";

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    /** Earliest time the OutboxWorker should re-claim this row for retry. */
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    /** Last error message from a failed submission attempt. */
    @Column(name = "last_error", length = 500)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
