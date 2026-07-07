package com.adpilot.modules.automation.entity;

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
 * An automation rule with bid bounds (Req 13.2), backed by the
 * {@code automation_rules} table (V11). Distinct from
 * {@link AutomationPolicyEntity}, which holds org/store-wide risk guardrails.
 *
 * <p>The {@code AutomationRunner} evaluates enabled rules on each scheduled run:
 * <ul>
 *   <li>{@code rule_type = "bid_adjustment"}: when the condition is met, compute
 *       an adjusted bid clamped within {@code [minBid, maxBid]} and submit it to
 *       the live platform (Req 13.2.2, 13.2.5).</li>
 *   <li>{@code rule_type = "negative_keyword"}: when the condition is met, add
 *       the qualifying search term as a negative keyword and submit it
 *       (Req 13.2.3).</li>
 * </ul>
 *
 * <p>{@code conditionJson} holds the rule's evaluation parameters as raw JSON.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("automation_rules")
@Table(name = "automation_rules")
public class AutomationRuleEntity {

    /** Bid-adjustment rule type. */
    public static final String TYPE_BID_ADJUSTMENT = "bid_adjustment";
    /** Negative-keyword rule type. */
    public static final String TYPE_NEGATIVE_KEYWORD = "negative_keyword";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "rule_type", nullable = false, length = 50)
    private String ruleType;

    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "condition_json", columnDefinition = "json")
    private String conditionJson;

    @Column(name = "min_bid", precision = 18, scale = 4)
    private BigDecimal minBid;

    @Column(name = "max_bid", precision = 18, scale = 4)
    private BigDecimal maxBid;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
