package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.hosting.InventorySafetyEvaluator.InventoryLevel;
import com.adpilot.modules.advertising.hosting.InventorySafetyEvaluator.InventorySafetyResult;
import com.adpilot.modules.advertising.hosting.InventorySafetyEvaluator.RequiredAction;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InventorySafetyEvaluatorImpl}.
 *
 * Validates: Requirements 4.4, 25.1, 25.2, 25.3, 25.5
 */
@DisplayName("InventorySafetyEvaluator")
class InventorySafetyEvaluatorTest {

    private final InventorySafetyEvaluator evaluator = new InventorySafetyEvaluatorImpl();

    private SafetyBoundary buildBoundary(int safetyDays, int criticalDays) {
        SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                .inventorySafetyDays(BigDecimal.valueOf(safetyDays))
                .inventoryCriticalDays(BigDecimal.valueOf(criticalDays))
                .build();
        return SafetyBoundaryResolver.resolve(null, null, limits, null);
    }

    @Nested
    @DisplayName("Healthy inventory (above safety threshold)")
    class HealthyInventory {

        @Test
        @DisplayName("Inventory well above safety threshold → HEALTHY, no restriction")
        void wellAboveSafety() {
            SafetyBoundary boundary = buildBoundary(7, 3);
            InventorySafetyResult result = evaluator.evaluate(30, boundary);

            assertThat(result.level()).isEqualTo(InventoryLevel.HEALTHY);
            assertThat(result.action()).isEqualTo(RequiredAction.NONE);
            assertThat(result.increasesBlocked()).isFalse();
            assertThat(result.killSwitchRequired()).isFalse();
        }

        @Test
        @DisplayName("Inventory exactly at safety threshold → HEALTHY (< required)")
        void exactlyAtSafetyThreshold() {
            SafetyBoundary boundary = buildBoundary(7, 3);
            InventorySafetyResult result = evaluator.evaluate(7, boundary);

            assertThat(result.level()).isEqualTo(InventoryLevel.HEALTHY);
            assertThat(result.action()).isEqualTo(RequiredAction.NONE);
        }
    }

    @Nested
    @DisplayName("Safety level (below safetyDays but above criticalDays)")
    class SafetyLevel {

        @Test
        @DisplayName("Inventory below safety days → SAFETY, force decrease")
        void belowSafetyDays() {
            SafetyBoundary boundary = buildBoundary(7, 3);
            InventorySafetyResult result = evaluator.evaluate(5, boundary);

            assertThat(result.level()).isEqualTo(InventoryLevel.SAFETY);
            assertThat(result.action()).isEqualTo(RequiredAction.FORCE_DECREASE);
            assertThat(result.increasesBlocked()).isTrue();
            assertThat(result.killSwitchRequired()).isFalse();
        }

        @Test
        @DisplayName("Inventory exactly at critical threshold → SAFETY (not critical)")
        void exactlyAtCriticalThreshold() {
            SafetyBoundary boundary = buildBoundary(7, 3);
            InventorySafetyResult result = evaluator.evaluate(3, boundary);

            assertThat(result.level()).isEqualTo(InventoryLevel.SAFETY);
            assertThat(result.action()).isEqualTo(RequiredAction.FORCE_DECREASE);
            assertThat(result.killSwitchRequired()).isFalse();
        }
    }

    @Nested
    @DisplayName("Critical level (below criticalDays)")
    class CriticalLevel {

        @Test
        @DisplayName("Inventory below critical days → CRITICAL, kill switch + reduce")
        void belowCriticalDays() {
            SafetyBoundary boundary = buildBoundary(7, 3);
            InventorySafetyResult result = evaluator.evaluate(2, boundary);

            assertThat(result.level()).isEqualTo(InventoryLevel.CRITICAL);
            assertThat(result.action()).isEqualTo(RequiredAction.KILL_SWITCH_AND_REDUCE);
            assertThat(result.increasesBlocked()).isTrue();
            assertThat(result.killSwitchRequired()).isTrue();
        }

        @Test
        @DisplayName("Inventory at zero → CRITICAL")
        void inventoryAtZero() {
            SafetyBoundary boundary = buildBoundary(7, 3);
            InventorySafetyResult result = evaluator.evaluate(0, boundary);

            assertThat(result.level()).isEqualTo(InventoryLevel.CRITICAL);
            assertThat(result.action()).isEqualTo(RequiredAction.KILL_SWITCH_AND_REDUCE);
            assertThat(result.killSwitchRequired()).isTrue();
        }
    }

    @Nested
    @DisplayName("Unavailable inventory data (fail-closed)")
    class UnavailableData {

        @Test
        @DisplayName("Null inventory → UNAVAILABLE, block increases")
        void nullInventory() {
            SafetyBoundary boundary = buildBoundary(7, 3);
            InventorySafetyResult result = evaluator.evaluate(null, boundary);

            assertThat(result.level()).isEqualTo(InventoryLevel.UNAVAILABLE);
            assertThat(result.action()).isEqualTo(RequiredAction.BLOCK_INCREASES);
            assertThat(result.increasesBlocked()).isTrue();
            assertThat(result.killSwitchRequired()).isFalse();
        }
    }

    @Nested
    @DisplayName("Custom boundary thresholds")
    class CustomThresholds {

        @Test
        @DisplayName("Custom safety=14 and critical=5 respected")
        void customThresholds() {
            SafetyBoundary boundary = buildBoundary(14, 5);

            // 10 days: below safety (14) but above critical (5) → SAFETY
            InventorySafetyResult result = evaluator.evaluate(10, boundary);
            assertThat(result.level()).isEqualTo(InventoryLevel.SAFETY);
            assertThat(result.safetyThreshold()).isEqualTo(14);
            assertThat(result.criticalThreshold()).isEqualTo(5);

            // 4 days: below critical (5) → CRITICAL
            InventorySafetyResult critical = evaluator.evaluate(4, boundary);
            assertThat(critical.level()).isEqualTo(InventoryLevel.CRITICAL);

            // 20 days: above safety (14) → HEALTHY
            InventorySafetyResult healthy = evaluator.evaluate(20, boundary);
            assertThat(healthy.level()).isEqualTo(InventoryLevel.HEALTHY);
        }
    }

    @Nested
    @DisplayName("Default thresholds")
    class DefaultThresholds {

        @Test
        @DisplayName("Uses default thresholds (safety=7, critical=3) when not configured")
        void defaultThresholdsUsed() {
            SafetyBoundary boundary = SafetyBoundaryResolver.resolve(
                    null, null, SafetyBoundaryLimits.empty(), null);

            // 5 days: below default safety (7) → SAFETY
            InventorySafetyResult result = evaluator.evaluate(5, boundary);
            assertThat(result.level()).isEqualTo(InventoryLevel.SAFETY);
            assertThat(result.safetyThreshold()).isEqualTo(7);
            assertThat(result.criticalThreshold()).isEqualTo(3);
        }
    }
}
