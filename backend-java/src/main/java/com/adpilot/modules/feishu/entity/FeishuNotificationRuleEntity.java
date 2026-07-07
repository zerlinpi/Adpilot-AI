package com.adpilot.modules.feishu.entity;

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
@TableName("feishu_notification_rules")
@Table(name = "feishu_notification_rules")
public class FeishuNotificationRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "feishu_integration_id", nullable = false, columnDefinition = "char(36)")
    private UUID feishuIntegrationId;

    @Column(name = "store_id", columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "chat_id", length = 255)
    private String chatId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "condition_json", columnDefinition = "TEXT")
    private String conditionJson;

    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = "active";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
