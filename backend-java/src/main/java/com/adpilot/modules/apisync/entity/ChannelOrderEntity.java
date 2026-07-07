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
 * Internal representation of an order pulled from a sales channel, persisted to
 * {@code channel_orders}. Rows are created/updated idempotently by the upsert
 * stage (Req 1.2) and are never physically deleted: platform-removed orders are
 * status-marked instead (Req 1.2.4).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("channel_orders")
@Table(name = "channel_orders")
public class ChannelOrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "connection_id", nullable = false, columnDefinition = "char(36)")
    private UUID connectionId;

    @Column(name = "external_order_id", length = 255)
    private String externalOrderId;

    @Column(name = "order_status", length = 50)
    private String orderStatus;

    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    @Column(name = "currency", length = 10)
    private String currency;

    @Column(name = "order_date")
    private LocalDateTime orderDate;

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
