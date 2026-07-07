package com.adpilot.modules.automation.entity;

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
@TableName("automation_policies")
@Table(name = "automation_policies")
public class AutomationPolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "org_id", nullable = false, columnDefinition = "char(36)")
    private UUID orgId;

    @Column(name = "store_id", columnDefinition = "char(36)")
    private UUID storeId;

    @Column(length = 30)
    @Builder.Default
    private String mode = "manual";

    @Column(name = "max_bid_change_pct")
    @Builder.Default
    private Integer maxBidChangePct = 20;

    @Column(name = "max_budget_change_pct")
    @Builder.Default
    private Integer maxBudgetChangePct = 30;

    @Column(name = "min_clicks_before_negative")
    @Builder.Default
    private Integer minClicksBeforeNegative = 20;

    @Column(name = "block_brand_negative")
    @Builder.Default
    private Boolean blockBrandNegative = true;

    @Column(name = "block_competitor_in_listing")
    @Builder.Default
    private Boolean blockCompetitorInListing = true;

    @Column(name = "inventory_threshold")
    @Builder.Default
    private Integer inventoryThreshold = 50;

    @Column(name = "min_days_of_supply_to_scale")
    @Builder.Default
    private Integer minDaysOfSupplyToScale = 30;

    @Column(name = "require_approval_for_high_risk")
    @Builder.Default
    private Boolean requireApprovalForHighRisk = true;

    @Column(name = "require_approval_for_product_upload")
    @Builder.Default
    private Boolean requireApprovalForProductUpload = true;

    @Column(name = "require_approval_for_replenishment")
    @Builder.Default
    private Boolean requireApprovalForReplenishment = true;

    @Column(name = "daily_budget_limit", precision = 18, scale = 4)
    private BigDecimal dailyBudgetLimit;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
