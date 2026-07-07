package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableField;
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
@TableName("campaigns")
@Table(name = "campaigns")
public class CampaignEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "goal_id", columnDefinition = "char(36)")
    private UUID goalId;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "campaign_type", length = 50)
    private String campaignType;

    @Column(name = "portfolio", length = 100)
    private String portfolio;

    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = "enabled";

    @Column(name = "budget", precision = 10, scale = 2)
    private BigDecimal budget;

    @Column(name = "budget_type", length = 20)
    @Builder.Default
    private String budgetType = "daily";

    @Column(name = "start_date", length = 10)
    private String startDate;

    @Column(name = "end_date", length = 10)
    private String endDate;

    @Column(name = "targeting_type", length = 30)
    private String targetingType;

    @Column(name = "state", length = 20)
    private String state;

    @Column(name = "spend")
    @Builder.Default
    private BigDecimal spend = BigDecimal.ZERO;

    @Column(name = "sales")
    @Builder.Default
    private BigDecimal sales = BigDecimal.ZERO;

    @Column(name = "orders")
    @Builder.Default
    private Integer orders = 0;

    @Column(name = "impressions")
    @Builder.Default
    private Long impressions = 0L;

    @Column(name = "clicks")
    @Builder.Default
    private Integer clicks = 0;

    @Column(name = "acos", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal acos = BigDecimal.ZERO;

    @Column(name = "roas", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal roas = BigDecimal.ZERO;

    @Column(name = "conversion_rate", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal conversionRate = BigDecimal.ZERO;

    @Column(name = "avg_cpc", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal avgCpc = BigDecimal.ZERO;

    @Column(name = "ad_group_count")
    @Builder.Default
    private Integer adGroupCount = 0;

    @Column(name = "keyword_count")
    @Builder.Default
    private Integer keywordCount = 0;

    @Column(name = "negative_keyword_count")
    @Builder.Default
    private Integer negativeKeywordCount = 0;

    @Column(name = "external_id", length = 100)
    private String externalId;

    // ----- advertising-workspace-rework columns -----

    /** Campaign-level AI_Personality override; null = inherit Goal/Store default (Req 49.2). */
    @Column(name = "campaign_personality", length = 20)
    private String campaignPersonality;

    /** Immutable origin: {@code local} or {@code amazon_import} (Req 12.6). */
    @Column(name = "origin", length = 20)
    @Builder.Default
    private String origin = "local";

    /** Amazon-assigned campaign id; gates Amazon-synced-list inclusion (Req 12.7). */
    @Column(name = "amazon_campaign_id", length = 100)
    private String amazonCampaignId;

    /** Optimistic-lock version (Req 5.4). */
    @Version
    @Column(name = "version")
    @Builder.Default
    private Long version = 0L;

    // ----- AI advertising columns (V2__ai_advertising_module.sql) -----

    /** Whether the campaign is under AI hosting (Req 21). */
    @Column(name = "hosting_enabled")
    @Builder.Default
    private Boolean hostingEnabled = false;

    /** Hosting goal (托管目标), e.g. {@code maximize_sales_at_target} (Req 21). */
    @Column(name = "hosting_goal", length = 40)
    private String hostingGoal;

    /** Target ACoS (目标ACOS) as a percentage; required when hosting is enabled (Req 21). */
    @Column(name = "target_acos", precision = 10, scale = 4)
    private BigDecimal targetAcos;

    /** AI-managed indicator (AI入格) (Req 21.3). */
    @Column(name = "ai_managed")
    @Builder.Default
    private Boolean aiManaged = false;

    /** Owning ad portfolio (Req 20); {@code null} when unassigned. */
    @Column(name = "portfolio_id", columnDefinition = "char(36)")
    private UUID portfolioId;

    // ----- Non-persisted filter attributes (Req 19.4) -----
    // These have no dedicated column yet; they are populated from related data
    // when available and are used by the pure CampaignFilter predicate. Marked
    // non-persistent for both JPA and MyBatis-Plus so they are never written.

    /** Parent ASIN (父ASIN) the campaign promotes; resolved from related data. */
    @Transient
    @TableField(exist = false)
    private String parentAsin;

    /** Targeting goal (投放目标); resolved from related data. */
    @Transient
    @TableField(exist = false)
    private String targetingGoal;

    @Column(name = "tags", columnDefinition = "json")
    private String tags;

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
