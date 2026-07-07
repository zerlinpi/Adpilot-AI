package com.adpilot.modules.importcenter.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("raw_search_term_reports")
@Table(name = "raw_search_term_reports")
public class RawSearchTermReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "import_job_id", nullable = false, columnDefinition = "char(36)")
    private UUID importJobId;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "marketplace_id", columnDefinition = "char(36)")
    private UUID marketplaceId;

    @Column(name = "report_date")
    private LocalDate reportDate;

    @Column(name = "campaign_name", length = 500)
    private String campaignName;

    @Column(name = "ad_group_name", length = 500)
    private String adGroupName;

    @Column(name = "targeting", length = 500)
    private String targeting;

    @Column(name = "match_type", length = 50)
    private String matchType;

    @Column(name = "customer_search_term", length = 500)
    private String customerSearchTerm;

    @Column(name = "impressions")
    @Builder.Default
    private Integer impressions = 0;

    @Column(name = "clicks")
    @Builder.Default
    private Integer clicks = 0;

    @Column(name = "spend", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal spend = BigDecimal.ZERO;

    @Column(name = "sales", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal sales = BigDecimal.ZERO;

    @Column(name = "orders")
    @Builder.Default
    private Integer orders = 0;

    @Column(columnDefinition = "json")
    private String rawData;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
