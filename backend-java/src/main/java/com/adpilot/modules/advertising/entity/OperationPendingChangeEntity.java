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
 * operation_pending_changes — the {@code open} row backs the Pending_Overlay for
 * an Operation in any Unsettled_State (Req 7). There is no second physical column
 * per field; the overlay is produced by left-joining each entity row to its
 * latest open pending-change for {@code (entity_type, entity_id, field)}.
 *
 * <p>Backed by the {@code operation_pending_changes} table (Req 7.1).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("operation_pending_changes")
@Table(name = "operation_pending_changes")
public class OperationPendingChangeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "operation_id", nullable = false, columnDefinition = "char(36)")
    private UUID operationId;

    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;

    @Column(name = "entity_id", nullable = false, columnDefinition = "char(36)")
    private UUID entityId;

    @Column(name = "field", nullable = false, length = 40)
    private String field;

    /** Amazon-confirmed value at creation, as a JSON string. */
    @Column(name = "before_value", columnDefinition = "json")
    private String beforeValue;

    /** Requested pending value, as a JSON string. */
    @Column(name = "after_value", columnDefinition = "json")
    private String afterValue;

    /** open | closed. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "open";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
