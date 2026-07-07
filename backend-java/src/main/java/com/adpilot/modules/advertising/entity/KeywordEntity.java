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
@TableName("keywords")
@Table(name = "keywords")
public class KeywordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "campaign_id", nullable = false, columnDefinition = "char(36)")
    private UUID campaignId;

    @Column(name = "ad_group_id", nullable = false, columnDefinition = "char(36)")
    private UUID adGroupId;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "keyword_text", nullable = false, length = 500)
    private String keywordText;

    @Column(name = "match_type", length = 20)
    @Builder.Default
    private String matchType = "broad";

    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "enabled";

    @Column(name = "bid", precision = 10, scale = 4)
    private BigDecimal bid;

    @Column(name = "impressions")
    @Builder.Default
    private Long impressions = 0L;

    @Column(name = "clicks")
    @Builder.Default
    private Integer clicks = 0;

    @Column(name = "spend")
    @Builder.Default
    private BigDecimal spend = BigDecimal.ZERO;

    @Column(name = "sales")
    @Builder.Default
    private BigDecimal sales = BigDecimal.ZERO;

    @Column(name = "orders")
    @Builder.Default
    private Integer orders = 0;

    @Column(name = "acos", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal acos = BigDecimal.ZERO;

    @Column(name = "roas", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal roas = BigDecimal.ZERO;

    @Column(name = "ctr", precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal ctr = BigDecimal.ZERO;

    @Column(name = "cvr", precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal cvr = BigDecimal.ZERO;

    @Column(name = "avg_cpc", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal avgCpc = BigDecimal.ZERO;

    @Column(name = "external_id", length = 100)
    private String externalId;

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
