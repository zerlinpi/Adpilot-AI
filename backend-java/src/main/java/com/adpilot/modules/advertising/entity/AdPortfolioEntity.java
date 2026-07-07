package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ad Portfolio (广告组合) — a grouping of campaigns with a shared budget and
 * reporting, belonging to a store (Req 20). Campaigns reference their owning
 * portfolio via {@code campaigns.portfolio_id}.
 *
 * <p>Backed by the additive {@code ad_portfolios} table created in
 * {@code V2__ai_advertising_module.sql}. Conventions mirror
 * {@link CampaignEntity}: {@code CHAR(36)} ids, JPA + MyBatis-Plus annotations,
 * and {@code DECIMAL(18,4)} money.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("ad_portfolios")
@Table(name = "ad_portfolios")
public class AdPortfolioEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Targeting status (投放状态): enabled / paused. */
    @Column(name = "state", length = 20)
    @Builder.Default
    private String state = "enabled";

    /** Budget type (预算类型): {@code none} => 无预算上限 | {@code recurring} | {@code date_range}. */
    @Column(name = "budget_type", length = 20)
    @Builder.Default
    private String budgetType = "none";

    @Column(name = "budget", precision = 18, scale = 4)
    private BigDecimal budget;

    @Column(name = "start_date", length = 10)
    private String startDate;

    @Column(name = "end_date", length = 10)
    private String endDate;

    @Column(name = "external_id", length = 100)
    private String externalId;

    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
