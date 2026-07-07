package com.adpilot.modules.apisync.entity;

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
 * Internal representation of an inventory record pulled from a sales channel
 * (Amazon SP-API FBA inventory), persisted to {@code channel_inventory_sync}.
 * Rows are created/updated idempotently by the upsert stage keyed on the
 * external identifier via {@code external_entity_mappings} (Req 1.2, 8.1.4).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("channel_inventory_sync")
@Table(name = "channel_inventory_sync")
public class ChannelInventorySyncEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "connection_id", nullable = false, columnDefinition = "char(36)")
    private UUID connectionId;

    @Column(name = "sku", length = 100)
    private String sku;

    @Column(name = "external_product_id", length = 255)
    private String externalProductId;

    @Column(name = "quantity")
    @Builder.Default
    private Integer quantity = 0;

    @Column(name = "sync_direction", length = 20)
    private String syncDirection;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
