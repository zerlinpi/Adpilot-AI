package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.support.SafetyBoundary;

import java.math.BigDecimal;

/**
 * Evaluates inventory safety constraints for budget decisions (Requirements 4.4, 25).
 *
 * <p>Three levels of inventory response:
 * <ol>
 *   <li><b>Healthy</b> (inventory &gt; inventoryHealthyDays): normal optimization allowed.</li>
 *   <li><b>Safety</b> (inventory &lt; inventorySafetyDays): force budget decrease
 *       regardless of ACoS performance; no budget increases allowed.</li>
 *   <li><b>Critical</b> (inventory &lt; inventoryCriticalDays): apply internal AI
 *       kill switch immediately AND emit a risk-0.9 budget reduction candidate.</li>
 * </ol>
 *
 * <p>When inventory data is unavailable (null), this evaluator <b>fails closed</b>:
 * no budget increases are permitted, but decreases are allowed.</p>
 *
 * <p>Validates: Requirements 4.4, 25.1, 25.2, 25.3, 25.4, 25.5.</p>
 */
public interface InventorySafetyEvaluator {

    /**
     * Evaluate inventory safety for a campaign.
     *
     * @param inventoryDays    available inventory days (null if data unavailable)
     * @param resolvedBoundary the campaign's resolved safety boundary
     * @return the inventory safety evaluation result
     */
    InventorySafetyResult evaluate(Integer inventoryDays, SafetyBoundary resolvedBoundary);

    /**
     * Inventory safety level classification.
     */
    enum InventoryLevel {
        /** Above inventoryHealthyDays — normal optimization. */
        HEALTHY,
        /** Between inventoryCriticalDays and inventorySafetyDays — force decrease, no increases. */
        SAFETY,
        /** Below inventoryCriticalDays — kill switch + emergency reduction. */
        CRITICAL,
        /** Inventory data unavailable — fail-closed (block increases, allow decreases). */
        UNAVAILABLE
    }

    /**
     * Required action based on inventory safety evaluation.
     */
    enum RequiredAction {
        /** No restriction — proceed normally. */
        NONE,
        /** Force a budget decrease regardless of other signals; block increases. */
        FORCE_DECREASE,
        /** Apply internal AI kill switch immediately AND emit risk-0.9 reduction. */
        KILL_SWITCH_AND_REDUCE,
        /** Fail-closed: block increases, allow decreases. */
        BLOCK_INCREASES
    }

    /**
     * Result of the inventory safety evaluation.
     *
     * @param level           the classified inventory level
     * @param action          the required action
     * @param inventoryDays   the input inventory days (null if unavailable)
     * @param safetyThreshold the inventorySafetyDays threshold used
     * @param criticalThreshold the inventoryCriticalDays threshold used
     * @param reason          description of the result
     */
    record InventorySafetyResult(
            InventoryLevel level,
            RequiredAction action,
            Integer inventoryDays,
            int safetyThreshold,
            int criticalThreshold,
            String reason
    ) {
        /** Factory for a healthy result. */
        public static InventorySafetyResult healthy(Integer inventoryDays,
                                                    int safetyThreshold,
                                                    int criticalThreshold) {
            return new InventorySafetyResult(InventoryLevel.HEALTHY, RequiredAction.NONE,
                    inventoryDays, safetyThreshold, criticalThreshold, null);
        }

        /** Factory for safety-level result. */
        public static InventorySafetyResult safety(Integer inventoryDays,
                                                   int safetyThreshold,
                                                   int criticalThreshold) {
            return new InventorySafetyResult(InventoryLevel.SAFETY, RequiredAction.FORCE_DECREASE,
                    inventoryDays, safetyThreshold, criticalThreshold,
                    "INVENTORY_SAFETY: inventory(" + inventoryDays + ") < safetyDays(" + safetyThreshold + ")");
        }

        /** Factory for critical-level result. */
        public static InventorySafetyResult critical(Integer inventoryDays,
                                                     int safetyThreshold,
                                                     int criticalThreshold) {
            return new InventorySafetyResult(InventoryLevel.CRITICAL, RequiredAction.KILL_SWITCH_AND_REDUCE,
                    inventoryDays, safetyThreshold, criticalThreshold,
                    "INVENTORY_CRITICAL: inventory(" + inventoryDays + ") < criticalDays(" + criticalThreshold + ")");
        }

        /** Factory for unavailable data (fail-closed). */
        public static InventorySafetyResult unavailable(int safetyThreshold, int criticalThreshold) {
            return new InventorySafetyResult(InventoryLevel.UNAVAILABLE, RequiredAction.BLOCK_INCREASES,
                    null, safetyThreshold, criticalThreshold,
                    "INVENTORY_UNAVAILABLE: fail-closed — no increases permitted");
        }

        /** Whether budget increases are blocked. */
        public boolean increasesBlocked() {
            return action == RequiredAction.FORCE_DECREASE
                    || action == RequiredAction.KILL_SWITCH_AND_REDUCE
                    || action == RequiredAction.BLOCK_INCREASES;
        }

        /** Whether the internal AI kill switch should be activated. */
        public boolean killSwitchRequired() {
            return action == RequiredAction.KILL_SWITCH_AND_REDUCE;
        }
    }
}
