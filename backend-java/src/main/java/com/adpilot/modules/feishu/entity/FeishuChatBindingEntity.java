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
@TableName("feishu_chat_bindings")
@Table(name = "feishu_chat_bindings")
public class FeishuChatBindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "feishu_integration_id", nullable = false, columnDefinition = "char(36)")
    private UUID feishuIntegrationId;

    @Column(name = "store_id", columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "chat_id", nullable = false, length = 255)
    private String chatId;

    @Column(name = "chat_type", length = 50)
    @Builder.Default
    private String chatType = "group";

    @Column(name = "chat_name", length = 500)
    private String chatName;

    @Column(name = "notify_on_approval")
    @Builder.Default
    private Boolean notifyOnApproval = true;

    @Column(name = "notify_on_execution")
    @Builder.Default
    private Boolean notifyOnExecution = true;

    @Column(name = "notify_on_rollback")
    @Builder.Default
    private Boolean notifyOnRollback = true;

    @Column(name = "notify_on_risk_alert")
    @Builder.Default
    private Boolean notifyOnRiskAlert = true;

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
