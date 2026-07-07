package com.adpilot.modules.listingops.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("listing_monitors")
@Table(name = "listing_monitors")
public class ListingMonitorEntity {

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

    @Column(name = "overall_score", precision = 5, scale = 2)
    private BigDecimal overallScore;

    @Column(name = "title_score", precision = 5, scale = 2)
    private BigDecimal titleScore;

    @Column(name = "bullet_score", precision = 5, scale = 2)
    private BigDecimal bulletScore;

    @Column(name = "description_score", precision = 5, scale = 2)
    private BigDecimal descriptionScore;

    @Column(name = "image_score", precision = 5, scale = 2)
    private BigDecimal imageScore;

    @Column(name = "keyword_score", precision = 5, scale = 2)
    private BigDecimal keywordScore;

    @Column(name = "issues", columnDefinition = "TEXT")
    private String issues;

    @Column(name = "recommendations", columnDefinition = "TEXT")
    private String recommendations;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
