package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Default implementation of {@link InventorySafetyEvaluator} (Requirements 4.4, 25).
 *
 * <p>Evaluates inventory days against the resolved safety boundary thresholds:
 * <ul>
 *   <li>{@code inventoryCriticalDays} (default 3): below → kill switch + risk-0.9 reduction</li>
 *   <li>{@code inventorySafetyDays} (default 7): below → force decrease, block increases</li>
 *   <li>Above safety threshold → healthy, normal operation allowed</li>
 *   <li>Null/unavailable → fail-closed (block increases, allow decreases)</li>
 * </ul>
 *
 * <p>This is a pure evaluator — it does not activate kill switches or create
 * Operations. The calling orchestration layer is responsible for applying the
 * required actions indicated by the result.</p>
 *
 * <p>Validates: Requirements 4.4, 25.1, 25.2, 25.3, 25.4, 25.5.</p>
 */
@Service
public class InventorySafetyEvaluatorImpl implements InventorySafetyEvaluator {

    private static final Logger log = LoggerFactory.getLogger(InventorySafetyEvaluatorImpl.class);

    /** Default inventorySafetyDays if not configured in boundary (Req 25.2). */
    private static final int DEFAULT_SAFETY_DAYS = 7;

    /** Default inventoryCriticalDays if not configured in boundary (Req 25.3). */
    private static final int DEFAULT_CRITICAL_DAYS = 3;

    @Override
    public InventorySafetyResult evaluate(Integer inventoryDays, SafetyBoundary resolvedBoundary) {
        // Resolve thresholds from safety boundary
        int safetyThreshold = resolvedBoundary
                .get(SafetyBoundaryLimit.INVENTORY_SAFETY_DAYS)
                .map(BigDecimal::intValue)
                .orElse(DEFAULT_SAFETY_DAYS);

        int criticalThreshold = resolvedBoundary
                .get(SafetyBoundaryLimit.INVENTORY_CRITICAL_DAYS)
                .map(BigDecimal::intValue)
                .orElse(DEFAULT_CRITICAL_DAYS);

        // Fail-closed when inventory data is unavailable (Req 25.5)
        if (inventoryDays == null) {
            log.warn("Inventory data unavailable — fail-closed: blocking increases");
            return InventorySafetyResult.unavailable(safetyThreshold, criticalThreshold);
        }

        // Critical level check first (most restrictive): Req 25.3
        // inventoryDays < inventoryCriticalDays → kill switch + emergency reduction
        if (inventoryDays < criticalThreshold) {
            log.warn("Inventory CRITICAL: {} days < critical threshold {} — kill switch required",
                    inventoryDays, criticalThreshold);
            return InventorySafetyResult.critical(inventoryDays, safetyThreshold, criticalThreshold);
        }

        // Safety level check: Req 4.4, 25.2
        // inventoryDays < inventorySafetyDays → force decrease, no increases
        if (inventoryDays < safetyThreshold) {
            log.info("Inventory SAFETY: {} days < safety threshold {} — forcing decrease",
                    inventoryDays, safetyThreshold);
            return InventorySafetyResult.safety(inventoryDays, safetyThreshold, criticalThreshold);
        }

        // Above safety threshold: healthy, normal operation
        return InventorySafetyResult.healthy(inventoryDays, safetyThreshold, criticalThreshold);
    }
}
