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
 * Mapping between an external platform entity and its internal record, persisted
 * to {@code external_entity_mappings}. This mapping is the idempotency anchor
 * for sync upserts (Req 1.2): a record whose external id already maps updates
 * the linked internal record, while an unmapped external id creates a new
 * internal record plus a new mapping.
 *
 * <p>Lookups during upsert are keyed on
 * {@code (store_id, platform, internal_entity_type, external_entity_id)}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("external_entity_mappings")
@Table(name = "external_entity_mappings")
public class ExternalEntityMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "platform", nullable = false, length = 50)
    private String platform;

    @Column(name = "internal_entity_type", nullable = false, length = 50)
    private String internalEntityType;

    @Column(name = "internal_entity_id", nullable = false, columnDefinition = "char(36)")
    private UUID internalEntityId;

    @Column(name = "external_entity_type", length = 50)
    private String externalEntityType;

    @Column(name = "external_entity_id", length = 255)
    private String externalEntityId;

    @Column(name = "external_data", columnDefinition = "json")
    @Builder.Default
    private String externalData = "{}";

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
