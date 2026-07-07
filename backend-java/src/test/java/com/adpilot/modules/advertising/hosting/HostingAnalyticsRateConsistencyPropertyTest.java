package com.adpilot.modules.advertising.hosting;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the consistency of the hosting analytics rates produced by
 * {@link HostingAnalyticsCalculator#computeRates(List)}.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 40: Analytics rates are consistent.
 *
 * <p>Validates: Requirements 29.2, 29.4, 37.4.
 *
 * <p>For any set of decisions, {@code success_rate} equals effective divided by attempted (and is
 * {@code 0} when nothing was attempted), the per-engine breakdown partitions the totals, and every
 * count is non-negative and ordered by the natural containment effective &le; attempted &le; total.
 * The decisions are computed over {@code ai_decisions} joined to operations where promoted, so
 * observe/recommend (non-promoted) decisions still contribute to {@code total_decisions} but never
 * to {@code attempted} or {@code effective}.
 */
class HostingAnalyticsRateConsistencyPropertyTest {

    private static final int RATE_SCALE = 6;

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 40: Analytics rates are consistent.
     *
     * <p>Validates: Requirements 29.2, 29.4, 37.4.
     *
     * <p>{@code success_rate} always equals {@code effective / attempted} (or {@code 0} when
     * {@code attempted == 0}) and always falls within {@code [0, 1]}.
     */
    @Property(tries = 300)
    void successRateEqualsEffectiveOverAttemptedAndIsWithinUnitInterval(
            @ForAll("decisions") List<HostingAnalyticsCalculator.DecisionOutcome> decisions) {

        HostingAnalyticsCalculator.RateResult r = HostingAnalyticsCalculator.computeRates(decisions);

        BigDecimal expected = r.attemptedCount() == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(r.effectiveCount())
                        .divide(BigDecimal.valueOf(r.attemptedCount()), RATE_SCALE, RoundingMode.HALF_UP);

        assertThat(r.successRate()).isEqualByComparingTo(expected);
        assertThat(r.successRate()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 40: Analytics rates are consistent.
     *
     * <p>Validates: Requirements 29.2, 29.4, 37.4.
     *
     * <p>The counts respect natural containment: {@code 0 <= effective <= attempted <= total}, and
     * total equals the number of decisions supplied.
     */
    @Property(tries = 300)
    void countsAreNonNegativeAndNested(
            @ForAll("decisions") List<HostingAnalyticsCalculator.DecisionOutcome> decisions) {

        HostingAnalyticsCalculator.RateResult r = HostingAnalyticsCalculator.computeRates(decisions);

        assertThat(r.totalDecisions()).isEqualTo(decisions.size());
        assertThat(r.effectiveCount()).isGreaterThanOrEqualTo(0);
        assertThat(r.attemptedCount()).isGreaterThanOrEqualTo(0);
        assertThat(r.effectiveCount()).isLessThanOrEqualTo(r.attemptedCount());
        assertThat(r.attemptedCount()).isLessThanOrEqualTo(r.totalDecisions());
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 40: Analytics rates are consistent.
     *
     * <p>Validates: Requirements 29.2, 29.4, 37.4.
     *
     * <p>The per-engine breakdown partitions the overall totals: summing each per-engine
     * {@code totalDecisions}/{@code attemptedCount}/{@code effectiveCount} reproduces the overall
     * figure exactly, and each per-engine success rate equals its own effective/attempted.
     */
    @Property(tries = 300)
    void perEngineBreakdownPartitionsTheTotals(
            @ForAll("decisions") List<HostingAnalyticsCalculator.DecisionOutcome> decisions) {

        HostingAnalyticsCalculator.RateResult r = HostingAnalyticsCalculator.computeRates(decisions);

        long engineTotal = r.perEngine().stream()
                .mapToLong(HostingAnalyticsCalculator.EngineRate::totalDecisions).sum();
        long engineAttempted = r.perEngine().stream()
                .mapToLong(HostingAnalyticsCalculator.EngineRate::attemptedCount).sum();
        long engineEffective = r.perEngine().stream()
                .mapToLong(HostingAnalyticsCalculator.EngineRate::effectiveCount).sum();

        assertThat(engineTotal).isEqualTo(r.totalDecisions());
        assertThat(engineAttempted).isEqualTo(r.attemptedCount());
        assertThat(engineEffective).isEqualTo(r.effectiveCount());

        for (HostingAnalyticsCalculator.EngineRate e : r.perEngine()) {
            assertThat(e.effectiveCount()).isGreaterThanOrEqualTo(0);
            assertThat(e.attemptedCount()).isGreaterThanOrEqualTo(0);
            assertThat(e.effectiveCount()).isLessThanOrEqualTo(e.attemptedCount());
            assertThat(e.attemptedCount()).isLessThanOrEqualTo(e.totalDecisions());

            BigDecimal expected = e.attemptedCount() == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(e.effectiveCount())
                            .divide(BigDecimal.valueOf(e.attemptedCount()), RATE_SCALE, RoundingMode.HALF_UP);
            assertThat(e.successRate()).isEqualByComparingTo(expected);
            assertThat(e.successRate()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        }
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 40: Analytics rates are consistent.
     *
     * <p>Validates: Requirements 29.2, 29.4, 37.4.
     *
     * <p>The mode tallies are non-negative and never over-count the decisions: the auto-executed and
     * approval-required counts each stay within the total and together never exceed it (they are
     * mutually exclusive execution modes).
     */
    @Property(tries = 300)
    void modeCountsAreNonNegativeAndBoundedByTotal(
            @ForAll("decisions") List<HostingAnalyticsCalculator.DecisionOutcome> decisions) {

        HostingAnalyticsCalculator.RateResult r = HostingAnalyticsCalculator.computeRates(decisions);

        assertThat(r.autoExecutedCount()).isGreaterThanOrEqualTo(0);
        assertThat(r.approvalRequiredCount()).isGreaterThanOrEqualTo(0);
        assertThat(r.autoExecutedCount() + r.approvalRequiredCount())
                .isLessThanOrEqualTo(r.totalDecisions());
    }

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 40: Analytics rates are consistent.
     *
     * <p>Validates: Requirements 29.2, 29.4, 37.4.
     *
     * <p>When every supplied risk score lies in {@code [0, 1]}, the reported average risk score also
     * lies in {@code [0, 1]}.
     */
    @Property(tries = 300)
    void averageRiskScoreStaysWithinUnitInterval(
            @ForAll("decisions") List<HostingAnalyticsCalculator.DecisionOutcome> decisions) {

        HostingAnalyticsCalculator.RateResult r = HostingAnalyticsCalculator.computeRates(decisions);

        assertThat(r.averageRiskScore()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
    }

    // ── generators ─────────────────────────────────────────────────────────────

    /** Engines: the three canonical ids plus {@code null} and an unrecognized id, to exercise the
     *  fallback ("unknown") engine bucket while still partitioning the totals. */
    @Provide
    Arbitrary<String> engines() {
        return Arbitraries.of("v1_bid", "v2_budget", "v3_keyword", "mystery_engine", null);
    }

    /** Execution modes: the four enum values plus {@code null} for unconfigured. */
    @Provide
    Arbitrary<String> executionModes() {
        return Arbitraries.of(
                ExecutionMode.OBSERVE_ONLY.value(),
                ExecutionMode.RECOMMEND_ONLY.value(),
                ExecutionMode.APPROVAL_REQUIRED.value(),
                ExecutionMode.AUTO_EXECUTE.value(),
                null);
    }

    /**
     * Sync states spanning attempted (submitted/processing/effective/failed/...), not-yet-attempted
     * (pending/awaiting_approval), never-reached-platform (cancelled/superseded/local), and
     * {@code null}.
     */
    @Provide
    Arbitrary<String> syncStates() {
        return Arbitraries.of(
                "submitted", "amazon-processing", "effective", "failed",
                "reconciliation_required", "expired",
                "pending", "awaiting_approval", "cancelled", "superseded", "local", null);
    }

    /** Risk scores constrained to {@code [0, 1]} with 6-decimal scale, plus {@code null}. */
    @Provide
    Arbitrary<BigDecimal> riskScores() {
        Arbitrary<BigDecimal> inUnit = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, BigDecimal.ONE)
                .ofScale(6);
        return Arbitraries.oneOf(inUnit, Arbitraries.just(null));
    }

    /** Failure reasons: a few realistic codes, blanks, and {@code null}. */
    @Provide
    Arbitrary<String> failureReasons() {
        return Arbitraries.of("DATA_STALE", "RATE_LIMIT", "VALIDATION_FAILED", "  ", "", null);
    }

    @Provide
    Arbitrary<HostingAnalyticsCalculator.DecisionOutcome> decision() {
        return Combinators.combine(
                        engines(),
                        executionModes(),
                        riskScores(),
                        Arbitraries.of(true, false),
                        syncStates(),
                        failureReasons())
                .as(HostingAnalyticsCalculator.DecisionOutcome::new);
    }

    @Provide
    Arbitrary<List<HostingAnalyticsCalculator.DecisionOutcome>> decisions() {
        return decision().list().ofMinSize(0).ofMaxSize(40);
    }
}
