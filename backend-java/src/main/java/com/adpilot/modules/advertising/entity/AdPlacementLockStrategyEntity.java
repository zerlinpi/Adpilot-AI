package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ad Placement Lock strategy (卡位策略 / 广告位锁定) — a placement strategy that
 * targets a specific Amazon ad placement (e.g. top-of-search 首页1-1位) for an SP
 * campaign using a keyword bid range, to hold the ad in that placement (Req 26).
 *
 * <p>Backed by the additive {@code ad_placement_lock_strategies} table created in
 * {@code V3__ai_workitems.sql}. The bid range invariant {@code bidMin <= bidMax}
 * is enforced in the service layer (Req 26.4). Conventions mirror
 * {@link AdPortfolioEntity}: {@code CHAR(36)} ids and {@code DECIMAL(18,4)} money.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("ad_placement_lock_strategies")
@Table(name = "ad_placement_lock_strategies")
public class AdPlacementLockStrategyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "campaign_id", nullable = false, columnDefinition = "char(36)")
    private UUID campaignId;

    /** Target placement (e.g. {@code top_of_search_1_1}, {@code page1_5_8}). */
    @Column(name = "target_placement", nullable = false, length = 40)
    private String targetPlacement;

    /** Lower bound of the keyword bid range (Req 26.2). */
    @Column(name = "bid_min", nullable = false, precision = 18, scale = 4)
    private BigDecimal bidMin;

    /** Upper bound of the keyword bid range; {@code bidMin <= bidMax} (Req 26.4). */
    @Column(name = "bid_max", nullable = false, precision = 18, scale = 4)
    private BigDecimal bidMax;

    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "active";

    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
