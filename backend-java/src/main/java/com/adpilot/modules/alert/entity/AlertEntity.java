package com.adpilot.modules.alert.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Unified alert-center row (Req 10.1). One OPEN alert exists per
 * (store, alert_type, subject_id); resolved rows are retained as history.
 *
 * <p>The {@code open_dedup_key} column is computed by the database and is not
 * written by the application, so it is intentionally not mapped here.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("alerts")
@Table(name = "alerts")
public class AlertEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    /** stockout | acos | buybox | negative_review */
    @Column(name = "alert_type", nullable = false, length = 50)
    private String alertType;

    /** Product or campaign identifier the alert is about. */
    @Column(name = "subject_id", length = 255)
    private String subjectId;

    @Column(name = "severity", length = 20)
    @Builder.Default
    private String severity = "warning";

    /** open | resolved */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "open";

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "feishu_pushed")
    @Builder.Default
    private Boolean feishuPushed = false;

    @Column(name = "feishu_error", columnDefinition = "TEXT")
    private String feishuError;

    @Column(name = "first_seen_at")
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;
}
