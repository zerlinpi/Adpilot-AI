package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Read model over the existing {@code raw_advertised_product_reports} table
 * (imported Amazon "Advertised Product"/promoted-product report rows). Reused to
 * back the workspace's 推广商品 (promoted products) and 购买的其他商品 (purchased
 * products) surfaces; no schema change is introduced.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("raw_advertised_product_reports")
@Table(name = "raw_advertised_product_reports")
public class AdvertisedProductReportEntity {

    @Id
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "import_job_id", columnDefinition = "char(36)")
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

    @Column(name = "sku", length = 100)
    private String sku;

    @Column(name = "asin", length = 20)
    private String asin;

    @Column(name = "impressions")
    private Integer impressions;

    @Column(name = "clicks")
    private Integer clicks;

    @Column(name = "spend", precision = 18, scale = 4)
    private BigDecimal spend;

    @Column(name = "sales", precision = 18, scale = 4)
    private BigDecimal sales;

    @Column(name = "orders")
    private Integer orders;

    @Column(name = "raw_data", columnDefinition = "json")
    private String rawData;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
