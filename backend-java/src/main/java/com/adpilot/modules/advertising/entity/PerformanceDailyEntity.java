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

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("performance_daily")
@Table(name = "performance_daily")
public class PerformanceDailyEntity {

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

    @Column(name = "entity_type", length = 30)
    private String entityType;

    @Column(name = "entity_id", columnDefinition = "char(36)")
    private UUID entityId;

    @Column(name = "report_date", nullable = false)
    @com.baomidou.mybatisplus.annotation.TableField("report_date")
    private LocalDate date;

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
