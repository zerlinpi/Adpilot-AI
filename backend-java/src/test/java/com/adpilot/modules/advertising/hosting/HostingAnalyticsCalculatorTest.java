package com.adpilot.modules.advertising.hosting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HostingAnalyticsCalculator} — the pure estimated-savings selection
 * (Req 27.3, 29.3) and analytics-rate computation (Req 29.2, 29.4, 37.4).
 */
@DisplayName("HostingAnalyticsCalculator")
class HostingAnalyticsCalculatorTest {

    @Nested
    @DisplayName("estimatedSpendSavings")
    class EstimatedSavings {

        @Test
        @DisplayName("sums only spend rows of ACoS-improving operations with negative spend impact")
        void selectsQualifyingRowsOnly() {
            UUID op1 = UUID.randomUUID(); // acos improved, spend negative -> qualifies (10)
            UUID op2 = UUID.randomUUID(); // acos worsened, spend negative -> excluded
            UUID op3 = UUID.randomUUID(); // acos improved, spend positive -> excluded
            UUID op4 = UUID.randomUUID(); // acos improved, spend negative -> qualifies (7)

            List<EffectAttributionEntity> rows = List.of(
                    attribution(op1, "acos", new BigDecimal("-0.05")),
                    attribution(op1, "spend", new BigDecimal("-10.00")),
                    attribution(op2, "acos", new BigDecimal("0.02")),
                    attribution(op2, "spend", new BigDecimal("-5.00")),
                    attribution(op3, "acos", new BigDecimal("-0.01")),
                    attribution(op3, "spend", new BigDecimal("3.00")),
                    attribution(op4, "acos", new BigDecimal("-0.03")),
                    attribution(op4, "spend", new BigDecimal("-7.00")));

            BigDecimal savings = HostingAnalyticsCalculator.estimatedSpendSavings(rows);

            assertThat(savings).isEqualByComparingTo("17.00");
            assertThat(HostingAnalyticsCalculator.signedQualifyingSpendImpact(rows))
                    .isEqualByComparingTo("-17.00");
        }

        @Test
        @DisplayName("returns zero for empty or null input")
        void zeroForEmpty() {
            assertThat(HostingAnalyticsCalculator.estimatedSpendSavings(null))
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(HostingAnalyticsCalculator.estimatedSpendSavings(List.of()))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("ignores null impacts and missing acos rows")
        void ignoresNullsAndMissingAcos() {
            UUID op = UUID.randomUUID();
            List<EffectAttributionEntity> rows = List.of(
                    attribution(op, "spend", new BigDecimal("-9.00")), // no acos row -> not improving
                    attribution(op, "acos", null));                    // null impact -> not improving

            assertThat(HostingAnalyticsCalculator.estimatedSpendSavings(rows))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("computeRates")
    class ComputeRates {

        @Test
        @DisplayName("success_rate equals effective/attempted and per-engine partitions totals")
        void ratesAreConsistent() {
            List<HostingAnalyticsCalculator.DecisionOutcome> decisions = List.of(
                    outcome("v1_bid", "auto_execute", "0.2", true, "effective", null),
                    outcome("v1_bid", "auto_execute", "0.4", true, "failed", "DATA_STALE"),
                    outcome("v2_budget", "approval_required", "0.6", true, "submitted", null),
                    outcome("v3_keyword", "observe_only", "0.1", false, null, null),
                    outcome("v2_budget", "auto_execute", null, true, "pending", null));

            HostingAnalyticsCalculator.RateResult r = HostingAnalyticsCalculator.computeRates(decisions);

            assertThat(r.totalDecisions()).isEqualTo(5);
            assertThat(r.autoExecutedCount()).isEqualTo(3);
            assertThat(r.approvalRequiredCount()).isEqualTo(1);
            assertThat(r.attemptedCount()).isEqualTo(3);
            assertThat(r.effectiveCount()).isEqualTo(1);
            assertThat(r.successRate()).isEqualByComparingTo(
                    new BigDecimal("1").divide(new BigDecimal("3"), 6, java.math.RoundingMode.HALF_UP));
            assertThat(r.averageRiskScore()).isEqualByComparingTo("0.325");

            assertThat(r.topFailureReasons()).hasSize(1);
            assertThat(r.topFailureReasons().get(0).reason()).isEqualTo("DATA_STALE");
            assertThat(r.topFailureReasons().get(0).count()).isEqualTo(1);

            // Per-engine partitions the totals (sum of per-engine totals == overall total).
            long perEngineTotal = r.perEngine().stream()
                    .mapToLong(HostingAnalyticsCalculator.EngineRate::totalDecisions).sum();
            assertThat(perEngineTotal).isEqualTo(r.totalDecisions());

            HostingAnalyticsCalculator.EngineRate v1 = engine(r, "v1_bid");
            assertThat(v1.totalDecisions()).isEqualTo(2);
            assertThat(v1.attemptedCount()).isEqualTo(2);
            assertThat(v1.effectiveCount()).isEqualTo(1);
            assertThat(v1.successRate()).isEqualByComparingTo("0.5");

            HostingAnalyticsCalculator.EngineRate v2 = engine(r, "v2_budget");
            assertThat(v2.totalDecisions()).isEqualTo(2);
            assertThat(v2.attemptedCount()).isEqualTo(1);
            assertThat(v2.effectiveCount()).isZero();
            assertThat(v2.successRate()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("empty input yields zero rates and canonical engine rows")
        void emptyInput() {
            HostingAnalyticsCalculator.RateResult r = HostingAnalyticsCalculator.computeRates(List.of());
            assertThat(r.totalDecisions()).isZero();
            assertThat(r.successRate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(r.averageRiskScore()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(r.perEngine()).extracting(HostingAnalyticsCalculator.EngineRate::engine)
                    .containsExactly("v1_bid", "v2_budget", "v3_keyword");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static EffectAttributionEntity attribution(UUID operationId, String metricType, BigDecimal impact) {
        EffectAttributionEntity e = new EffectAttributionEntity();
        e.setOperationId(operationId);
        e.setStoreId(UUID.randomUUID());
        e.setMetricType(metricType);
        e.setEstimatedIncrementalImpact(impact);
        return e;
    }

    private static HostingAnalyticsCalculator.DecisionOutcome outcome(
            String engine, String mode, String risk, boolean promoted, String syncState, String reason) {
        return new HostingAnalyticsCalculator.DecisionOutcome(
                engine, mode, risk == null ? null : new BigDecimal(risk), promoted, syncState, reason);
    }

    private static HostingAnalyticsCalculator.EngineRate engine(
            HostingAnalyticsCalculator.RateResult r, String engine) {
        return r.perEngine().stream().filter(e -> e.engine().equals(engine)).findFirst().orElseThrow();
    }
}
