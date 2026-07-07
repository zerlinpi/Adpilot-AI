package com.adpilot.modules.logistics.entity;

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

/**
 * Shipment Exception (异常) — an issue raised against a shipment that follows a
 * one-way open -> resolved transition. Mirrors {@code shipment_exceptions} in
 * db/schema.sql.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("shipment_exceptions")
@Table(name = "shipment_exceptions")
public class ShipmentExceptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "shipment_id", nullable = false, columnDefinition = "char(36)")
    private UUID shipmentId;

    @Column(name = "exception_type", nullable = false, length = 30)
    private String exceptionType;

    @Column(name = "description", nullable = false, length = 1000)
    private String description;

    @Column(name = "resolution_state", nullable = false, length = 20)
    @Builder.Default
    private String resolutionState = "open";

    @Column(name = "resolved_by", columnDefinition = "char(36)")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
