package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.operation.InFlightConflictLock;
import com.adpilot.modules.advertising.service.HostingAdjustmentType;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Default implementation of the {@link OptimizationCoordinator} (Requirement 23).
 *
 * <p>Processes candidate decisions through the following pipeline:
 * <ol>
 *   <li><b>De-conflict</b> — skip candidates colliding with an unsettled Operation
 *       on the same entity/field (Req 23.4, 20.5, 36.5).</li>
 *   <li><b>Cross-engine interaction modeling</b> — a budget decrease constrains
 *       bid increases within the same campaign (Req 23.2).</li>
 *   <li><b>Prioritize by risk</b> — lowest-risk first (Req 23.3).</li>
 *   <li><b>Enforce operation caps</b> — maxOperationsPerRun and maxOperationsPerDay
 *       from safety boundaries (Req 23.3).</li>
 *   <li><b>Safety-boundary clipping</b> — clamp proposed values to the resolved
 *       boundary limits (Req 6.4).</li>
 *   <li><b>Route through pipeline</b> — hand survivors to the
 *       {@link DecisionRoutingPipeline} (Req 7.10).</li>
 * </ol>
 *
 * <p>Validates: Requirements 23.2, 23.3, 23.4, 20.5, 36.5.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptimizationCoordinatorImpl implements OptimizationCoordinator {

    static final String SKIP_IN_FLIGHT_CONFLICT = "IN_FLIGHT_CONFLICT";
    static final String SKIP_CROSS_ENGINE_CONSTRAINT = "CROSS_ENGINE_CONSTRAINT";
    static final String SKIP_MAX_OPERATIONS_PER_RUN = "MAX_OPERATIONS_PER_RUN";
    static final String SKIP_MAX_OPERATIONS_PER_DAY = "MAX_OPERATIONS_PER_DAY";
    static final String SKIP_CLIPPED_TO_CURRENT = "CLIPPED_TO_CURRENT_VALUE";

    /** Default max operations per run when not configured in safety boundaries. */
    private static final int DEFAULT_MAX_OPERATIONS_PER_RUN = 50;

    /** Default max operations per day when not configured in safety boundaries. */
    private static final int DEFAULT_MAX_OPERATIONS_PER_DAY = 200;

    private final InFlightConflictLock inFlightConflictLock;
    private final DecisionRoutingPipeline decisionRoutingPipeline;

    @Override
    public CoordinationResult coordinate(
            List<CandidateDecision> candidates,
            SafetyBoundary resolvedBoundary,
            UUID campaignId,
            UUID storeId,
            int operationsToday,
            boolean killSwitchActive,
            boolean shadowModeActive,
            boolean phaseEnabled,
            boolean canaryStoreAllowed,
            ExecutionMode executionMode) {

        if (candidates == null || candidates.isEmpty()) {
            return CoordinationResult.empty();
        }

        log.debug("Coordinating {} candidates for campaign={} store={}",
                candidates.size(), campaignId, storeId);

        List<CoordinationResult.SkippedCandidate> skipped = new ArrayList<>();
        List<CandidateDecision> surviving = new ArrayList<>();

        // --- Stage 1: De-conflict against unsettled operations ---
        for (CandidateDecision candidate : candidates) {
            if (hasInFlightConflict(candidate)) {
                log.debug("Candidate {} skipped: in-flight conflict on entity={} field={}",
                        candidate.candidateId(), candidate.entityId(), candidate.field());
                skipped.add(new CoordinationResult.SkippedCandidate(candidate, SKIP_IN_FLIGHT_CONFLICT));
            } else {
                surviving.add(candidate);
            }
        }

        // --- Stage 2: Model cross-engine interactions ---
        surviving = applyCrossEngineConstraints(surviving, skipped);

        // --- Stage 3: Prioritize by risk score (lowest-risk first) ---
        surviving.sort(Comparator.comparing(CandidateDecision::riskScore));

        // --- Stage 4: Enforce operation caps ---
        int maxPerRun = resolvedBoundary.get(SafetyBoundaryLimit.MAX_OPERATIONS_PER_RUN)
                .map(BigDecimal::intValue)
                .orElse(DEFAULT_MAX_OPERATIONS_PER_RUN);
        int maxPerDay = resolvedBoundary.get(SafetyBoundaryLimit.MAX_OPERATIONS_PER_DAY)
                .map(BigDecimal::intValue)
                .orElse(DEFAULT_MAX_OPERATIONS_PER_DAY);

        int remainingDayBudget = Math.max(0, maxPerDay - operationsToday);
        int effectiveMax = Math.min(maxPerRun, remainingDayBudget);

        if (surviving.size() > effectiveMax) {
            List<CandidateDecision> excess = surviving.subList(effectiveMax, surviving.size());
            for (CandidateDecision candidate : excess) {
                String reason = (effectiveMax == remainingDayBudget)
                        ? SKIP_MAX_OPERATIONS_PER_DAY
                        : SKIP_MAX_OPERATIONS_PER_RUN;
                skipped.add(new CoordinationResult.SkippedCandidate(candidate, reason));
            }
            surviving = new ArrayList<>(surviving.subList(0, effectiveMax));
        }

        // --- Stage 5 & 6: Safety-boundary clipping + route through pipeline ---
        List<CoordinationResult.CoordinatedCandidate> coordinated = new ArrayList<>();
        for (CandidateDecision candidate : surviving) {
            BigDecimal clippedValue = clipToSafetyBoundary(candidate, resolvedBoundary);

            // If clipping collapsed the change to a no-op, skip it
            if (clippedValue != null && candidate.beforeValue() != null
                    && clippedValue.compareTo(candidate.beforeValue()) == 0) {
                log.debug("Candidate {} skipped: clipping reduced proposed value to current value",
                        candidate.candidateId());
                skipped.add(new CoordinationResult.SkippedCandidate(candidate, SKIP_CLIPPED_TO_CURRENT));
                continue;
            }

            // Build the routing context
            BigDecimal budgetDecreaseRatio = computeBudgetDecreaseRatio(candidate, clippedValue);
            BigDecimal maxBudgetDecreaseRatio = resolvedBoundary
                    .get(SafetyBoundaryLimit.MAX_DAILY_BUDGET_DECREASE_RATIO)
                    .orElse(CandidateDecisionContext.DEFAULT_MAX_BUDGET_DECREASE_RATIO);

            CandidateDecisionContext routingContext = new CandidateDecisionContext(
                    storeId,
                    campaignId,
                    candidate.adjustmentType(),
                    candidate.changeType(),
                    killSwitchActive,
                    shadowModeActive,
                    phaseEnabled,
                    canaryStoreAllowed,
                    executionMode,
                    candidate.riskScore(),
                    candidate.riskThreshold(),
                    budgetDecreaseRatio,
                    maxBudgetDecreaseRatio
            );

            RoutingResult routingResult = decisionRoutingPipeline.route(routingContext);
            coordinated.add(new CoordinationResult.CoordinatedCandidate(
                    candidate, clippedValue, routingResult));
        }

        log.debug("Coordination complete for campaign={}: {} survivors, {} skipped",
                campaignId, coordinated.size(), skipped.size());

        return new CoordinationResult(coordinated, skipped);
    }

    /**
     * Check if a candidate decision conflicts with an existing unsettled Operation
     * on the same entity/field.
     */
    private boolean hasInFlightConflict(CandidateDecision candidate) {
        return inFlightConflictLock.hasInFlightOperation(
                candidate.entityType(),
                candidate.entityId(),
                candidate.field()
        );
    }

    /**
     * Model cross-engine interactions (Req 23.2).
     *
     * <p>Current rule: if there is a budget DECREASE candidate for a campaign,
     * then bid INCREASE candidates for the same campaign are constrained
     * (skipped). The reasoning is that increasing bids while decreasing budget
     * is contradictory and wasteful.
     */
    private List<CandidateDecision> applyCrossEngineConstraints(
            List<CandidateDecision> candidates,
            List<CoordinationResult.SkippedCandidate> skipped) {

        // Detect if any budget decrease candidates exist per campaign
        boolean hasBudgetDecrease = candidates.stream()
                .anyMatch(c -> c.adjustmentType() == HostingAdjustmentType.BUDGET
                        && c.beforeValue() != null
                        && c.proposedValue() != null
                        && c.proposedValue().compareTo(c.beforeValue()) < 0);

        if (!hasBudgetDecrease) {
            return candidates;
        }

        List<CandidateDecision> result = new ArrayList<>();
        for (CandidateDecision candidate : candidates) {
            if (isBidIncrease(candidate) && hasBudgetDecrease) {
                log.debug("Candidate {} skipped: bid increase constrained by budget decrease",
                        candidate.candidateId());
                skipped.add(new CoordinationResult.SkippedCandidate(
                        candidate, SKIP_CROSS_ENGINE_CONSTRAINT));
            } else {
                result.add(candidate);
            }
        }
        return result;
    }

    /**
     * Whether a candidate represents a bid increase.
     */
    private boolean isBidIncrease(CandidateDecision candidate) {
        return candidate.adjustmentType() == HostingAdjustmentType.BID
                && candidate.beforeValue() != null
                && candidate.proposedValue() != null
                && candidate.proposedValue().compareTo(candidate.beforeValue()) > 0;
    }

    /**
     * Apply safety-boundary clipping to a candidate's proposed value.
     *
     * <p>Clamps the proposed value to the resolved boundary limits based on
     * the adjustment type:
     * <ul>
     *   <li>BID: clamp to [minBid, min(maxBid, maxCpc)]; apply maxBidAdjustmentRatio</li>
     *   <li>BUDGET: clamp to [minDailyBudget, maxDailyBudget]; apply increase/decrease ratios</li>
     *   <li>KEYWORD/NEGATIVE: no numeric clipping (these are additions, not value changes)</li>
     * </ul>
     *
     * @return the clipped value, or the original proposedValue if no clipping applies
     */
    private BigDecimal clipToSafetyBoundary(CandidateDecision candidate, SafetyBoundary boundary) {
        BigDecimal proposed = candidate.proposedValue();
        if (proposed == null) {
            return null; // keyword/negative additions have no numeric value to clip
        }

        return switch (candidate.adjustmentType()) {
            case BID -> clipBid(candidate, boundary, proposed);
            case BUDGET -> clipBudget(candidate, boundary, proposed);
            case KEYWORD, NEGATIVE -> proposed; // no numeric clipping for additions
        };
    }

    /**
     * Clip a bid value to [minBid, min(maxBid, maxCpc)] and apply
     * maxBidAdjustmentRatio relative to the before value.
     */
    private BigDecimal clipBid(CandidateDecision candidate, SafetyBoundary boundary, BigDecimal proposed) {
        BigDecimal result = proposed;

        // Apply absolute floor
        Optional<BigDecimal> minBid = boundary.get(SafetyBoundaryLimit.MIN_BID);
        if (minBid.isPresent() && result.compareTo(minBid.get()) < 0) {
            result = minBid.get();
        }

        // Apply absolute ceiling: min(maxBid, maxCpc)
        Optional<BigDecimal> maxBid = boundary.get(SafetyBoundaryLimit.MAX_BID);
        Optional<BigDecimal> maxCpc = boundary.get(SafetyBoundaryLimit.MAX_CPC);
        BigDecimal ceiling = null;
        if (maxBid.isPresent() && maxCpc.isPresent()) {
            ceiling = maxBid.get().min(maxCpc.get());
        } else if (maxBid.isPresent()) {
            ceiling = maxBid.get();
        } else if (maxCpc.isPresent()) {
            ceiling = maxCpc.get();
        }
        if (ceiling != null && result.compareTo(ceiling) > 0) {
            result = ceiling;
        }

        // Apply maxBidAdjustmentRatio (relative to before value)
        BigDecimal beforeValue = candidate.beforeValue();
        Optional<BigDecimal> maxAdjRatio = boundary.get(SafetyBoundaryLimit.MAX_BID_ADJUSTMENT_RATIO);
        if (beforeValue != null && maxAdjRatio.isPresent()
                && beforeValue.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal maxChange = beforeValue.multiply(maxAdjRatio.get());
            BigDecimal maxAllowed = beforeValue.add(maxChange);
            BigDecimal minAllowed = beforeValue.subtract(maxChange);
            if (minAllowed.compareTo(BigDecimal.ZERO) < 0) {
                minAllowed = BigDecimal.ZERO;
            }
            if (result.compareTo(maxAllowed) > 0) {
                result = maxAllowed;
            }
            if (result.compareTo(minAllowed) < 0) {
                result = minAllowed;
            }
        }

        return result.setScale(Math.max(result.scale(), 2), RoundingMode.HALF_UP);
    }

    /**
     * Clip a budget value to [minDailyBudget, maxDailyBudget] and apply
     * increase/decrease ratio constraints relative to the before value.
     */
    private BigDecimal clipBudget(CandidateDecision candidate, SafetyBoundary boundary, BigDecimal proposed) {
        BigDecimal result = proposed;

        // Apply absolute floor
        Optional<BigDecimal> minBudget = boundary.get(SafetyBoundaryLimit.MIN_DAILY_BUDGET);
        if (minBudget.isPresent() && result.compareTo(minBudget.get()) < 0) {
            result = minBudget.get();
        }

        // Apply absolute ceiling
        Optional<BigDecimal> maxBudget = boundary.get(SafetyBoundaryLimit.MAX_DAILY_BUDGET);
        if (maxBudget.isPresent() && result.compareTo(maxBudget.get()) > 0) {
            result = maxBudget.get();
        }

        // Apply increase/decrease ratio constraints relative to before value
        BigDecimal beforeValue = candidate.beforeValue();
        if (beforeValue != null && beforeValue.compareTo(BigDecimal.ZERO) > 0) {
            if (result.compareTo(beforeValue) > 0) {
                // Budget increase — apply maxDailyBudgetIncreaseRatio
                Optional<BigDecimal> maxIncRatio = boundary.get(
                        SafetyBoundaryLimit.MAX_DAILY_BUDGET_INCREASE_RATIO);
                if (maxIncRatio.isPresent()) {
                    BigDecimal maxIncrease = beforeValue.multiply(maxIncRatio.get());
                    BigDecimal maxAllowed = beforeValue.add(maxIncrease);
                    if (result.compareTo(maxAllowed) > 0) {
                        result = maxAllowed;
                    }
                }
            } else if (result.compareTo(beforeValue) < 0) {
                // Budget decrease — apply maxDailyBudgetDecreaseRatio
                Optional<BigDecimal> maxDecRatio = boundary.get(
                        SafetyBoundaryLimit.MAX_DAILY_BUDGET_DECREASE_RATIO);
                if (maxDecRatio.isPresent()) {
                    BigDecimal maxDecrease = beforeValue.multiply(maxDecRatio.get());
                    BigDecimal minAllowed = beforeValue.subtract(maxDecrease);
                    if (minAllowed.compareTo(BigDecimal.ZERO) < 0) {
                        minAllowed = BigDecimal.ZERO;
                    }
                    if (result.compareTo(minAllowed) < 0) {
                        result = minAllowed;
                    }
                }
            }
        }

        return result.setScale(Math.max(result.scale(), 2), RoundingMode.HALF_UP);
    }

    /**
     * Compute the budget decrease ratio for a candidate, used by the
     * HighRiskClassifier in the routing pipeline.
     *
     * @return the decrease ratio (0.0–1.0), or null/zero if not a budget decrease
     */
    private BigDecimal computeBudgetDecreaseRatio(CandidateDecision candidate, BigDecimal clippedValue) {
        if (candidate.adjustmentType() != HostingAdjustmentType.BUDGET) {
            return BigDecimal.ZERO;
        }
        BigDecimal before = candidate.beforeValue();
        if (before == null || before.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (clippedValue == null || clippedValue.compareTo(before) >= 0) {
            return BigDecimal.ZERO; // not a decrease
        }
        // ratio = (before - clipped) / before
        return before.subtract(clippedValue)
                .divide(before, 6, RoundingMode.HALF_UP);
    }
}
