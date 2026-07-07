package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.hosting.EmergencyStopEvaluator.EmergencyStopResult;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EmergencyStopEvaluatorImpl}.
 *
 * Validates: Requirements 6.5, 25.1
 */
@DisplayName("EmergencyStopEvaluator")
class EmergencyStopEvaluatorTest {

    private final EmergencyStopEvaluator evaluator = new EmergencyStopEvaluatorImpl();

    private SafetyBoundary buildBoundary(BigDecimal spendMultiplier, BigDecimal acosMultiplier) {
        SafetyBoundaryLimits.Builder builder = SafetyBoundaryLimits.builder();
        if (spendMultiplier != null) {
            builder.emergencySpendToBudgetMultiplier(spendMultiplier);
        }
        if (acosMultiplier != null) {
            builder.emergencyAcosToTargetMultiplier(acosMultiplier);
        }
        return SafetyBoundaryResolver.resolve(null, null, builder.build(), null);
    }

    @Nested
    @DisplayName("Boolean OR condition")
    class BooleanOrCondition {

        @Test
        @DisplayName("Neither condition breached → not triggered")
        void neitherBreached() {
            SafetyBoundary boundary = buildBoundary(new BigDecimal("2.0"), new BigDecimal("3.0"));
            EmergencyStopResult result = evaluator.evaluate(
                    new BigDecimal("50"),   // dailySpend
                    new BigDecimal("100"),  // dailyBudget (threshold = 200)
                    new BigDecimal("0.20"), // actualAcos
                    new BigDecimal("0.25"), // targetAcos (threshold = 0.75)
                    boundary);

            assertThat(result.triggered()).isFalse();
            assertThat(result.spendBreached()).isFalse();
            assertThat(result.acosBreached()).isFalse();
        }

        @Test
        @DisplayName("Spend breached only → triggered")
        void spendBreachedOnly() {
            SafetyBoundary boundary = buildBoundary(new BigDecimal("2.0"), new BigDecimal("3.0"));
            EmergencyStopResult result = evaluator.evaluate(
                    new BigDecimal("250"),  // dailySpend > 2.0 * 100
                    new BigDecimal("100"),  // dailyBudget
                    new BigDecimal("0.20"), // actualAcos < 3.0 * 0.25
                    new BigDecimal("0.25"), // targetAcos
                    boundary);

            assertThat(result.triggered()).isTrue();
            assertThat(result.spendBreached()).isTrue();
            assertThat(result.acosBreached()).isFalse();
        }

        @Test
        @DisplayName("ACoS breached only → triggered")
        void acosBreachedOnly() {
            SafetyBoundary boundary = buildBoundary(new BigDecimal("2.0"), new BigDecimal("3.0"));
            EmergencyStopResult result = evaluator.evaluate(
                    new BigDecimal("50"),   // dailySpend < 2.0 * 100
                    new BigDecimal("100"),  // dailyBudget
                    new BigDecimal("0.80"), // actualAcos > 3.0 * 0.25 = 0.75
                    new BigDecimal("0.25"), // targetAcos
                    boundary);

            assertThat(result.triggered()).isTrue();
            assertThat(result.spendBreached()).isFalse();
            assertThat(result.acosBreached()).isTrue();
        }

        @Test
        @DisplayName("Both conditions breached → triggered with both flags")
        void bothBreached() {
            SafetyBoundary boundary = buildBoundary(new BigDecimal("2.0"), new BigDecimal("3.0"));
            EmergencyStopResult result = evaluator.evaluate(
                    new BigDecimal("250"),  // dailySpend > 2.0 * 100
                    new BigDecimal("100"),  // dailyBudget
                    new BigDecimal("0.80"), // actualAcos > 3.0 * 0.25 = 0.75
                    new BigDecimal("0.25"), // targetAcos
                    boundary);

            assertThat(result.triggered()).isTrue();
            assertThat(result.spendBreached()).isTrue();
            assertThat(result.acosBreached()).isTrue();
        }
    }

