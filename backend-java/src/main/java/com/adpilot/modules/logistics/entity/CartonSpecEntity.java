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
 * Carton Spec (箱规) — per-shipment box dimensions, weight, and counts.
 * Mirrors {@code carton_specs} in db/schema.sql.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("carton_specs")
@Table(name = "carton_specs")
public class CartonSpecEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "shipment_id", nullable = false, columnDefinition = "char(36)")
    private UUID shipmentId;

    @Column(name = "box_length_cm", precision = 6, scale = 1)
    @Builder.Default
    private BigDecimal boxLengthCm = BigDecimal.ZERO;

    @Column(name = "box_width_cm", precision = 6, scale = 1)
    @Builder.Default
    private BigDecimal boxWidthCm = BigDecimal.ZERO;

    @Column(name = "box_height_cm", precision = 6, scale = 1)
    @Builder.Default
    private BigDecimal boxHeightCm = BigDecimal.ZERO;

    @Column(name = "box_weight_kg", precision = 8, scale = 2)
    @Builder.Default
    private BigDecimal boxWeightKg = BigDecimal.ZERO;

    @Column(name = "units_per_box")
    @Builder.Default
    private Integer unitsPerBox = 0;

    @Column(name = "box_count")
    @Builder.Default
    private Integer boxCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
