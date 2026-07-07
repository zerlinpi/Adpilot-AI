package com.adpilot.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@TableName("inventory_forecasts")
@Table(name = "inventory_forecasts")
public class InventoryForecastEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "product_id", columnDefinition = "char(36)")
    private UUID productId;

    @Column(name = "forecast_date")
    private LocalDate forecastDate;

    @Column(name = "daily_sales_velocity")
    private BigDecimal dailySalesVelocity;

    @Column(name = "ad_driven_sales_velocity")
    private BigDecimal adDrivenSalesVelocity;

    @Column(name = "organic_sales_velocity")
    private BigDecimal organicSalesVelocity;

    @Column(name = "days_of_supply")
    private Integer daysOfSupply;

    @Column(name = "stockout_date")
    private LocalDate stockoutDate;

    @Column(name = "stockout_risk")
    private String stockoutRisk;

    @Column(name = "overstock_risk")
    private String overstockRisk;

    @Column(name = "recommended_replenishment_qty")
    private Integer recommendedReplenishmentQty;

    @Column(name = "confidence_score")
    private Integer confidenceScore;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
