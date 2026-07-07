package com.adpilot.modules.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("data_scopes")
@Table(name = "data_scopes")
public class DataScope {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "role_id", nullable = false, columnDefinition = "char(36)")
    private UUID roleId;

    @Column(name = "scope_type", nullable = false, length = 50)
    private String scopeType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "store_ids", columnDefinition = "json")
    @Builder.Default
    private List<String> storeIds = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "product_ids", columnDefinition = "json")
    @Builder.Default
    private List<String> productIds = List.of();

    /**
     * Store-group ids carried by an {@code assigned_store_group} scope row
     * (platform-workspace-rbac Req 13.1, 18.3). Mirrors {@link #storeIds} /
     * {@link #productIds}; the union of these across the account's roles forms the
     * effective Store_Group_Scope (Req 13.7).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "store_group_ids", columnDefinition = "json")
    @Builder.Default
    private List<String> storeGroupIds = List.of();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
