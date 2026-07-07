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
@TableName("feishu_user_bindings")
@Table(name = "feishu_user_bindings")
public class FeishuUserBindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "feishu_integration_id", nullable = false, columnDefinition = "char(36)")
    private UUID feishuIntegrationId;

    @Column(name = "user_id", nullable = false, columnDefinition = "char(36)")
    private UUID userId;

    @Column(name = "feishu_user_id", nullable = false, length = 255)
    private String feishuUserId;

    @Column(name = "feishu_open_id", length = 255)
    private String feishuOpenId;

    @Column(name = "feishu_name", length = 255)
    private String feishuName;

    @Column(name = "feishu_avatar_url", length = 1000)
    private String feishuAvatarUrl;

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
