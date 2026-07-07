package com.adpilot.modules.store.entity;

import com.adpilot.modules.apisync.model.ConnectionStatus;
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
@TableName("stores")
@Table(name = "stores")
public class StoreEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "org_id", nullable = false, columnDefinition = "char(36)")
    private UUID orgId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "marketplace_id", nullable = false, columnDefinition = "char(36)")
    private UUID marketplaceId;

    @Column(name = "seller_id", length = 255)
    private String sellerId;

    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = ConnectionStatus.CONNECTED;

    @Column(name = "store_group", length = 100)
    private String storeGroup;

    /**
     * First-class Store_Group association (platform-workspace-rbac Req 10.2, 18.2).
     * Every Store resolves to exactly one Store_Group of its platform family; the
     * legacy free-text {@link #storeGroup} label above is retained and reconciled
     * by migration.
     */
    @Column(name = "store_group_id", columnDefinition = "char(36)")
    private UUID storeGroupId;

    /**
     * Platform family the Store belongs to: {@code amazon} | {@code independent_site}
     * (Req 10.1, 18.2). Derived from the marketplace/platform during migration and
     * used to enforce the Store↔Store_Group platform-family match on assignment
     * (Req 10.6).
     */
    @Column(name = "platform_family", length = 20)
    private String platformFamily;

    /** Store_Default_Personality used when a Campaign's personality is otherwise unresolved (Req 49.2). */
    @Column(name = "default_personality", length = 20)
    private String defaultPersonality;

    /**
     * Per-store policy when the Store is not write-capable; exactly one of
     * {@code allow_local_draft} or {@code forbid} (Req 12.3).
     */
    @Column(name = "not_write_capable_policy", length = 30)
    @Builder.Default
    private String notWriteCapablePolicy = "allow_local_draft";

    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_by", columnDefinition = "char(36)")
    private UUID updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
