package com.adpilot.modules.advertising.entity;

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
@TableName("search_terms")
@Table(name = "search_terms")
public class SearchTermEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "campaign_id", nullable = false, columnDefinition = "char(36)")
    private UUID campaignId;

    @Column(name = "ad_group_id", columnDefinition = "char(36)")
    private UUID adGroupId;

    @Column(name = "keyword_id", columnDefinition = "char(36)")
    private UUID keywordId;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "search_term", nullable = false, length = 500)
    private String searchTerm;

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

    @Column(name = "ctr", precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal ctr = BigDecimal.ZERO;

    @Column(name = "cvr", precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal cvr = BigDecimal.ZERO;

    @Column(name = "avg_cpc", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal avgCpc = BigDecimal.ZERO;

    @Column(name = "roas", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal roas = BigDecimal.ZERO;

    @Column(name = "harvested")
    @Builder.Default
    private Boolean harvested = false;

    /**
     * Harvest lifecycle status (Req 13). One of {@code candidate}, {@code add_exact},
     * {@code add_phrase}, {@code add_broad}, {@code add_negative}, {@code watchlist},
     * or {@code waste}. Records the chosen Harvest_Action so a watch record can be
     * persisted without creating a Keyword or Negative_Keyword (Req 13.6).
     */
    @Column(name = "harvesting_status", length = 30)
    @Builder.Default
    private String harvestingStatus = "candidate";

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
