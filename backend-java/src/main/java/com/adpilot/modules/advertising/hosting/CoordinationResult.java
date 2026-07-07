package com.adpilot.modules.advertising.hosting;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The result of running the {@link OptimizationCoordinator} for a single campaign
 * within an optimization run.
 *
 * <p>Contains:
 * <ul>
 *   <li>The surviving candidates (clipped and routed through the pipeline).</li>
 *   <li>The skip reasons for candidates that were filtered out.</li>
 * </ul>
 *
 * <p>Validates: Requirements 23.3, 23.4.</p>
 */
public record CoordinationResult(
        List<CoordinatedCandidate> survivors,
        List<SkippedCandidate> skipped
) {
    public CoordinationResult {
        survivors = survivors == null ? Collections.emptyList() : Collections.unmodifiableList(survivors);
        skipped = skipped == null ? Collections.emptyList() : Collections.unmodifiableList(skipped);
    }

    /** Factory for an empty result (all candidates skipped or no candidates). */
    public static CoordinationResult empty() {
        return new CoordinationResult(Collections.emptyList(), Collections.emptyList());
    }

    /** The total number of candidates that were processed. */
    public int totalProcessed() {
        return survivors.size() + skipped.size();
    }

    /**
     * A candidate that survived coordination — clipped to safety boundaries and
     * routed through the {@link DecisionRoutingPipeline}.
     *
     * @param candidate      the original candidate decision
     * @param clippedValue   the proposed value after safety-boundary clipping
     * @param routingResult  the result from the DecisionRoutingPipeline
     */
    public record CoordinatedCandidate(
            CandidateDecision candidate,
            java.math.BigDecimal clippedValue,
            RoutingResult routingResult
    ) {
        public CoordinatedCandidate {
            Objects.requireNonNull(candidate, "candidate must not be null");
            Objects.requireNonNull(routingResult, "routingResult must not be null");
        }
    }

    /**
     * A candidate that was skipped during coordination.
     *
     * @param candidate  the original candidate decision
     * @param reason     the reason the candidate was skipped
     */
    public record SkippedCandidate(
            CandidateDecision candidate,
            String reason
    ) {
        public SkippedCandidate {
            Objects.requireNonNull(candidate, "candidate must not be null");
            if (reason == null || reason.isBlank()) {
                reason = "unknown";
            }
        }
    }
}
