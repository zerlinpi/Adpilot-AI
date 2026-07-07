package com.adpilot.modules.review.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("listing_quality_checks")
@Table(name = "listing_quality_checks")
public class ListingQualityCheckEntity {

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "issues", columnDefinition = "json")
    @Builder.Default
    private String issues = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recommendations", columnDefinition = "json")
    @Builder.Default
    private String recommendations = "[]";

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
