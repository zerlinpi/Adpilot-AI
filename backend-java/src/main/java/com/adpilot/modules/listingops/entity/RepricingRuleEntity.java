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
@TableName("repricing_rules")
@Table(name = "repricing_rules")
public class RepricingRuleEntity {

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

    @Column(name = "rule_name", nullable = false, length = 255)
    private String ruleName;

    @Column(name = "min_price", precision = 18, scale = 4)
    private BigDecimal minPrice;

    @Column(name = "max_price", precision = 18, scale = 4)
    private BigDecimal maxPrice;

    @Column(name = "strategy", length = 50)
    private String strategy;

    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = "active";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
