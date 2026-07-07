package com.adpilot.modules.listingops.entity;

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
@TableName("buy_box_alerts")
@Table(name = "buy_box_alerts")
public class BuyBoxAlertEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "asin", length = 20)
    private String asin;

    @Column(name = "sku", length = 100)
    private String sku;

    @Column(name = "product_name", length = 500)
    private String productName;

    @Column(name = "buy_box_seller", length = 255)
    private String buyBoxSeller;

    @Column(name = "buy_box_price", precision = 18, scale = 4)
    private BigDecimal buyBoxPrice;

    @Column(name = "is_own_buy_box")
    @Builder.Default
    private Boolean isOwnBuyBox = false;

    @Column(name = "alert_type", length = 50)
    private String alertType;

    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = "open";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
