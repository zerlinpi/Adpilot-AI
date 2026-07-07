package com.adpilot.modules.feishu.entity;

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
@TableName("feishu_message_logs")
@Table(name = "feishu_message_logs")
public class FeishuMessageLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "feishu_integration_id", nullable = false, columnDefinition = "char(36)")
    private UUID feishuIntegrationId;

    @Column(name = "chat_id", length = 255)
    private String chatId;

    @Column(name = "message_id", length = 255)
    private String messageId;

    @Column(name = "message_type", length = 50)
    @Builder.Default
    private String messageType = "interactive";

    @Column(name = "direction", nullable = false, length = 20)
    private String direction;

    @Column(name = "content", columnDefinition = "json")
    @Builder.Default
    private String content = "{}";

    @Column(name = "related_entity_type", length = 100)
    private String relatedEntityType;

    @Column(name = "related_entity_id", columnDefinition = "char(36)")
    private UUID relatedEntityId;

    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = "sent";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
