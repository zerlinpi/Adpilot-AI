package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.hosting.InventorySafetyEvaluator.InventoryLevel;
import com.adpilot.modules.advertising.hosting.InventorySafetyEvaluator.InventorySafetyResult;
import com.adpilot.modules.advertising.hosting.InventorySafetyEvaluator.RequiredAction;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryResolver;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the {@link InventorySafetyEvaluator}'s inventory-critical response.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 34: Inventory-critical response
 *
 * <p><b>Validates: Requirements 25.3</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>For any inventory days value below the critical threshold, the evaluator ALWAYS
 *       returns CRITICAL level with KILL_SWITCH_AND_REDUCE action.</li>
 *   <li>For any inventory days at or above critical but below safety, the evaluator
 *       returns SAFETY with FORCE_DECREASE.</li>
 *   <li>The kill switch is required (killSwitchRequired()=true) ONLY when level is CRITICAL.</li>
 *   <li>Budget increases are blocked (increasesBlocked()=true) for SAFETY, CRITICAL,
 *       and UNAVAILABLE levels.</li>
 *   <li>Null inventory always results in UNAVAILABLE with BLOCK_INCREASES (fail-closed, Req 25.5).</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 34: Inventory-critical response")
class InventoryCriticalResponsePropertyTest {

    private final InventorySafetyEvaluator evaluator = new InventorySafetyEvaluatorImpl();

    // ── Generators ────────────────────────────────────────────────────────────

