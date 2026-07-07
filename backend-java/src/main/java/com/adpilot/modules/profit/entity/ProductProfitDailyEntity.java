package com.adpilot.modules.profit.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@TableName("product_profit_daily")
@Table(name = "product_profit_daily")
public class ProductProfitDailyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "product_id", columnDefinition = "char(36)")
    private UUID productId;

    @Column(name = "marketplace_id", columnDefinition = "char(36)")
    private UUID marketplaceId;

    private LocalDate date;

    private String sku;

    private String asin;

    @Column(name = "units_sold")
    private Integer unitsSold;

    @Column(name = "gross_sales")
    private BigDecimal grossSales;

    @Column(name = "organic_sales")
    private BigDecimal organicSales;

    @Column(name = "ad_sales")
    private BigDecimal adSales;

    @Column(name = "ad_spend")
    private BigDecimal adSpend;

    @Column(name = "amazon_referral_fee")
    private BigDecimal amazonReferralFee;

    @Column(name = "fba_fee")
    private BigDecimal fbaFee;

    @Column(name = "storage_fee")
    private BigDecimal storageFee;

    @Column(name = "refund_cost")
    private BigDecimal refundCost;

    @Column(name = "promo_cost")
    private BigDecimal promoCost;

    private BigDecimal cogs;

    @Column(name = "inbound_shipping_cost")
    private BigDecimal inboundShippingCost;

    @Column(name = "other_cost")
    private BigDecimal otherCost;

    @Column(name = "gross_profit")
    private BigDecimal grossProfit;

    @Column(name = "net_profit")
    private BigDecimal netProfit;

    @Column(name = "gross_margin")
    private BigDecimal grossMargin;

    @Column(name = "net_margin")
    private BigDecimal netMargin;

    private BigDecimal acos;

    private BigDecimal tacos;

    private BigDecimal roas;

    @Column(name = "break_even_acos")
    private BigDecimal breakEvenAcos;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
