package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

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
@TableName("goals")
@Table(name = "goals")
public class GoalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "type", length = 50)
    private String type;

    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "active";

    @Column(name = "target_acos", precision = 10, scale = 2)
    private BigDecimal targetAcos;

    @Column(name = "daily_budget", precision = 10, scale = 2)
    private BigDecimal dailyBudget;

    @Column(name = "max_cpc", precision = 10, scale = 2)
    private BigDecimal maxCpc;

    @Column(name = "min_bid", precision = 10, scale = 4)
    private BigDecimal minBid;

    @Column(name = "max_bid", precision = 10, scale = 4)
    private BigDecimal maxBid;

    @Column(name = "brand_keywords", columnDefinition = "json")
    private String brandKeywords;

    @Column(name = "category_keywords", columnDefinition = "json")
    private String categoryKeywords;

    @Column(name = "competitor_brands", columnDefinition = "json")
    private String competitorBrands;

    @Column(name = "competitor_asins", columnDefinition = "json")
    private String competitorAsins;

    @Column(name = "auto_negate")
    @Builder.Default
    private Boolean autoNegate = false;

    @Column(name = "auto_bid")
    @Builder.Default
    private Boolean autoBid = true;

    @Column(name = "auto_expand")
    @Builder.Default
    private Boolean autoExpand = false;

    @Column(name = "optimize_frequency", length = 20)
    private String optimizeFrequency;

    @Column(name = "risk_preference", length = 20)
    private String riskPreference;

    @Column(name = "product_ids", columnDefinition = "json")
    private String productIds;

    /** Optimistic-lock version (Req 5.4). */
    @Version
    @Column(name = "version")
    @Builder.Default
    private Long version = 0L;

    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_by", columnDefinition = "char(36)")
    private UUID updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
