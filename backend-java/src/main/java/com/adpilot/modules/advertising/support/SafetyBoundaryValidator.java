package com.adpilot.modules.advertising.support;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Validates proposed Safety_Boundary configurations to enforce the only-tighten
 * inheritance rule (Req 6.3) and cross-field ordering constraints (Req 6.8),
 * and produces audit entries with before/after values (Req 6.8, 12.6).
 *
 * <p>This is a pure service for its validation logic — all validation methods are
 * static/instance with no side effects beyond returning results. It is also a
 * Spring {@link Component} so it can be injected into controllers/services that
 * need to validate boundary changes before persisting them.
 *
 * <h3>Only-Tighten Rule (Req 6.3)</h3>
 * A user may NOT configure a boundary at a lower level (e.g., campaign) that is
 * LESS restrictive than the resolved boundary from higher levels. For each limit
 * in the proposed set, we resolve the effective boundary from ALL HIGHER levels
 * and check that the proposed value is at least as restrictive (or equal).
 *
 * <h3>Cross-Field Constraints (Req 6.8)</h3>
 * Certain limits have ordering relationships that must hold within a single boundary set:
 * <ul>
 *   <li>{@code minBid ≤ maxBid}</li>
 *   <li>{@code minDailyBudget ≤ maxDailyBudget}</li>
 *   <li>{@code inventoryCriticalDays ≤ inventorySafetyDays ≤ inventoryHealthyDays}</li>
 * </ul>
 *
 * <h3>Auditing (Req 6.8, 12.6)</h3>
 * When a boundary change passes validation, the validator produces
 * {@link BoundaryChangeAuditEntry} records capturing before/after values and actor.
 */
@Component
public class SafetyBoundaryValidator {

    /**
     * Validate a proposed boundary limit set against the only-tighten rule.
     *
     * <p>For each limit in {@code proposed}, checks that the value is at least as
     * restrictive as the resolved boundary from levels ABOVE the target level.
     * The higher-level boundary is resolved using {@link SafetyBoundaryResolver}
     * from only those levels that are strictly above the target level.
     *
     * @param proposed             the limits the user wants to set at the target level
     * @param targetLevel          the level at which the user is configuring
     * @param higherLevelBoundary  the resolved boundary from all levels strictly above
     *                             the target level (computed by the caller via
     *                             {@link SafetyBoundaryResolver})
     * @return validation result with any only-tighten violations
     */
    public BoundaryValidationResult validateOnlyTighten(SafetyBoundaryLimits proposed,
                                                         SafetyBoundaryLevel targetLevel,
                                                         SafetyBoundary higherLevelBoundary) {
        if (proposed == null || higherLevelBoundary == null) {
            return BoundaryValidationResult.success();
        }

        List<BoundaryValidationResult.ConstraintViolation> violations = new ArrayList<>();

        for (Map.Entry<SafetyBoundaryLimit, BigDecimal> entry : proposed.asMap().entrySet()) {
            SafetyBoundaryLimit limit = entry.getKey();
            BigDecimal proposedValue = entry.getValue();

            if (proposedValue == null) {
                continue;
            }

            // Check if the higher levels define this limit
            if (!higherLevelBoundary.isDefined(limit)) {
                continue; // No higher-level constraint — any value is acceptable
            }

            BigDecimal constrainingValue = higherLevelBoundary.get(limit).orElse(null);
            if (constrainingValue == null) {
                continue;
            }

            // Skip SET_INTERSECTION limits (not representable as scalar comparison)
            if (limit.comparisonSemantics() == BoundaryComparison.SET_INTERSECTION) {
                continue;
            }

            if (isLessRestrictive(limit, proposedValue, constrainingValue)) {
                SafetyBoundaryLevel constrainingLevel = higherLevelBoundary.sourceOf(limit)
                        .orElse(null);
                violations.add(new BoundaryValidationResult.ConstraintViolation(
                        BoundaryValidationResult.ViolationType.ONLY_TIGHTEN,
                        limit,
                        proposedValue,
                        constrainingValue,
                        constrainingLevel,
                        null,
                        buildOnlyTightenMessage(limit, proposedValue, constrainingValue,
                                constrainingLevel, targetLevel)
                ));
            }
        }

        return violations.isEmpty()
                ? BoundaryValidationResult.success()
                : BoundaryValidationResult.failure(violations);
    }

