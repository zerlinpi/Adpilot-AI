package com.adpilot.modules.advertising.hosting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure, side-effect-free computation backing the hosting dashboard summary savings and the
 * historical analytics rates (Requirements 27.3, 29.2, 29.3, 29.4, 37.4).
 *
 * <p>This class holds no Spring dependencies and reads no ambient state so it can be unit- and
 * property-tested in isolation. It is the target of the estimated-savings selection property
 * (Property 39) and the analytics-rate consistency property (Property 40).</p>
 *
 * <h3>Estimated savings (Property 39)</h3>
 * Only {@code spend} attribution rows belonging to an operation whose ACoS improved and whose
 * {@code estimated_incremental_impact} on spend is negative are summed. The returned savings is the
 * non-negative magnitude of that (negative) sum so the dashboard shows a positive "estimate".
 *
 * <h3>Analytics rates (Property 40)</h3>
 * {@code success_rate = effective / attempted}; the per-engine breakdown partitions the totals; and
 * counts are computed over {@code ai_decisions} joined to {@code operations} where promoted (so
 * observe/recommend decisions still contribute to {@code total_decisions}).
 */
public final class HostingAnalyticsCalculator {

    /** Metric type recorded in {@code effect_attributions} for ACoS movement. */
    public static final String METRIC_ACOS = "acos";
    /** Metric type recorded in {@code effect_attributions} for spend movement. */
    public static final String METRIC_SPEND = "spend";
    /** Metric type recorded in {@code effect_attributions} for sales movement. */
    public static final String METRIC_SALES = "sales";

    /** Canonical engine identifiers used by the per-engine breakdown. */
    public static final List<String> ENGINES = List.of("v1_bid", "v2_budget", "v3_keyword");

    /**
     * SyncState machine values for which a promoted Operation counts as <em>attempted</em> — it was
     * actually submitted to (or settled on) the platform. Excludes pending/awaiting_approval (not
     * yet submitted) and cancelled/superseded/local-only (never reached the platform).
     */
    private static final Set<String> ATTEMPTED_SYNC_STATES = Set.of(
            "submitted", "amazon-processing", "effective", "failed",
            "reconciliation_required", "expired");

    /** SyncState machine value indicating the change is confirmed applied. */
    private static final String EFFECTIVE_SYNC_STATE = "effective";

    private static final int RATE_SCALE = 6;

