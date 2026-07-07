package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity mapping to the {@code ai_decisions} table (Req 37.1).
 *
 * <p>Every AI decision is persisted here regardless of Execution_Mode
 * (observe_only, recommend_only, approval_required, auto_execute).
 * When the decision is promoted to an Operation, the {@code promotedOperationId}
 * links to the corresponding {@code operations} row.
 *
 * <p>The {@code decisionSnapshot} column is an immutable JSON value object
 * (a {@link DecisionSnapshot}) written once at creation and never updated.
 * It is the sole source of truth for Decision_Explanation cards.
 *
 * <p>Validates: Requirements 34.1, 34.2, 34.3, 34.4, 37.1, 37.2, 37.3, 37.5.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("ai_decisions")
@Table(name = "ai_decisions")
public class AiDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    /** The optimization run that produced this decision; nullable for ad-hoc decisions. */
    @Column(name = "run_id", columnDefinition = "char(36)")
    private UUID runId;

    @Column(name = "campaign_id", columnDefinition = "char(36)")
    private UUID campaignId;

    /** Engine that produced this decision: v1_bid, v2_budget, v3_keyword. */
    @Column(name = "engine", nullable = false, length = 20)
    private String engine;

    /** The type of decision: bid_adjustment, budget_adjustment, keyword_addition, negative_keyword_addition. */
    @Column(name = "decision_type", nullable = false, length = 40)
    private String decisionType;

    /** The resolved execution mode at decision time. */
    @Column(name = "execution_mode", nullable = false, length = 20)
    private String executionMode;

    /** Risk score ∈ [0.0, 1.0] computed by {@link com.adpilot.modules.advertising.support.RiskScoreCalculator}. */
    @Column(name = "risk_score", nullable = false, precision = 6, scale = 5)
    @Builder.Default
    private BigDecimal riskScore = BigDecimal.ZERO;

    /** The routing outcome from the DecisionRoutingPipeline. */
    @Column(name = "routing_outcome", length = 30)
    private String routingOutcome;

    /** Link to the promoted Operation when one was created. */
    @Column(name = "promoted_operation_id", columnDefinition = "char(36)")
    private UUID promotedOperationId;

    /**
     * Immutable JSON representation of a {@link DecisionSnapshot}, written once at
     * creation and never updated. This is the sole source for Decision_Explanation cards.
     */
    @Column(name = "decision_snapshot", nullable = false, columnDefinition = "json")
    private String decisionSnapshot;

    /** When this decision expires (for pre-submission revalidation). */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