    /**
     * Validate cross-field ordering constraints within a single boundary set (Req 6.8).
     *
     * <p>The constraints are:
     * <ul>
     *   <li>{@code minBid ≤ maxBid}</li>
     *   <li>{@code minDailyBudget ≤ maxDailyBudget}</li>
     *   <li>{@code inventoryCriticalDays ≤ inventorySafetyDays ≤ inventoryHealthyDays}</li>
     * </ul>
     *
     * <p>If a limit is not defined in the effective set, the corresponding constraint
     * is not checked (a missing limit means "no constraint from this field").
     *
     * @param effectiveLimits the fully resolved limits that will be in effect after the
     *                        proposed change is applied (proposed merged with resolved
     *                        higher-level values)
     * @return validation result with any cross-field violations
     */
    public BoundaryValidationResult validateCrossFieldConstraints(SafetyBoundaryLimits effectiveLimits) {
        if (effectiveLimits == null) {
            return BoundaryValidationResult.success();
        }

        List<BoundaryValidationResult.ConstraintViolation> violations = new ArrayList<>();

        // minBid ≤ maxBid
        checkOrdering(effectiveLimits, SafetyBoundaryLimit.MIN_BID,
                SafetyBoundaryLimit.MAX_BID, "minBid must be ≤ maxBid", violations);

        // minDailyBudget ≤ maxDailyBudget
        checkOrdering(effectiveLimits, SafetyBoundaryLimit.MIN_DAILY_BUDGET,
                SafetyBoundaryLimit.MAX_DAILY_BUDGET,
                "minDailyBudget must be ≤ maxDailyBudget", violations);

        // inventoryCriticalDays ≤ inventorySafetyDays
        checkOrdering(effectiveLimits, SafetyBoundaryLimit.INVENTORY_CRITICAL_DAYS,
                SafetyBoundaryLimit.INVENTORY_SAFETY_DAYS,
                "inventoryCriticalDays must be ≤ inventorySafetyDays", violations);

        // inventorySafetyDays ≤ inventoryHealthyDays
        checkOrdering(effectiveLimits, SafetyBoundaryLimit.INVENTORY_SAFETY_DAYS,
                SafetyBoundaryLimit.INVENTORY_HEALTHY_DAYS,
                "inventorySafetyDays must be ≤ inventoryHealthyDays", violations);

        return violations.isEmpty()
                ? BoundaryValidationResult.success()
                : BoundaryValidationResult.failure(violations);
    }

    /**
     * Perform a full validation: only-tighten + cross-field constraints combined.
     *
     * @param proposed             the limits being set at the target level
     * @param targetLevel          the level being configured
     * @param higherLevelBoundary  resolved boundary from levels above the target
     * @param effectiveLimits      the fully resolved limits after the proposed change
     *                             is applied (for cross-field checks)
     * @return combined validation result
     */
    public BoundaryValidationResult validate(SafetyBoundaryLimits proposed,
                                              SafetyBoundaryLevel targetLevel,
                                              SafetyBoundary higherLevelBoundary,
                                              SafetyBoundaryLimits effectiveLimits) {
        BoundaryValidationResult tightenResult =
                validateOnlyTighten(proposed, targetLevel, higherLevelBoundary);
        BoundaryValidationResult crossFieldResult =
                validateCrossFieldConstraints(effectiveLimits);

        if (tightenResult.valid() && crossFieldResult.valid()) {
            return BoundaryValidationResult.success();
        }

        List<BoundaryValidationResult.ConstraintViolation> allViolations = new ArrayList<>();
        allViolations.addAll(tightenResult.violations());
        allViolations.addAll(crossFieldResult.violations());
        return BoundaryValidationResult.failure(allViolations);
    }

