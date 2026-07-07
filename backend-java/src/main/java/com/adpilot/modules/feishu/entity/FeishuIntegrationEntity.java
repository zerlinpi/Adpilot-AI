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
@TableName("feishu_integrations")
@Table(name = "feishu_integrations")
public class FeishuIntegrationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "org_id", nullable = false, columnDefinition = "char(36)")
    private UUID orgId;

    @Column(name = "store_id", columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "owner_account_id", columnDefinition = "char(36)")
    private UUID ownerAccountId;

    @Column(name = "provider", length = 50)
    @Builder.Default
    private String provider = "feishu";

    @Column(name = "connection_type", length = 20)
    @Builder.Default
    private String connectionType = "app";

    @Column(name = "app_id", length = 255)
    private String appId;

    @Column(name = "app_secret_encrypted", columnDefinition = "TEXT")
    private String appSecretEncrypted;

    @Column(name = "webhook_url_encrypted", columnDefinition = "TEXT")
    private String webhookUrlEncrypted;

    @Column(name = "webhook_secret_encrypted", columnDefinition = "TEXT")
    private String webhookSecretEncrypted;

    @Column(name = "verification_token_encrypted", length = 255)
    private String verificationTokenEncrypted;

    @Column(name = "encrypt_key_encrypted", length = 255)
    private String encryptKeyEncrypted;

    @Column(name = "bot_open_id", length = 255)
    private String botOpenId;

    @Column(name = "default_chat_id", length = 255)
    private String defaultChatId;

    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = "active";

    @Column(name = "last_connected_at")
    private LocalDateTime lastConnectedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
