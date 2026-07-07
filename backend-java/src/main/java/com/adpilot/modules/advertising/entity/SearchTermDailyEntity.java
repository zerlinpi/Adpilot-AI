package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity mapping to the {@code search_term_daily} table (Req 31).
 * Stores daily search-term performance data with idempotent upsert
 * keyed by {@code (store_id, campaign_id, ad_group_id, search_term, report_date)}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("search_term_daily")
@Table(name = "search_term_daily")
public class SearchTermDailyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "campaign_id", nullable = false, columnDefinition = "char(36)")
    private UUID campaignId;

    @Column(name = "ad_group_id", nullable = false, columnDefinition = "char(36)")
    private UUID adGroupId;

    @Column(name = "search_term", nullable = false, length = 500)
    private String searchTerm;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "impressions")
    @Builder.Default
    private Long impressions = 0L;

    @Column(name = "clicks")
    @Builder.Default
    private Integer clicks = 0;

    @Column(name = "orders")
    @Builder.Default
    private Integer orders = 0;

    @Column(name = "spend", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal spend = BigDecimal.ZERO;

    @Column(name = "sales", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal sales = BigDecimal.ZERO;

    @Column(name = "acos", precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal acos = BigDecimal.ZERO;

    @Column(name = "currency", length = 10)
    private String currency;

    @Column(name = "data_status", length = 12, nullable = false)
    @Builder.Default
    private String dataStatus = "preliminary";

    @Column(name = "data_version", nullable = false)
    @Builder.Default
    private Integer dataVersion = 1;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