    /**
     * Generate audit entries for a boundary change. Compares the proposed limits
     * to the current limits at the same level, producing one
     * {@link BoundaryChangeAuditEntry} per changed or newly set limit.
     *
     * @param currentLimits the limits currently configured at this level (may be null/empty)
     * @param proposedLimits the new limits being set
     * @param scope         the scope string (e.g., "campaign", "store")
     * @param scopeId       the scope ID (e.g., campaign UUID)
     * @param actorId       the user making the change
     * @param actorName     the user's display name
     * @return list of audit entries for all changed limits
     */
    public List<BoundaryChangeAuditEntry> buildAuditEntries(SafetyBoundaryLimits currentLimits,
                                                             SafetyBoundaryLimits proposedLimits,
                                                             String scope,
                                                             UUID scopeId,
                                                             UUID actorId,
                                                             String actorName) {
        if (proposedLimits == null) {
            return List.of();
        }

        SafetyBoundaryLimits current = currentLimits != null ? currentLimits : SafetyBoundaryLimits.empty();
        List<BoundaryChangeAuditEntry> entries = new ArrayList<>();
        Instant now = Instant.now();

        for (Map.Entry<SafetyBoundaryLimit, BigDecimal> entry : proposedLimits.asMap().entrySet()) {
            SafetyBoundaryLimit limit = entry.getKey();
            BigDecimal newValue = entry.getValue();

            BigDecimal oldValue = current.get(limit).orElse(null);

            // Only record if the value actually changed (or is newly set)
            if (oldValue == null && newValue != null) {
                entries.add(new BoundaryChangeAuditEntry(
                        limit, scope, scopeId, null, newValue, actorId, actorName, now));
            } else if (oldValue != null && newValue != null
                    && oldValue.compareTo(newValue) != 0) {
                entries.add(new BoundaryChangeAuditEntry(
                        limit, scope, scopeId, oldValue, newValue, actorId, actorName, now));
            }
        }

        return entries;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Determine whether {@code proposedValue} is LESS restrictive than
     * {@code constrainingValue} for the given limit's comparison semantics.
     */
    static boolean isLessRestrictive(SafetyBoundaryLimit limit,
                                     BigDecimal proposedValue,
                                     BigDecimal constrainingValue) {
        return switch (limit.comparisonSemantics()) {
            case UPPER_BOUND ->
                // Upper-bound: smaller is more restrictive. Proposed is less restrictive
                // if it is LARGER than the constraining value.
                    proposedValue.compareTo(constrainingValue) > 0;
            case LOWER_BOUND ->
                // Lower-bound: larger is more restrictive. Proposed is less restrictive
                // if it is SMALLER than the constraining value.
                    proposedValue.compareTo(constrainingValue) < 0;
            case BOOLEAN_OR ->
                // Boolean OR: enabling is "more restrictive" (it triggers emergency).
                // A proposed value of 0 (disabled) when the higher level has 1 (enabled)
                // would be less restrictive. But disabling at a lower level cannot override
                // a higher-level enable (the resolved value is always OR). Actually, since
                // boolean OR means "any level enables it", setting 0 at a lower level is
                // not really "less restrictive" — the resolved value would still be 1.
                // We treat a proposed 0 as not conflicting because the OR resolution
                // guarantees the higher-level 1 still wins regardless.
                    false;
            case SET_INTERSECTION ->
                // Set intersection is not scalar-comparable; handled separately.
                    false;
        };
    }

    /**
     * Check that lowerLimit ≤ upperLimit within the effective limits.
     */
    private void checkOrdering(SafetyBoundaryLimits limits,
                               SafetyBoundaryLimit lowerLimit,
                               SafetyBoundaryLimit upperLimit,
                               String message,
                               List<BoundaryValidationResult.ConstraintViolation> violations) {
        BigDecimal lowerVal = limits.get(lowerLimit).orElse(null);
        BigDecimal upperVal = limits.get(upperLimit).orElse(null);

        if (lowerVal == null || upperVal == null) {
            return; // Cannot validate if either side is undefined
        }

        if (lowerVal.compareTo(upperVal) > 0) {
            violations.add(new BoundaryValidationResult.ConstraintViolation(
                    BoundaryValidationResult.ViolationType.CROSS_FIELD,
                    lowerLimit,
                    lowerVal,
                    upperVal,
                    null,
                    upperLimit,
                    message + " (got " + lowerLimit.name() + "=" + lowerVal
                            + " > " + upperLimit.name() + "=" + upperVal + ")"
            ));
        }
    }

    private String buildOnlyTightenMessage(SafetyBoundaryLimit limit,
                                           BigDecimal proposedValue,
                                           BigDecimal constrainingValue,
                                           SafetyBoundaryLevel constrainingLevel,
                                           SafetyBoundaryLevel targetLevel) {
        String levelName = constrainingLevel != null ? constrainingLevel.name() : "higher level";
        String semantics = limit.comparisonSemantics() == BoundaryComparison.UPPER_BOUND
                ? "smaller is more restrictive"
                : "larger is more restrictive";
        return String.format(
                "Cannot set %s to %s at %s level: the %s level constrains it to %s (%s)",
                limit.name(), proposedValue, targetLevel.name(),
                levelName, constrainingValue, semantics
        );
    }
}
