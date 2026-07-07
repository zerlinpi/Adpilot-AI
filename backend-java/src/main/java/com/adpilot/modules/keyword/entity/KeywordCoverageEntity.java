package com.adpilot.modules.keyword.entity;

import com.baomidou.mybatisplus.annotation.TableField;
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
@TableName("keyword_coverage")
@Table(name = "keyword_coverage")
public class KeywordCoverageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "campaign_id", columnDefinition = "char(36)")
    private UUID campaignId;

    @Column(name = "keyword_id", columnDefinition = "char(36)")
    private UUID keywordId;

    @Column(name = "keyword_text", length = 500)
    private String keywordText;

    @Column(name = "match_type", length = 20)
    private String matchType;

    @TableField("is_in_listing")
    @Column(name = "is_in_listing")
    private Boolean inListing;

    @TableField("is_in_title")
    @Column(name = "is_in_title")
    private Boolean inTitle;

    @TableField("is_in_bullet_points")
    @Column(name = "is_in_bullet_points")
    private Boolean inBulletPoints;

    @TableField("is_in_backend")
    @Column(name = "is_in_backend")
    private Boolean inBackend;

    @Column(name = "impressions")
    private Long impressions;

    @Column(name = "clicks")
    private Long clicks;

    @Column(name = "orders")
    private Long orders;

    @Column(name = "spend", precision = 12, scale = 2)
    private BigDecimal spend;

    @Column(name = "sales", precision = 12, scale = 2)
    private BigDecimal sales;

    @Column(name = "acos", precision = 8, scale = 4)
    private BigDecimal acos;

    @Column(name = "cvr", precision = 8, scale = 4)
    private BigDecimal cvr;

    @Column(name = "coverage_status", length = 20)
    private String coverageStatus;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
