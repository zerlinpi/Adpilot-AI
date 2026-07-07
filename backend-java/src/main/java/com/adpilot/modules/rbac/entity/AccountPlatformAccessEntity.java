package com.adpilot.modules.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The Platform_Access RBAC dimension: which of the four Nav_Blocks an account may
 * enter (platform-workspace-rbac Req 12). One row per (user, platform family);
 * the unique constraint keeps grants idempotent (Req 18.6).
 *
 * <p>The {@code platform_family} column accepts any of the four families
 * ({@code amazon} | {@code independent_site} | {@code logistics} | {@code finance});
 * see {@link com.adpilot.modules.rbac.PlatformFamily}.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("account_platform_access")
@Table(name = "account_platform_access", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "platform_family"})
})
public class AccountPlatformAccessEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "char(36)")
    private UUID userId;

    /** Platform family code an account may enter (Req 12.1). */
    @Column(name = "platform_family", nullable = false, length = 20)
    private String platformFamily;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
