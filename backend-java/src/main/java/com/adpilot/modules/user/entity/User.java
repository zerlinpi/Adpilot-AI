package com.adpilot.modules.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
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
@TableName("users")
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "org_id", nullable = false, columnDefinition = "char(36)")
    private UUID orgId;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    @NotBlank(message = "Email is required")
    private String email;

    @Column(name = "name", nullable = false, length = 255)
    @NotBlank(message = "Name is required")
    private String name;

    @Column(name = "password_hash", length = 500)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String passwordHash;

    @Column(name = "avatar_url", columnDefinition = "TEXT")
    private String avatarUrl;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "active";

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    /** Consecutive failed login attempts since the last success/lockout reset (per account). */
    @Column(name = "failed_login_count", nullable = false)
    @Builder.Default
    private Integer failedLoginCount = 0;

    /** Account is locked until this instant; NULL means not locked. */
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    /** Whether two-factor authentication is enabled for this account. */
    @Column(name = "twofa_enabled", nullable = false)
    @Builder.Default
    private Boolean twofaEnabled = false;

    /** TOTP/2FA shared secret (encrypted at rest). */
    @Column(name = "twofa_secret", length = 255)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String twofaSecret;

    @Column(name = "default_store_id", columnDefinition = "char(36)")
    private UUID defaultStoreId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
