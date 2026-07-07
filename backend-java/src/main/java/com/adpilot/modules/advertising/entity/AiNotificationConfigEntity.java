package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Per-store AI Notification configuration (Req 23.5) controlling which
 * core-ops items are raised. One row per store (enforced by
 * {@code uk_ai_notif_config_store}).
 *
 * <p>Backed by the additive {@code ai_notification_config} table created in
 * {@code V3__ai_workitems.sql}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("ai_notification_config")
@Table(name = "ai_notification_config")
public class AiNotificationConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    /** Configuration blob (which core-ops items are raised) as a JSON string. */
    @Column(name = "config_json", nullable = false, columnDefinition = "json")
    private String configJson;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
