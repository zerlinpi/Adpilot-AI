package com.adpilot.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@TableName("replenishment_plans")
@Table(name = "replenishment_plans")
public class ReplenishmentPlanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "product_id", columnDefinition = "char(36)")
    private UUID productId;

    private String status;

    @Column(name = "recommended_qty")
    private Integer recommendedQty;

    @Column(name = "approved_qty")
    private Integer approvedQty;

    private String reason;

    @Column(name = "expected_stockout_date")
    private LocalDate expectedStockoutDate;

    @Column(name = "expected_arrival_date")
    private LocalDate expectedArrivalDate;

    @Column(name = "purchase_cost")
    private BigDecimal purchaseCost;

    @Column(name = "shipping_cost")
    private BigDecimal shippingCost;

    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_by", columnDefinition = "char(36)")
    private UUID updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