    @Nested
    @DisplayName("Boundary values at threshold")
    class AtThreshold {

        @Test
        @DisplayName("Spend exactly at threshold → not triggered (> required, not >=)")
        void spendExactlyAtThreshold() {
            SafetyBoundary boundary = buildBoundary(new BigDecimal("2.0"), new BigDecimal("3.0"));
            EmergencyStopResult result = evaluator.evaluate(
                    new BigDecimal("200"),  // dailySpend == 2.0 * 100 (not strictly greater)
                    new BigDecimal("100"),
                    new BigDecimal("0.20"),
                    new BigDecimal("0.25"),
                    boundary);

            assertThat(result.triggered()).isFalse();
        }

        @Test
        @DisplayName("ACoS exactly at threshold → not triggered (> required, not >=)")
        void acosExactlyAtThreshold() {
            SafetyBoundary boundary = buildBoundary(new BigDecimal("2.0"), new BigDecimal("3.0"));
            EmergencyStopResult result = evaluator.evaluate(
                    new BigDecimal("50"),
                    new BigDecimal("100"),
                    new BigDecimal("0.75"), // actualAcos == 3.0 * 0.25 (not strictly greater)
                    new BigDecimal("0.25"),
                    boundary);

            assertThat(result.triggered()).isFalse();
        }
    }

    @Nested
    @DisplayName("Null/missing inputs")
    class NullInputs {

        @Test
        @DisplayName("Null dailySpend → spend not breached")
        void nullDailySpend() {
            SafetyBoundary boundary = buildBoundary(new BigDecimal("2.0"), new BigDecimal("3.0"));
            EmergencyStopResult result = evaluator.evaluate(
                    null,                  // dailySpend unknown
                    new BigDecimal("100"),
                    new BigDecimal("0.20"),
                    new BigDecimal("0.25"),
                    boundary);

            assertThat(result.triggered()).isFalse();
            assertThat(result.spendBreached()).isFalse();
        }

        @Test
        @DisplayName("Null dailyBudget → spend not breached")
        void nullDailyBudget() {
            SafetyBoundary boundary = buildBoundary(new BigDecimal("2.0"), new BigDecimal("3.0"));
            EmergencyStopResult result = evaluator.evaluate(
                    new BigDecimal("250"),
                    null,                  // dailyBudget unknown
                    new BigDecimal("0.20"),
                    new BigDecimal("0.25"),
                    boundary);

            assertThat(result.triggered()).isFalse();
            assertThat(result.spendBreached()).isFalse();
        }

        @Test
        @DisplayName("Null targetAcos → acos not breached")
        void nullTargetAcos() {
            SafetyBoundary boundary = buildBoundary(new BigDecimal("2.0"), new BigDecimal("3.0"));
            EmergencyStopResult result = evaluator.evaluate(
                    new BigDecimal("50"),
                    new BigDecimal("100"),
                    new BigDecimal("0.80"),
                    null,                  // targetAcos unknown
                    boundary);

            assertThat(result.triggered()).isFalse();
            assertThat(result.acosBreached()).isFalse();
        }
    }

    @Nested
    @DisplayName("Default multipliers")
    class DefaultMultipliers {

        @Test
        @DisplayName("Uses default multipliers when not configured in boundary")
        void defaultMultipliersUsed() {
            // Empty boundary — defaults: spend=2.0, acos=3.0
            SafetyBoundary boundary = SafetyBoundaryResolver.resolve(
                    null, null, SafetyBoundaryLimits.empty(), null);
            EmergencyStopResult result = evaluator.evaluate(
                    new BigDecimal("250"),  // > 2.0 * 100
                    new BigDecimal("100"),
                    new BigDecimal("0.20"),
                    new BigDecimal("0.25"),
                    boundary);

            assertThat(result.triggered()).isTrue();
            assertThat(result.spendBreached()).isTrue();
        }
    }
}
