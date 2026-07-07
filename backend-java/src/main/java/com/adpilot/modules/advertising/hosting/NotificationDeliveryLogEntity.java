package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity mapping to the {@code notification_delivery_log} table (Req 9.6, 9.7).
 *
 * <p>Records every notification delivery attempt, including retries and failures,
 * providing an audit trail for Feishu notification delivery.</p>
 *
 * <p>Validates: Requirements 9.7, 30.4</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("notification_delivery_log")
@Table(name = "notification_delivery_log")
public class NotificationDeliveryLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", columnDefinition = "char(36)")
    private UUID storeId;

    /** Notification type: approval_needed, effective_confirmed, failed, emergency, data_gap */
    @Column(name = "notification_type", nullable = false, length = 40)
    private String notificationType;

    /** Recipient identifier (e.g., Feishu chat id). */
    @Column(name = "recipient", length = 255)
    private String recipient;

    /** Delivery status: delivered, failed, queued. */
    @Column(name = "status", nullable = false, length = 12)
    private String status;

    /** Number of delivery attempts made. */
    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 1;

    /** Last error message if delivery failed. */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    /** JSON payload of the notification content. */
    @Column(name = "payload", columnDefinition = "json")
    private String payload;

    /** Row creation timestamp. */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** Last update timestamp. */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
