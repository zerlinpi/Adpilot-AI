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
 * personality_policies — one row per {@code (scope, personality)} carrying the
 * concrete numeric control fields (candidate-eligibility thresholds, approval
 * gating ratios, Safety_Boundary maximum ratios, explore-budget band, V3
 * keyword/negative control fields) and the in-effect {@code rule_version} that is
 * recorded on every AI Operation (Req 49.5 / 49.6 / 49.9).
 *
 * <p>Backed by the {@code personality_policies} table (Req 49.5).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("personality_policies")
@Table(name = "personality_policies")
public class PersonalityPolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    /** Policy scope, e.g. {@code system} / {@code store} / {@code campaign}. */
    @Column(name = "scope", nullable = false, length = 20)
    private String scope;

    /** The scoped entity id (e.g. store UUID); null for system scope. */
    @Column(name = "scope_id", columnDefinition = "char(36)")
    private UUID scopeId;

    /** The personality this row parameterizes, e.g. {@code balanced}. */
    @Column(name = "personality", nullable = false, length = 20)
    private String personality;

    /** Row status: {@code active} or {@code inactive}. */
    @Column(name = "status", nullable = false, length = 12)
    @Builder.Default
    private String status = "active";

    /** Optional start of the validity window. */
    @Column(name = "effective_from")
    private LocalDateTime effectiveFrom;

    /** Optional end of the validity window. */
    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    // ----- candidate-eligibility thresholds -----

    @Column(name = "min_clicks", nullable = false)
    @Builder.Default
    private Integer minClicks = 0;

    @Column(name = "min_orders", nullable = false)
    @Builder.Default
    private Integer minOrders = 0;

    @Column(name = "lookback_days", nullable = false)
    @Builder.Default
    private Integer lookbackDays = 30;

    @Column(name = "min_conversion_rate", nullable = false, precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal minConversionRate = BigDecimal.ZERO;

    @Column(name = "negative_confidence_threshold", nullable = false, precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal negativeConfidenceThreshold = BigDecimal.ZERO;

    @Column(name = "acos_tolerance_ratio", nullable = false, precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal acosToleranceRatio = BigDecimal.ZERO;

    // ----- approval gating ratios (Req 49.5 / 22.8) -----

    @Column(name = "approval_bid_change_ratio", nullable = false, precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal approvalBidChangeRatio = BigDecimal.ZERO;

    @Column(name = "approval_budget_change_ratio", nullable = false, precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal approvalBudgetChangeRatio = BigDecimal.ZERO;

    // ----- Safety_Boundary maximum ratios (Req 49.10 / 49.11) -----

    @Column(name = "max_bid_increase_ratio", nullable = false, precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal maxBidIncreaseRatio = BigDecimal.ZERO;

    @Column(name = "max_bid_decrease_ratio", nullable = false, precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal maxBidDecreaseRatio = BigDecimal.ZERO;

    @Column(name = "max_daily_budget_increase_ratio", nullable = false, precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal maxDailyBudgetIncreaseRatio = BigDecimal.ZERO;

    @Column(name = "adjustment_cooldown_hours", nullable = false)
    @Builder.Default
    private Integer adjustmentCooldownHours = 0;

    // ----- explore budget band -----

    @Column(name = "explore_budget_ratio_min", nullable = false, precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal exploreBudgetRatioMin = BigDecimal.ZERO;

    @Column(name = "explore_budget_ratio_max", nullable = false, precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal exploreBudgetRatioMax = BigDecimal.ZERO;

    // ----- V3 keyword/negative control fields (Req 54.3) -----

    @Column(name = "max_new_keywords_per_run", nullable = false)
    @Builder.Default
    private Integer maxNewKeywordsPerRun = 0;

    @Column(name = "min_keyword_orders", nullable = false)
    @Builder.Default
    private Integer minKeywordOrders = 0;

    @Column(name = "negative_keyword_min_clicks", nullable = false)
    @Builder.Default
    private Integer negativeKeywordMinClicks = 0;

    /** In-effect rule version recorded on every AI Operation (Req 49.9). */
    @Column(name = "rule_version", nullable = false, length = 40)
    private String ruleVersion;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
