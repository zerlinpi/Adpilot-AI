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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Shipment Leg (头程/尾程 segment) — one ordered segment of a shipment's
 * transport path with an assigned carrier, dates, and leg cost. Mirrors
 * {@code shipment_legs} in db/schema.sql.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("shipment_legs")
@Table(name = "shipment_legs")
public class ShipmentLegEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "shipment_id", nullable = false, columnDefinition = "char(36)")
    private UUID shipmentId;

    @Column(name = "leg_type", nullable = false, length = 50)
    private String legType;

    @Column(name = "sequence_no", nullable = false)
    private Integer sequenceNo;

    @Column(name = "carrier_id", columnDefinition = "char(36)")
    private UUID carrierId;

    @Column(name = "departure_date")
    private LocalDate departureDate;

    @Column(name = "arrival_date")
    private LocalDate arrivalDate;

    @Column(name = "leg_cost", precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal legCost = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
