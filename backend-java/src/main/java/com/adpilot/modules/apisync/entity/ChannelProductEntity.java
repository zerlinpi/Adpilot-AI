package com.adpilot.modules.apisync.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Internal representation of a product pulled from a sales channel, persisted to
 * {@code channel_products}. Rows are created/updated idempotently by the upsert
 * stage (Req 1.2) and are never physically deleted: platform-removed products
 * are status-marked inactive instead (Req 1.2.4).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("channel_products")
@Table(name = "channel_products")
public class ChannelProductEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "connection_id", nullable = false, columnDefinition = "char(36)")
    private UUID connectionId;

    @Column(name = "external_product_id", length = 255)
    private String externalProductId;

    @Column(name = "sku", length = 100)
    private String sku;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "price")
    private BigDecimal price;

    @Column(name = "currency", length = 10)
    private String currency;

    @Column(name = "status", length = 30)
    private String status;

    @Column(name = "raw_data", columnDefinition = "json")
    @Builder.Default
    private String rawData = "{}";

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
