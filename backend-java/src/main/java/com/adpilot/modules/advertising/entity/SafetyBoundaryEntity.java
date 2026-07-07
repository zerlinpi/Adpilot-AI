package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persistence entity for the {@code safety_boundaries} table (Req 6.7).
 *
 * <p>Each row represents a single boundary limit configured at a specific scope
 * (campaign, goal, store, organization, system). The typed value model stores
 * the limit's value in the column matching its {@code value_type}:
 * <ul>
 *   <li>{@code amount} → {@link #valueAmount} (e.g., monetary thresholds)</li>
 *   <li>{@code ratio}  → {@link #valueRatio}  (e.g., percentage limits)</li>
 *   <li>{@code integer} → {@link #valueInteger} (e.g., day counts, operation caps)</li>
 *   <li>{@code boolean} → {@link #valueBoolean} (e.g., emergency stop flag)</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("safety_boundaries")
@Table(name = "safety_boundaries")
public class SafetyBoundaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "org_id", columnDefinition = "char(36)")
    private UUID orgId;

    /** Scope level: campaign, goal, store, organization, system. */
    @Column(name = "scope", nullable = false, length = 20)
    private String scope;

    /** The ID within the scope (e.g., campaign ID, goal ID, store ID). Null for system scope. */
    @Column(name = "scope_id", columnDefinition = "char(36)")
    private UUID scopeId;

    /** The limit type name matching a {@link com.adpilot.modules.advertising.support.SafetyBoundaryLimit} value. */
    @Column(name = "limit_type", nullable = false, length = 60)
    private String limitType;

    /** The type of value stored: amount, ratio, integer, boolean. */
    @Column(name = "value_type", nullable = false, length = 12)
    private String valueType;

    /** Value column for amount-typed limits (monetary thresholds). */
    @Column(name = "value_amount", precision = 18, scale = 4)
    private BigDecimal valueAmount;

    /** Value column for ratio-typed limits (percentages/multipliers). */
    @Column(name = "value_ratio", precision = 10, scale = 6)
    private BigDecimal valueRatio;

    /** Value column for integer-typed limits (day counts, operation caps). */
    @Column(name = "value_integer")
    private Integer valueInteger;

    /** Value column for boolean-typed limits (emergency stop). */
    @Column(name = "value_boolean")
    private Boolean valueBoolean;

    /** Comparison semantics: upper_bound, lower_bound, boolean_or, set_intersection. */
    @Column(name = "comparison_semantics", nullable = false, length = 20)
    private String comparisonSemantics;

    /** Currency code when relevant (e.g., USD, CNY for amount-typed limits). */
    @Column(name = "currency", length = 10)
    private String currency;

    /** The user who created or last updated this boundary. */
    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ────────────────────────────────────────────────────────────────────────────
    // Typed value accessors
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Return the effective value as a BigDecimal regardless of underlying type.
     * For boolean limits, returns BigDecimal.ONE (true) or BigDecimal.ZERO (false).
     */
    public BigDecimal getEffectiveValue() {
        return switch (valueType) {
            case "amount" -> valueAmount;
            case "ratio" -> valueRatio;
            case "integer" -> valueInteger != null ? BigDecimal.valueOf(valueInteger) : null;
            case "boolean" -> valueBoolean != null && valueBoolean ? BigDecimal.ONE : BigDecimal.ZERO;
            default -> null;
        };
    }

    /**
     * Set the effective value from a BigDecimal, storing it in the appropriate
     * typed column based on this entity's {@link #valueType}.
     */
    public void setEffectiveValue(BigDecimal value) {
        switch (valueType) {
            case "amount" -> this.valueAmount = value;
            case "ratio" -> this.valueRatio = value;
            case "integer" -> this.valueInteger = value != null ? value.intValue() : null;
            case "boolean" -> this.valueBoolean = value != null && value.compareTo(BigDecimal.ZERO) != 0;
        }
    }
}
