package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.service.HostingAdjustmentType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A candidate decision produced by an optimization engine (V1/V2/V3) against
 * the shared immutable {@link DataSnapshot}.
 *
 * <p>Engines produce these candidates and hand them to the
 * {@link OptimizationCoordinator}, which de-conflicts, prioritizes, clips to
 * safety boundaries, and routes survivors to the {@link DecisionRoutingPipeline}.
 *
 * <p>This record carries all information the coordinator needs to perform
 * conflict detection (entity/field), cross-engine interaction modeling
 * (adjustmentType + proposed values), risk prioritization (riskScore),
 * and safety-boundary clipping (proposedValue + boundary limits).
 *
 * <p>Validates: Requirements 23.2, 23.3, 23.4, 36.3.</p>
 *
 * @param candidateId       unique identifier for this candidate decision
 * @param storeId           the store this candidate belongs to
 * @param campaignId        the campaign the decision targets
 * @param entityType        the entity type being changed (e.g., "keyword", "campaign")
 * @param entityId          the entity being changed
 * @param field             the field being changed (e.g., "bid", "daily_budget")
 * @param adjustmentType    the type of adjustment (BID, BUDGET, KEYWORD, NEGATIVE)
 * @param changeType        the wire change type (e.g., "bid", "budget", "state",
 *                          "keyword", "negative_keyword")
 * @param engineType        the engine that produced this candidate (V1_BID, V2_BUDGET, V3_KEYWORD)
 * @param beforeValue       the current value before the proposed change
 * @param proposedValue     the proposed new value (before safety-boundary clipping)
 * @param riskScore         the computed risk score ∈ [0.0, 1.0]
 * @param riskThreshold     the store's auto-execute threshold
 * @param dataConfidence    data confidence level for decision explanation
 * @param decisionSnapshot  the immutable decision snapshot JSON (Requirement 34)
 */
public record CandidateDecision(
        UUID candidateId,
        UUID storeId,
        UUID campaignId,
        String entityType,
        UUID entityId,
        String field,
        HostingAdjustmentType adjustmentType,
        String changeType,
        String engineType,
        BigDecimal beforeValue,
        BigDecimal proposedValue,
        BigDecimal riskScore,
        BigDecimal riskThreshold,
        BigDecimal dataConfidence,
        String decisionSnapshot
) {
    public CandidateDecision {
        if (candidateId == null) throw new IllegalArgumentException("candidateId must not be null");
        if (storeId == null) throw new IllegalArgumentException("storeId must not be null");
        if (campaignId == null) throw new IllegalArgumentException("campaignId must not be null");
        if (entityType == null || entityType.isBlank()) throw new IllegalArgumentException("entityType must not be blank");
        if (entityId == null) throw new IllegalArgumentException("entityId must not be null");
        if (field == null || field.isBlank()) throw new IllegalArgumentException("field must not be blank");
        if (adjustmentType == null) throw new IllegalArgumentException("adjustmentType must not be null");
        if (changeType == null || changeType.isBlank()) throw new IllegalArgumentException("changeType must not be blank");
        if (engineType == null || engineType.isBlank()) throw new IllegalArgumentException("engineType must not be blank");
        if (riskScore == null) throw new IllegalArgumentException("riskScore must not be null");
        if (riskThreshold == null) {
            riskThreshold = CandidateDecisionContext.DEFAULT_RISK_THRESHOLD;
        }
    }

    /** Engine type constants. */
    public static final String ENGINE_V1_BID = "V1_BID";
    public static final String ENGINE_V2_BUDGET = "V2_BUDGET";
    public static final String ENGINE_V3_KEYWORD = "V3_KEYWORD";

    /**
     * Returns whether this candidate decision represents a reversible operation,
     * as classified by the {@link ReversibilityClassifier}.
     *
     * <p>This is a convenience method for use when building
     * {@link com.adpilot.modules.advertising.operation.CreateOperationCommand} from
     * a coordinated candidate.
     *
     * <p>Validates: Requirements 5.11, 10.3.</p>
     *
     * @return {@code true} for bid/budget/state changes; {@code false} for keyword/negative additions
     */
    public boolean isReversible() {
        return ReversibilityClassifier.classify(changeType);
    }
}