    private HostingAnalyticsCalculator() {
        // Utility class — not instantiable.
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Estimated savings (Property 39)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Compute the non-negative estimated spend savings from a set of attribution rows.
     *
     * <p>An operation contributes only when (a) its ACoS improved — it has an {@code acos} row whose
     * {@code estimated_incremental_impact} is negative — and (b) it has {@code spend} rows whose
     * {@code estimated_incremental_impact} is negative. The qualifying spend impacts are summed (a
     * negative number) and the result is returned as its magnitude (Req 27.3, 29.3, Property 39).</p>
     *
     * @param attributions attribution rows (any mix of operations and metric types)
     * @return the non-negative estimated savings; {@link BigDecimal#ZERO} when nothing qualifies
     */
    public static BigDecimal estimatedSpendSavings(List<EffectAttributionEntity> attributions) {
        return signedQualifyingSpendImpact(attributions).negate();
    }

    /**
     * The signed sum of qualifying spend impacts (a non-positive number) used by
     * {@link #estimatedSpendSavings}. Exposed for property testing of the literal selection rule.
     *
     * @param attributions attribution rows
     * @return the sum of {@code estimated_incremental_impact} for spend rows that belong to an
     *         ACoS-improving operation and are themselves negative; {@code <= 0}
     */
    public static BigDecimal signedQualifyingSpendImpact(List<EffectAttributionEntity> attributions) {
        if (attributions == null || attributions.isEmpty()) {
            return BigDecimal.ZERO;
        }

        // Group by operation so the "ACoS improved" predicate is evaluated per operation.
        Map<UUID, Boolean> acosImprovedByOp = new LinkedHashMap<>();
        for (EffectAttributionEntity a : attributions) {
            if (a == null || a.getOperationId() == null) {
                continue;
            }
            if (METRIC_ACOS.equalsIgnoreCase(a.getMetricType()) && isNegative(a.getEstimatedIncrementalImpact())) {
                acosImprovedByOp.put(a.getOperationId(), Boolean.TRUE);
            } else {
                acosImprovedByOp.putIfAbsent(a.getOperationId(), Boolean.FALSE);
            }
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (EffectAttributionEntity a : attributions) {
            if (a == null || a.getOperationId() == null) {
                continue;
            }
            if (!METRIC_SPEND.equalsIgnoreCase(a.getMetricType())) {
                continue;
            }
            BigDecimal impact = a.getEstimatedIncrementalImpact();
            if (!isNegative(impact)) {
                continue;
            }
            if (Boolean.TRUE.equals(acosImprovedByOp.get(a.getOperationId()))) {
                sum = sum.add(impact);
            }
        }
        return sum;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Analytics rates (Property 40)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * A single decision joined to its promoted Operation (when promoted), the minimal projection
     * needed to compute analytics rates.
     *
     * @param engine            producing engine (v1_bid/v2_budget/v3_keyword); null tolerated
     * @param executionMode     resolved execution mode machine value; null tolerated
     * @param riskScore         risk score in [0,1]; null tolerated (ignored in the average)
     * @param promoted          whether the decision was promoted to an Operation
     * @param operationSyncState the promoted Operation's SyncState machine value; null when not promoted
     * @param failureReason     status reason when the Operation failed; null otherwise
     */
    public record DecisionOutcome(
            String engine,
            String executionMode,
            BigDecimal riskScore,
            boolean promoted,
            String operationSyncState,
            String failureReason) {
    }

    /**
     * Aggregated analytics result.
     */
    public record RateResult(
            long totalDecisions,
            long autoExecutedCount,
            long approvalRequiredCount,
            long attemptedCount,
            long effectiveCount,
            BigDecimal successRate,
            BigDecimal averageRiskScore,
            List<ReasonCount> topFailureReasons,
            List<EngineRate> perEngine) {
    }

    /** A failure reason paired with its occurrence count. */
    public record ReasonCount(String reason, long count) {
    }

    /** Per-engine rate partition of the totals. */
    public record EngineRate(
            String engine,
            long totalDecisions,
            long attemptedCount,
            long effectiveCount,
            BigDecimal successRate) {
    }

    /**
     * Compute analytics rates over the given decisions. {@code success_rate = effective/attempted}
     * and the per-engine breakdown partitions the totals (Property 40).
     *
     * @param decisions decisions joined to their promoted operations (may be empty, never null)
     * @return the aggregated {@link RateResult}
     */
    public static RateResult computeRates(List<DecisionOutcome> decisions) {
        List<DecisionOutcome> safe = decisions == null ? List.of() : decisions;

        long total = safe.size();
        long autoExecuted = 0;
        long approvalRequired = 0;
        long attempted = 0;
        long effective = 0;

        BigDecimal riskSum = BigDecimal.ZERO;
        long riskCount = 0;

        Map<String, Long> failureReasonCounts = new LinkedHashMap<>();

        // Per-engine accumulators (ordered by canonical engine list, plus any others encountered).
        Map<String, long[]> engineCounts = new LinkedHashMap<>();
        for (String e : ENGINES) {
            engineCounts.put(e, new long[3]); // [total, attempted, effective]
        }

        for (DecisionOutcome d : safe) {
            if (d == null) {
                continue;
            }
            if (ExecutionMode.AUTO_EXECUTE.value().equals(d.executionMode())) {
                autoExecuted++;
            }
            if (ExecutionMode.APPROVAL_REQUIRED.value().equals(d.executionMode())) {
                approvalRequired++;
            }
            if (d.riskScore() != null) {
                riskSum = riskSum.add(d.riskScore());
                riskCount++;
            }

            boolean isAttempted = d.promoted() && d.operationSyncState() != null
                    && ATTEMPTED_SYNC_STATES.contains(d.operationSyncState());
            boolean isEffective = d.promoted()
                    && EFFECTIVE_SYNC_STATE.equals(d.operationSyncState());
            if (isAttempted) {
                attempted++;
            }
            if (isEffective) {
                effective++;
            }
            if (isAttempted && "failed".equals(d.operationSyncState())) {
                String reason = normalizeReason(d.failureReason());
                failureReasonCounts.merge(reason, 1L, Long::sum);
            }

            String engineKey = d.engine() == null ? "unknown" : d.engine();
            long[] acc = engineCounts.computeIfAbsent(engineKey, k -> new long[3]);
            acc[0]++;
            if (isAttempted) {
                acc[1]++;
            }
            if (isEffective) {
                acc[2]++;
            }
        }

        BigDecimal successRate = ratio(effective, attempted);
        BigDecimal avgRisk = riskCount == 0
                ? BigDecimal.ZERO
                : riskSum.divide(BigDecimal.valueOf(riskCount), RATE_SCALE, RoundingMode.HALF_UP);

        List<ReasonCount> topReasons = new ArrayList<>();
        failureReasonCounts.forEach((reason, count) -> topReasons.add(new ReasonCount(reason, count)));
        topReasons.sort(Comparator.comparingLong(ReasonCount::count).reversed()
                .thenComparing(ReasonCount::reason));

        List<EngineRate> perEngine = new ArrayList<>();
        engineCounts.forEach((engine, acc) -> {
            if (acc[0] == 0 && !ENGINES.contains(engine)) {
                return; // skip non-canonical engines with no data
            }
            perEngine.add(new EngineRate(engine, acc[0], acc[1], acc[2], ratio(acc[2], acc[1])));
        });

        return new RateResult(total, autoExecuted, approvalRequired, attempted, effective,
                successRate, avgRisk, topReasons, perEngine);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static boolean isNegative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "UNKNOWN";
        }
        return reason.trim();
    }
}
