package com.adpilot.modules.logistics.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Customs Clearance (清关) — one record per shipment tracking declaration
 * status and duties/taxes. Mirrors {@code customs_clearance} in db/schema.sql.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("customs_clearance")
@Table(name = "customs_clearance")
public class CustomsClearanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "shipment_id", nullable = false, columnDefinition = "char(36)")
    private UUID shipmentId;

    @Column(name = "clearance_status", nullable = false, length = 30)
    @Builder.Default
    private String clearanceStatus = "not_started";

    @Column(name = "declaration_ref", length = 100)
    private String declarationRef;

    @Column(name = "duties_taxes", precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal dutiesTaxes = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
