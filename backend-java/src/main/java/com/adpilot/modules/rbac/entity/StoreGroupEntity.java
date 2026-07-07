package com.adpilot.modules.rbac.entity;

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

/**
 * First-class grouping of stores within a platform family (platform-workspace-rbac
 * Req 10). Every Store belongs to exactly one Store_Group, and the per-family
 * {@code is_default} group is the fallback for unassigned stores (Req 10.7).
 *
 * <p>The {@code platform_family} column is constrained to {@code amazon} or
 * {@code independent_site} at the schema level (Req 10.1); see
 * {@link com.adpilot.modules.rbac.PlatformFamily}.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("store_groups")
@Table(name = "store_groups", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"org_id", "platform_family", "name"})
})
public class StoreGroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "org_id", nullable = false, columnDefinition = "char(36)")
    private UUID orgId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Platform family code: {@code amazon} | {@code independent_site} (Req 10.1). */
    @Column(name = "platform_family", nullable = false, length = 20)
    private String platformFamily;

    /** Per-family fallback group for unassigned stores (Req 10.7). */
    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
