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

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("shipments")
@Table(name = "shipments")
public class ShipmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "shipment_id", length = 255)
    private String shipmentId;

    @Column(name = "shipment_type", length = 50)
    private String shipmentType;

    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = "pending";

    @Column(name = "carrier", length = 100)
    private String carrier;

    @Column(name = "tracking_number", length = 255)
    private String trackingNumber;

    @Column(name = "ship_from_address", columnDefinition = "TEXT")
    private String shipFromAddress;

    @Column(name = "ship_to_address", columnDefinition = "TEXT")
    private String shipToAddress;

    @Column(name = "ship_date")
    private LocalDate shipDate;

    @Column(name = "estimated_delivery_date")
    private LocalDate estimatedDeliveryDate;

    @Column(name = "actual_delivery_date")
    private LocalDate actualDeliveryDate;

    @Column(name = "total_weight", precision = 12, scale = 4)
    private BigDecimal totalWeight;

    @Column(name = "weight_unit", length = 10)
    private String weightUnit;

    @Column(name = "total_items")
    @Builder.Default
    private Integer totalItems = 0;

    @Column(name = "shipping_cost", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal shippingCost = BigDecimal.ZERO;

    @Column(name = "currency", length = 10)
    private String currency;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "fba_shipment_id", length = 100)
    private String fbaShipmentId;

    @Column(name = "amazon_shipment_status", length = 50)
    private String amazonShipmentStatus;

    @Column(name = "destination_fc_code", length = 50)
    private String destinationFcCode;

    @Column(name = "reporting_currency", columnDefinition = "char(3)")
    private String reportingCurrency;

    @Column(name = "raw_data", columnDefinition = "json")
    private String rawData;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