    /**
     * Generates valid threshold pairs where criticalDays < safetyDays.
     * Critical range: [1, 30], Safety range: [criticalDays+1, 60].
     */
    @Provide
    Arbitrary<int[]> validThresholdPairs() {
        return Arbitraries.integers().between(1, 30).flatMap(critical ->
                Arbitraries.integers().between(critical + 1, critical + 30)
                        .map(safety -> new int[]{safety, critical})
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SafetyBoundary buildBoundary(int safetyDays, int criticalDays) {
        SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                .inventorySafetyDays(BigDecimal.valueOf(safetyDays))
                .inventoryCriticalDays(BigDecimal.valueOf(criticalDays))
                .build();
        return SafetyBoundaryResolver.resolve(null, null, limits, null);
    }

    // ── Property 1: Below critical → CRITICAL + KILL_SWITCH_AND_REDUCE ────────

    /**
     * For any inventory days value strictly below the critical threshold, the evaluator
     * ALWAYS returns CRITICAL level with KILL_SWITCH_AND_REDUCE action.
     */
    @Property(tries = 500)
    @Label("Below critical threshold → CRITICAL level with KILL_SWITCH_AND_REDUCE")
    void belowCriticalAlwaysReturnsCriticalWithKillSwitch(
            @ForAll("validThresholdPairs") int[] thresholds) {

        int safetyDays = thresholds[0];
        int criticalDays = thresholds[1];
        SafetyBoundary boundary = buildBoundary(safetyDays, criticalDays);

        // Generate inventory days strictly below critical (0 to criticalDays-1)
        for (int inventoryDays = 0; inventoryDays < criticalDays; inventoryDays++) {
            InventorySafetyResult result = evaluator.evaluate(inventoryDays, boundary);

            assertThat(result.level())
                    .as("inventory=%d with criticalThreshold=%d must be CRITICAL",
                            inventoryDays, criticalDays)
                    .isEqualTo(InventoryLevel.CRITICAL);

            assertThat(result.action())
                    .as("CRITICAL level must have KILL_SWITCH_AND_REDUCE action")
                    .isEqualTo(RequiredAction.KILL_SWITCH_AND_REDUCE);
        }
    }

    /**
     * Focused property: random inventory values in [0, criticalDays-1] produce CRITICAL.
     */
    @Property(tries = 500)
    @Label("Random inventory below critical → CRITICAL + KILL_SWITCH_AND_REDUCE")
    void randomInventoryBelowCriticalIsAlwaysCritical(
            @ForAll @IntRange(min = 1, max = 30) int criticalDays,
            @ForAll @IntRange(min = 31, max = 60) int safetyDays,
            @ForAll @IntRange(min = 0, max = 100) int rawInventory) {

        // Only test when safetyDays > criticalDays (valid configuration)
        Assume.that(safetyDays > criticalDays);
        // Only test when inventory is below critical
        int inventoryDays = rawInventory % criticalDays; // Ensure [0, criticalDays-1]

        SafetyBoundary boundary = buildBoundary(safetyDays, criticalDays);
        InventorySafetyResult result = evaluator.evaluate(inventoryDays, boundary);

        assertThat(result.level()).isEqualTo(InventoryLevel.CRITICAL);
        assertThat(result.action()).isEqualTo(RequiredAction.KILL_SWITCH_AND_REDUCE);
    }

    // ── Property 2: At/above critical but below safety → SAFETY + FORCE_DECREASE ──

    /**
     * For any inventory days at or above critical but strictly below safety, the evaluator
     * returns SAFETY level with FORCE_DECREASE action.
     */
    @Property(tries = 500)
    @Label("At or above critical but below safety → SAFETY level with FORCE_DECREASE")
    void betweenCriticalAndSafetyReturnsSafetyWithForceDecrease(
            @ForAll("validThresholdPairs") int[] thresholds) {

        int safetyDays = thresholds[0];
        int criticalDays = thresholds[1];
        SafetyBoundary boundary = buildBoundary(safetyDays, criticalDays);

        // Inventory in [criticalDays, safetyDays-1]
        for (int inventoryDays = criticalDays; inventoryDays < safetyDays; inventoryDays++) {
            InventorySafetyResult result = evaluator.evaluate(inventoryDays, boundary);

            assertThat(result.level())
                    .as("inventory=%d with critical=%d, safety=%d must be SAFETY",
                            inventoryDays, criticalDays, safetyDays)
                    .isEqualTo(InventoryLevel.SAFETY);

            assertThat(result.action())
                    .as("SAFETY level must have FORCE_DECREASE action")
                    .isEqualTo(RequiredAction.FORCE_DECREASE);
        }
    }

    // ── Property 3: killSwitchRequired() ONLY true for CRITICAL ───────────────

    /**
     * The kill switch is required (killSwitchRequired()=true) ONLY when the
     * evaluated level is CRITICAL. For all other levels, it must be false.
     */
    @Property(tries = 500)
    @Label("killSwitchRequired() is true ONLY for CRITICAL level")
    void killSwitchRequiredOnlyForCritical(
            @ForAll("validThresholdPairs") int[] thresholds,
            @ForAll @IntRange(min = 0, max = 100) int inventoryDays) {

        int safetyDays = thresholds[0];
        int criticalDays = thresholds[1];
        SafetyBoundary boundary = buildBoundary(safetyDays, criticalDays);

        InventorySafetyResult result = evaluator.evaluate(inventoryDays, boundary);

        if (result.level() == InventoryLevel.CRITICAL) {
            assertThat(result.killSwitchRequired())
                    .as("killSwitchRequired() must be true when level is CRITICAL")
                    .isTrue();
        } else {
            assertThat(result.killSwitchRequired())
                    .as("killSwitchRequired() must be false when level is %s", result.level())
                    .isFalse();
        }
    }

    /**
     * Also verify killSwitchRequired for null inventory (UNAVAILABLE level).
     */
    @Property(tries = 200)
    @Label("killSwitchRequired() is false for UNAVAILABLE (null inventory)")
    void killSwitchNotRequiredForUnavailable(
            @ForAll("validThresholdPairs") int[] thresholds) {

        int safetyDays = thresholds[0];
        int criticalDays = thresholds[1];
        SafetyBoundary boundary = buildBoundary(safetyDays, criticalDays);

        InventorySafetyResult result = evaluator.evaluate(null, boundary);

        assertThat(result.killSwitchRequired())
                .as("killSwitchRequired() must be false for UNAVAILABLE level")
                .isFalse();
    }

    // ── Property 4: increasesBlocked() for SAFETY, CRITICAL, and UNAVAILABLE ──

    /**
     * Budget increases are blocked (increasesBlocked()=true) for SAFETY, CRITICAL,
     * and UNAVAILABLE levels; not blocked for HEALTHY.
     */
    @Property(tries = 500)
    @Label("increasesBlocked() is true for SAFETY, CRITICAL, UNAVAILABLE; false for HEALTHY")
    void increasesBlockedForNonHealthyLevels(
            @ForAll("validThresholdPairs") int[] thresholds,
            @ForAll @IntRange(min = 0, max = 100) int inventoryDays) {

        int safetyDays = thresholds[0];
        int criticalDays = thresholds[1];
        SafetyBoundary boundary = buildBoundary(safetyDays, criticalDays);

        InventorySafetyResult result = evaluator.evaluate(inventoryDays, boundary);

        switch (result.level()) {
            case HEALTHY -> assertThat(result.increasesBlocked())
                    .as("HEALTHY level must NOT block increases")
                    .isFalse();
            case SAFETY -> assertThat(result.increasesBlocked())
                    .as("SAFETY level must block increases")
                    .isTrue();
            case CRITICAL -> assertThat(result.increasesBlocked())
                    .as("CRITICAL level must block increases")
                    .isTrue();
            case UNAVAILABLE -> assertThat(result.increasesBlocked())
                    .as("UNAVAILABLE level must block increases")
                    .isTrue();
        }
    }

    /**
     * Explicitly verify that null inventory (UNAVAILABLE) also blocks increases.
     */
    @Property(tries = 200)
    @Label("Null inventory → UNAVAILABLE → increasesBlocked()")
    void unavailableAlwaysBlocksIncreases(
            @ForAll("validThresholdPairs") int[] thresholds) {

        int safetyDays = thresholds[0];
        int criticalDays = thresholds[1];
        SafetyBoundary boundary = buildBoundary(safetyDays, criticalDays);

        InventorySafetyResult result = evaluator.evaluate(null, boundary);

        assertThat(result.increasesBlocked())
                .as("UNAVAILABLE (null inventory) must block increases (fail-closed)")
                .isTrue();
    }

    // ── Property 5: Null inventory → UNAVAILABLE with BLOCK_INCREASES ─────────

    /**
     * Null inventory always results in UNAVAILABLE with BLOCK_INCREASES action,
     * regardless of the configured thresholds (fail-closed, Req 25.5).
     */
    @Property(tries = 300)
    @Label("Null inventory → UNAVAILABLE level with BLOCK_INCREASES action (fail-closed)")
    void nullInventoryAlwaysUnavailableWithBlockIncreases(
            @ForAll("validThresholdPairs") int[] thresholds) {

        int safetyDays = thresholds[0];
        int criticalDays = thresholds[1];
        SafetyBoundary boundary = buildBoundary(safetyDays, criticalDays);

        InventorySafetyResult result = evaluator.evaluate(null, boundary);

        assertThat(result.level())
                .as("Null inventory must always produce UNAVAILABLE level")
                .isEqualTo(InventoryLevel.UNAVAILABLE);

        assertThat(result.action())
                .as("UNAVAILABLE must have BLOCK_INCREASES action")
                .isEqualTo(RequiredAction.BLOCK_INCREASES);

        assertThat(result.inventoryDays())
                .as("inventoryDays in result must be null when input is null")
                .isNull();

        assertThat(result.killSwitchRequired())
                .as("UNAVAILABLE must NOT require kill switch (different from CRITICAL)")
                .isFalse();

        assertThat(result.increasesBlocked())
                .as("UNAVAILABLE must block increases (fail-closed)")
                .isTrue();
    }

    // ── Property: Boundary thresholds are correctly captured in result ─────────

    /**
     * The result always captures the correct threshold values used in evaluation.
     */
    @Property(tries = 300)
    @Label("Result captures the correct safety and critical thresholds")
    void resultCapturesCorrectThresholds(
            @ForAll("validThresholdPairs") int[] thresholds,
            @ForAll @IntRange(min = 0, max = 100) int inventoryDays) {

        int safetyDays = thresholds[0];
        int criticalDays = thresholds[1];
        SafetyBoundary boundary = buildBoundary(safetyDays, criticalDays);

        InventorySafetyResult result = evaluator.evaluate(inventoryDays, boundary);

        assertThat(result.safetyThreshold())
                .as("Result must capture safetyThreshold from boundary")
                .isEqualTo(safetyDays);

        assertThat(result.criticalThreshold())
                .as("Result must capture criticalThreshold from boundary")
                .isEqualTo(criticalDays);
    }
}
