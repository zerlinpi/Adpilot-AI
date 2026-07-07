package com.adpilot.modules.warehouse.entity;

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

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("warehouse_inventory")
@Table(name = "warehouse_inventory")
public class WarehouseInventoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "warehouse_location_id", nullable = false, columnDefinition = "char(36)")
    private UUID warehouseLocationId;

    @Column(name = "sku", nullable = false, length = 100)
    private String sku;

    @Column(name = "asin", length = 20)
    private String asin;

    @Column(name = "product_name", length = 500)
    private String productName;

    @Column(name = "quantity_on_hand")
    @Builder.Default
    private Integer quantityOnHand = 0;

    @Column(name = "quantity_reserved")
    @Builder.Default
    private Integer quantityReserved = 0;

    @Column(name = "quantity_available")
    @Builder.Default
    private Integer quantityAvailable = 0;

    @Column(name = "reorder_point")
    @Builder.Default
    private Integer reorderPoint = 0;

    @Column(name = "reorder_quantity")
    @Builder.Default
    private Integer reorderQuantity = 0;

    @Column(name = "unit_cost", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "last_counted_at")
    private LocalDateTime lastCountedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
