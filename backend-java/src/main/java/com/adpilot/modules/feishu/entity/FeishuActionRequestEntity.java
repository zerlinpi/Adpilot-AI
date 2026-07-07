package com.adpilot.modules.feishu.entity;

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

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("feishu_action_requests")
@Table(name = "feishu_action_requests")
public class FeishuActionRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "feishu_integration_id", nullable = false, columnDefinition = "char(36)")
    private UUID feishuIntegrationId;

    @Column(name = "feishu_user_id", length = 255)
    private String feishuUserId;

    @Column(name = "chat_id", length = 255)
    private String chatId;

    @Column(name = "message_id", length = 255)
    private String messageId;

    @Column(name = "action_type", nullable = false, length = 100)
    private String actionType;

    @Column(name = "action_data", columnDefinition = "json")
    @Builder.Default
    private String actionData = "{}";

    @Column(name = "related_entity_type", length = 100)
    private String relatedEntityType;

    @Column(name = "related_entity_id", columnDefinition = "char(36)")
    private UUID relatedEntityId;

    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = "pending";

    @Column(name = "approval_request_id", columnDefinition = "char(36)")
    private UUID approvalRequestId;

    @Column(name = "result", columnDefinition = "json")
    @Builder.Default
    private String result = "{}";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
